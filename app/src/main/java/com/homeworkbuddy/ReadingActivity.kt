package com.homeworkbuddy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Keep line-based headings and questionnaire choices separate, then split prose into sentences. */
internal fun readingSentences(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n')
    .lineSequence().flatMap { line ->
        line.trim().split(Regex("(?<=[.!?。！？])\\s+")).asSequence()
    }.filter { it.isNotBlank() }.map { it.trim() }.toList()

class ReadingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra("text").orEmpty()
        if (text.isBlank()) { finish(); return }
        setContent {
            HomeTheme {
                ReadingScreen(intent.getStringExtra("title").orEmpty(), text, ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy(this).apply {
            markManagedActivityForeground()
            if (mode() == KioskMode.STUDY) applyForCurrentTime(this@ReadingActivity)
        }
    }

    companion object {
        internal fun open(context: Context, task: HomeworkTask) {
            val text = HomeworkTaskDetails.readingText(task.description)
            if (text != null) {
                // Internal navigation also triggers MainActivity.onUserLeaveHint;
                // permit it before study mode schedules a return to the main screen.
                (context as? MainActivity)?.allowManagedActivityLaunch()
                context.startActivity(Intent(context, ReadingActivity::class.java)
                    .putExtra("title", task.title).putExtra("text", text))
            } else task.link?.takeIf(HomeworkLinks::isWebUrl)?.let { GameActivity.openHomeworkLink(context, it) }
        }
    }
}

@Composable
private fun ReadingScreen(title: String, text: String, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sentences = remember(text) { readingSentences(text) }
    val dictionary = remember { VocabularyDictionary(context.applicationContext) }
    val audio = remember { VocabularyAudio(context) }
    DisposableEffect(audio) { onDispose { audio.close() } }
    var playing by remember { mutableStateOf<Int?>(null) }
    var audioSources by rememberSaveable(text) { mutableStateOf(List(sentences.size) { "" }) }
    var playbackError by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(playing) {
        val index = playing ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val succeeded = audio.playSentence(sentences[index], 0.85f) { source ->
                    audioSources = audioSources.toMutableList().also { it[index] = source.name }
                }
                playbackError = if (succeeded) null else index
                if (!succeeded) audioSources = audioSources.toMutableList().also { it[index] = "" }
            } finally { playing = null }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onExit) { Text("返回作业") }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(sentences) { index, sentence ->
                    var translation by rememberSaveable(sentence) { mutableStateOf<String?>(null) }
                    var translating by remember { mutableStateOf(false) }
                    var translationError by remember { mutableStateOf(false) }
                    LaunchedEffect(translating) {
                        if (translating) {
                            try {
                                translation = dictionary.translate(sentence)
                                translationError = translation == null
                            } finally { translating = false }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${index + 1}. $sentence", style = MaterialTheme.typography.headlineSmall)
                            translation?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton(onClick = { playbackError = null; playing = index }, enabled = playing == null) {
                                    Text(if (playing == index) "朗读中…" else "朗读")
                                }
                                OutlinedButton(onClick = { translating = true }, enabled = !translating) {
                                    Text(if (translating) "翻译中…" else if (translationError) "重试翻译" else "翻译")
                                }
                                val source = audioSources.getOrNull(index)
                                if (!source.isNullOrEmpty()) {
                                    val isSystem = source == ReadingAudioSource.SYSTEM.name
                                    val label = if (isSystem) "系统语音" else "在线语音"
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(if (isSystem) Icons.Outlined.Smartphone else Icons.Outlined.Cloud,
                                            contentDescription = label, modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(label, style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (translationError) Text("翻译暂时不可用，请联网后重试。", color = MaterialTheme.colorScheme.error)
                            if (playbackError == index) Text("朗读失败，请重试。", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
