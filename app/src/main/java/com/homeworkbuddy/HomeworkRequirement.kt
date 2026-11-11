package com.homeworkbuddy

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.util.Locale

@Composable
internal fun HomeworkRequirement(taskId: String, text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(taskId, text, lifecycle) {
        val initialization = CompletableDeferred<Int>()
        val speech = TextToSpeech(context) { initialization.complete(it) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) speech.stop()
        }
        lifecycle.addObserver(observer)
        try {
            if (initialization.await() != TextToSpeech.SUCCESS) return@LaunchedEffect
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            if (speech.setLanguage(Locale.SIMPLIFIED_CHINESE) < TextToSpeech.LANG_AVAILABLE) return@LaunchedEffect

            text.chunked(TextToSpeech.getMaxSpeechInputLength()).forEachIndexed { index, part ->
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                speech.speak(part, queueMode, null, "homework-requirement-$taskId-$index")
            }
            awaitCancellation()
        } finally {
            lifecycle.removeObserver(observer)
            speech.stop()
            speech.shutdown()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFE9AE),
        contentColor = Color(0xFF48320C),
    ) {
        Column(
            modifier = Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("作业要求", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            HomeworkLinkedText(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}
