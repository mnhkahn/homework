package com.homeworkbuddy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
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

class GameActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(::isAllowedGameUrl) ?: GAME_24_URL
        setContent { MaterialTheme { GameScreen(url, ::finish) } }
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy(this).apply {
            markManagedActivityForeground()
            if (mode() == KioskMode.STUDY) applyForCurrentTime(this@GameActivity)
        }
    }

    companion object {
        const val GAME_24_URL = "https://www.cyeam.com/game/24"
        const val SUDOKU_URL = "https://www.cyeam.com/game/sudoku"
        private const val EXTRA_URL = "game_url"

        fun intent(context: Context, url: String) =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_URL, url)

        private fun isAllowedGameUrl(url: String): Boolean =
            url == GAME_24_URL || url == SUDOKU_URL
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GameScreen(initialUrl: String, finish: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val goBack = {
        webView?.takeIf(WebView::canGoBack)?.goBack() ?: finish()
    }
    BackHandler(onBack = goBack)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = goBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回学习应用")
                }
                Text(
                    if (initialUrl == GameActivity.GAME_24_URL) "24 点" else "数独",
                    modifier = Modifier.padding(start = 14.dp),
                    fontSize = 24.sp,
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !request.url.isCyeamUrl()
                        }
                        loadUrl(initialUrl)
                        webView = this
                    }
                },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
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
