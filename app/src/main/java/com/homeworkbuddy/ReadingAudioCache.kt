package com.homeworkbuddy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal enum class ReadingAudioSource { SYSTEM, ONLINE }
internal data class CachedReadingAudio(val file: File, val source: ReadingAudioSource)

/** Durable app-private audio. Publish only completed files; never retain failed synthesis/downloads. */
internal class ReadingAudioCache(private val directory: File, private val maxBytes: Long = 64L * 1024 * 1024) {
    private val mutex = Mutex()

    suspend fun getOrCreate(key: String, create: suspend (File) -> ReadingAudioSource?): CachedReadingAudio? = mutex.withLock {
        try {
            directory.mkdirs()
            val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            // Store provenance in the published filename so audio and source stay atomic.
            ReadingAudioSource.entries.forEach { source ->
                val cached = File(directory, "$name.${source.name.lowercase()}.audio")
                if (cached.isFile && cached.length() > 0) {
                    cached.setLastModified(System.currentTimeMillis())
                    return@withLock CachedReadingAudio(cached, source)
                }
            }
            val temporary = File.createTempFile("reading-", ".part", directory)
            try {
                val source = create(temporary) ?: return@withLock null
                if (temporary.length() == 0L || temporary.length() > maxBytes) return@withLock null
                val target = File(directory, "$name.${source.name.lowercase()}.audio")
                if (!temporary.renameTo(target)) return@withLock null
                var total = directory.listFiles().orEmpty().filter { it.extension == "audio" }.sumOf { it.length() }
                directory.listFiles().orEmpty().filter { it.extension == "audio" && it != target }
                    .sortedBy { it.lastModified() }.forEach { file ->
                        if (total > maxBytes) {
                            val size = file.length()
                            if (file.delete()) total -= size
                        }
                    }
                CachedReadingAudio(target, source)
            } finally {
                temporary.delete()
            }
        } catch (_: IOException) { null }
    }
}
