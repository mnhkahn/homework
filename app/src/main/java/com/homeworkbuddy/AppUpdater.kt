package com.homeworkbuddy

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppUpdateSettingsCard(activity: Activity) {
    val updater = AppUpdateViewModel.get(activity)
    val state by updater.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("版本更新", fontSize = 21.sp)
            Text("当前版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Button(
                enabled = state.stage != UpdateStage.CHECKING && state.stage != UpdateStage.DOWNLOADING,
                onClick = { updater.checkForUpdate() },
            ) { Text(if (state.stage == UpdateStage.CHECKING) "正在检查…" else "检查更新") }
            if (state.message.isNotBlank()) Text(state.message)
        }
    }
    AppUpdatePanel(updater)
}
