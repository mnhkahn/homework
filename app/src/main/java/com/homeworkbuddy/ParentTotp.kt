package com.homeworkbuddy

import android.content.Context
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Standard TOTP (RFC 6238): HMAC-SHA1, six digits, 30 seconds. */
internal object ParentTotp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun newSecret(): String = encode(ByteArray(20).also(SecureRandom()::nextBytes))
    fun recoveryCode(): String = encode(ByteArray(10).also(SecureRandom()::nextBytes))

    fun encode(bytes: ByteArray): String {
        var buffer = 0; var bits = 0
        val output = StringBuilder()
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 255); bits += 8
            while (bits >= 5) { bits -= 5; output.append(ALPHABET[(buffer shr bits) and 31]) }
        }
        if (bits > 0) output.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return output.toString()
    }

    fun decode(secret: String): ByteArray {
        var buffer = 0; var bits = 0
        val bytes = ArrayList<Byte>()
        secret.forEach { char ->
            val value = ALPHABET.indexOf(char)
            require(value >= 0) { "Invalid Base32 secret" }
            buffer = (buffer shl 5) or value; bits += 5
            if (bits >= 8) { bits -= 8; bytes.add(((buffer shr bits) and 255).toByte()) }
        }
        return bytes.toByteArray()
    }

    fun code(secret: String, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(decode(secret), "HmacSHA1")) }
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = digest.last().toInt() and 15
        val binary = ((digest[offset].toInt() and 127) shl 24) or ((digest[offset + 1].toInt() and 255) shl 16) or
            ((digest[offset + 2].toInt() and 255) shl 8) or (digest[offset + 3].toInt() and 255)
        return String.format(Locale.US, "%06d", binary % 1_000_000)
    }

    fun matchingStep(secret: String, candidate: String, epochSeconds: Long, lastUsed: Long = -1): Long? {
        if (!candidate.matches(Regex("[0-9]{6}"))) return null
        val step = epochSeconds / 30
        return listOf(step, step - 1, step + 1).firstOrNull { it >= 0 && it > lastUsed &&
            MessageDigest.isEqual(code(secret, it).toByteArray(), candidate.toByteArray()) }
    }
}

/** Keeps the secret encrypted on the tablet and the single-use recovery code hashed. */
internal class ParentTotpStore(context: Context) {
    private val prefs = context.getSharedPreferences("parent_totp", Context.MODE_PRIVATE)
    val configured: Boolean get() = prefs.contains("secret")
    val lockedUntil: Long get() = prefs.getLong("locked_until", 0)

    fun activate(secret: String, code: String, now: Long = System.currentTimeMillis()): String? {
        val step = runCatching { ParentTotp.matchingStep(secret, code, now / 1000) }.getOrNull() ?: return null
        val recovery = ParentTotp.recoveryCode()
        val encrypted = DeviceTokenCipher.encrypt(secret)
        check(prefs.edit().putString("secret", encrypted).putString("recovery_hash", hash(recovery))
            .putLong("last_step", step).remove("failures").remove("locked_until").commit())
        return recovery
    }

    fun verify(value: String, recovery: Boolean = false, now: Long = System.currentTimeMillis()): Boolean = synchronized(verificationLock) {
        if (!configured || now < lockedUntil) return@synchronized false
        val valid = if (recovery) {
            val expected = prefs.getString("recovery_hash", null)
            expected != null && MessageDigest.isEqual(expected.toByteArray(), hash(value.trim().uppercase(Locale.ROOT)).toByteArray())
        } else {
            val secret = prefs.getString("secret", null)?.let(DeviceTokenCipher::decrypt)
            val matched = secret?.let { runCatching { ParentTotp.matchingStep(it, value.trim(), now / 1000, prefs.getLong("last_step", -1)) }.getOrNull() }
            if (matched != null) prefs.edit().putLong("last_step", matched).commit() else false
        }
        if (valid) {
            val edit = prefs.edit().remove("failures").remove("locked_until")
            if (recovery) edit.remove("recovery_hash")
            return@synchronized edit.commit()
        }
        val failures = prefs.getInt("failures", 0) + 1
        val edit = prefs.edit().putInt("failures", if (failures >= 5) 0 else failures)
        if (failures >= 5) edit.putLong("locked_until", now + 30_000)
        edit.commit()
        false
    }

    private companion object { val verificationLock = Any() }

    private fun hash(value: String): String = ParentTotp.encode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
