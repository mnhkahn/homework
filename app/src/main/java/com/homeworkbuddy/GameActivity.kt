package com.homeworkbuddy

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class GameActivity : ComponentActivity() {
    private var microphoneRequest: PermissionRequest? = null
    private var microphonePromptActive = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphonePromptActive = false
        val request = microphoneRequest
        microphoneRequest = null
        if (request != null) {
            if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else {
                request.deny()
                Toast.makeText(this, "跟读需要麦克风权限，请在应用设置中允许后重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestMicrophone(request: PermissionRequest, pageUrl: String?) {
        if (!WebMicrophonePolicy.allows(request.origin.toString(), pageUrl) ||
            PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources || microphonePromptActive
        ) {
            request.deny()
            return
        }
        cancelMicrophoneRequest()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            microphoneRequest = request
            microphonePromptActive = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun cancelMicrophoneRequest() {
        microphoneRequest?.deny()
        microphoneRequest = null
    }

    override fun onDestroy() {
        cancelMicrophoneRequest()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val homework = intent.getBooleanExtra(EXTRA_HOMEWORK, false)
        val requestedUrl = intent.getStringExtra(EXTRA_URL)
        val url = requestedUrl?.takeIf {
            if (homework) HomeworkLinks.isWebUrl(it) else isAllowedCyeamToolUrl(it)
        } ?: run {
            android.app.AlertDialog.Builder(this)
                .setTitle("无法打开学习链接")
                .setMessage(learningPageErrorMessage(requestedUrl,
                    if (homework) "作业链接格式无效，仅支持 HTTP 或 HTTPS 网页。"
                    else "该地址不在内置学习工具允许范围内。"))
                .setPositiveButton("返回作业") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        hideNavigationBar()
        setContent { MaterialTheme { GameScreen(url, ::finish, homework, ::requestMicrophone, ::cancelMicrophoneRequest) { canceled ->
            // A canceled WebView request is no longer valid: just forget it.
            if (microphoneRequest === canceled) microphoneRequest = null
        } } }
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy(this).apply {
            markManagedActivityForeground()
            if (mode() == KioskMode.STUDY) applyForCurrentTime(this@GameActivity)
        }
        hideNavigationBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    private fun hideNavigationBar() {
        // Keep clock/battery visible and use the in-app back button for navigation.
        // Reapply after keyboard/dialog/external-activity focus changes.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val policy = KioskPolicy(this)
        if (policy.isDeviceOwner && policy.mode() == KioskMode.STUDY) {
            Handler(Looper.getMainLooper()).postDelayed({
                policy.applyForCurrentTime(this, navigate = true)
            }, 200)
        }
    }

    companion object {
        const val GAME_24_URL = "https://www.cyeam.com/game/24"
        const val SUDOKU_URL = "https://www.cyeam.com/game/sudoku"
        const val ARITHMETIC_URL = "https://www.cyeam.com/tool/arithmetic"
        const val ENGLISH_READING_URL = "https://www.cyeam.com/ai/translate"
        private const val EXTRA_URL = "game_url"
        private const val EXTRA_HOMEWORK = "homework_link"

        fun intent(context: Context, url: String) =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_URL, url)

        fun homeworkIntent(context: Context, url: String) =
            intent(context, url).putExtra(EXTRA_HOMEWORK, true)

        /** All assignment and review web links stay in the app-owned WebView. */
        fun openHomeworkLink(context: Context, url: String) {
            require(HomeworkLinks.isWebUrl(url)) { "无效的作业链接" }
            (context as? MainActivity)?.allowManagedActivityLaunch()
            context.startActivity(homeworkIntent(context, url))
        }

        private fun isAllowedCyeamToolUrl(url: String): Boolean {
            if (url == GAME_24_URL || url == SUDOKU_URL || url == ARITHMETIC_URL || url == ENGLISH_READING_URL) return true

            // Vocabulary tasks append the assigned words as a query parameter to the
            // same first-party translate page. Keep that learning flow in the
            // app-owned WebView instead of handing the URL to an external browser.
            val uri = Uri.parse(url)
            return uri.scheme == "https" && uri.host == "www.cyeam.com" &&
                uri.path == "/ai/translate" && !uri.getQueryParameter("words").isNullOrBlank()
        }

        fun titleFor(url: String): String = when (url) {
            GAME_24_URL -> "24 点"
            SUDOKU_URL -> "数独"
            ARITHMETIC_URL -> "口算"
            ENGLISH_READING_URL -> "英语跟读"
            else -> if (Uri.parse(url).path == "/ai/translate") "背单词" else "学习应用"
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GameScreen(
    initialUrl: String,
    finish: () -> Unit,
    homework: Boolean = false,
    requestMicrophone: (PermissionRequest, String?) -> Unit,
    cancelMicrophoneRequest: () -> Unit,
    onMicrophoneRequestCanceled: (PermissionRequest) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ttsBridge by remember { mutableStateOf<WebViewTtsBridge?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    val goBack = {
        webView?.takeIf(WebView::canGoBack)?.goBack() ?: finish()
    }
    BackHandler(onBack = goBack)

    Surface(Modifier.fillMaxSize()) {
        // Reserve space for system bars/keyboard whenever Android keeps them visible.
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = goBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回学习应用")
                }
                Text(
                    if (homework) "作业链接" else GameActivity.titleFor(initialUrl),
                    modifier = Modifier.padding(start = 14.dp),
                    fontSize = 24.sp,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = {
                    webView?.apply {
                        // Clear HTTP resources, preserving cookies and saved learning text.
                        clearCache(true)
                        reload()
                    }
                }) {
                    Icon(Icons.Outlined.Refresh, "刷新网页")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        val bridge = WebViewTtsBridge(context).also {
                            it.attach(this)
                            ttsBridge = it
                        }
                        addJavascriptInterface(bridge, "__androidTts")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        if (Uri.parse(initialUrl).path == "/ai/translate") {
                            // The textbook script has a stable URL and a seven-day
                            // HTTP lifetime. Always fetch current learning content.
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        }
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) {
                                requestMicrophone(request, url)
                            }

                            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                                onMicrophoneRequestCanceled(request)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                cancelMicrophoneRequest()
                                bridge.cancel()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val blocked = if (homework) !HomeworkLinks.allowsNavigation(request.url.toString())
                                    else !request.url.isCyeamUrl()
                                if (blocked && request.isForMainFrame) {
                                    pageError = learningPageErrorMessage(request.url.toString(),
                                        if (homework && !HomeworkLinks.isWebUrl(request.url.toString())) "跳转地址无效或使用了不支持的协议，仅支持 HTTP 或 HTTPS 网页。"
                                        else if (homework) "该跳转不在学习网站白名单中。允许的域名：${HomeworkLinks.studyAllowedHosts.joinToString("、")}（含子域名）。"
                                        else "内置学习工具仅允许 HTTPS 的 cyeam.com 及其子域名。")
                                }
                                return blocked
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    pageError = learningPageErrorMessage(request.url.toString(), "网页加载失败（错误码 ${error.errorCode}）：${error.description}")
                                }
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) {
                                    pageError = learningPageErrorMessage(request.url.toString(), "网站返回 HTTP ${response.statusCode}。")
                                }
                            }

                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel()
                                // SSL callbacks include subresources; report only a page destination.
                                if (error.url == view.url || error.url == view.originalUrl || error.url == initialUrl) {
                                    pageError = learningPageErrorMessage(error.url, "HTTPS 证书验证失败（错误码 ${error.primaryError}）。")
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                // WebView ships without the Web Speech API; back the page's
                                // speechSynthesis usage (e.g. dictation) with the device TTS.
                                view.evaluateJavascript(WebViewTtsBridge.POLYFILL_JS, null)
                            }
                        }
                        // A parent explicitly assigned this URL. Later navigations use the allowlist.
                        loadUrl(initialUrl)
                        webView = this
                    }
                },
            )
        }
    }

    pageError?.let { message ->
        AlertDialog(
            onDismissRequest = { pageError = null },
            title = { Text("无法打开学习链接") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { pageError = null }) { Text("知道了") } },
            dismissButton = { TextButton(onClick = finish) { Text("返回作业") } },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            cancelMicrophoneRequest()
            ttsBridge?.shutdown()
            ttsBridge = null
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}

private fun Uri.isCyeamUrl(): Boolean =
    scheme == "https" && (host == "cyeam.com" || host?.endsWith(".cyeam.com") == true)
