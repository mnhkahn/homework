package com.homeworkbuddy

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen notice placed over an app that is not on the study-time
 * allowlist. StudySessionService launches it when UsageStats reports a blocked
 * app in the foreground — the fallback for Lock Task escapes such as the
 * HyperOS tablet window menu.
 */
class StudyBlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent { MaterialTheme { StudyBlockScreen(this, intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { MaterialTheme { StudyBlockScreen(this, intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)) } }
    }

    override fun onResume() {
        super.onResume()
        // The block screen belongs to study time only; never linger after it ends.
        if (KioskPolicy(this).mode() != KioskMode.STUDY) finish()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "blocked_package"
    }
}

@Composable
private fun StudyBlockScreen(activity: StudyBlockActivity, blockedPackage: String?) {
    val label = remember(blockedPackage) {
        blockedPackage?.let {
            runCatching {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.packageManager.getApplicationInfo(it, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getApplicationInfo(it, 0)
                }
                activity.packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }
    }
    Surface(Modifier.fillMaxSize(), color = Color(0xFFFFFBFF)) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🔒", fontSize = 72.sp)
            Spacer(Modifier.height(18.dp))
            Text("现在是学习时间", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (label != null) "「$label」没有加入学习白名单，先完成作业吧。" else "这个应用没有加入学习白名单，先完成作业吧。",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = {
                activity.startActivity(
                    Intent(activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }) { Text("返回作业", fontSize = 20.sp) }
        }
    }
}
