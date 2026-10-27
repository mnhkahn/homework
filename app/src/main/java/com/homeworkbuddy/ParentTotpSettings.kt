package com.homeworkbuddy

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
internal fun ParentTotpSettings(onChanged: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ParentTotpStore(context) }
    var configured by remember { mutableStateOf(store.configured) }
    var enrolling by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("家长动态验证码", style = MaterialTheme.typography.titleLarge)
            Text(if (configured) "已启用 Google Authenticator，进入家长设置及临时开放需使用动态验证码。" else "绑定 Google Authenticator 后，使用手机上的动态验证码保护家长设置。")
            OutlinedButton(onClick = { enrolling = true }) { Text(if (configured) "重新绑定验证器" else "绑定验证器") }
        }
    }
    if (enrolling) {
        val secret = remember { ParentTotp.newSecret() }
        val issuer = "作业小伙伴"
        val uri = remember(secret) { Uri.Builder().scheme("otpauth").authority("totp").appendPath("$issuer:家长")
            .appendQueryParameter("secret", secret).appendQueryParameter("issuer", issuer)
            .appendQueryParameter("algorithm", "SHA1").appendQueryParameter("digits", "6").appendQueryParameter("period", "30").build().toString() }
        val qr = remember(uri) {
            val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 300, 300)
            Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until 300) for (x in 0 until 300) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }.asImageBitmap()
        }
        var code by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { enrolling = false }, title = { Text("绑定 Google Authenticator") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请用家长手机的 Google Authenticator 扫码，再输入手机显示的 6 位验证码。不要让孩子扫描。")
                Image(qr, "验证器绑定二维码", Modifier.size(220.dp))
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6 位验证码") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { Button(onClick = {
            runCatching { store.activate(secret, code) }.onSuccess { result ->
                if (result == null) error = "验证码不正确，请检查两台设备的时间。"
                else { recovery = result; configured = true; enrolling = false; onChanged() }
            }.onFailure { error = "保存失败，请重试。" }
        }, enabled = code.length == 6) { Text("验证并启用") } }, dismissButton = { TextButton(onClick = { enrolling = false }) { Text("取消") } })
    }
    recovery?.let { value ->
        AlertDialog(onDismissRequest = {}, title = { Text("保存一次性恢复码") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("换手机或验证器丢失时，用此恢复码进入家长设置并重新绑定。恢复码只能使用一次，请存放在孩子无法访问的安全位置。此页面关闭后无法再次查看。")
                SelectionContainer { Text(value, fontWeight = FontWeight.Bold) }
            }
        }, confirmButton = { Button(onClick = { recovery = null }) { Text("我已保存") } })
    }
}

@Composable
internal fun ParentCodeDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ParentTotpStore(context) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("验证后临时开放") }, text = {
        Column {
            Text("请输入 Google Authenticator 当前的 6 位验证码。已使用的验证码不能再次使用，请等待新的验证码。")
            OutlinedTextField(value, { value = it.filter(Char::isDigit).take(6) }, label = { Text("动态验证码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = {
        if (store.verify(value)) onVerified()
        else error = if (System.currentTimeMillis() < store.lockedUntil) "尝试次数过多，请等待 30 秒。" else "验证码无效或已使用，请检查设备时间后重试。"
    }) { Text("验证") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
