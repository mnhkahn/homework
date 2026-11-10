package com.homeworkbuddy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

class VocabularyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val words = intent.getStringArrayListExtra(EXTRA_WORDS).orEmpty()
        if (words.isEmpty()) {
            finish()
            return
        }
        setContent {
            HomeTheme {
                VocabularyScreen(words, onExit = ::finish, onComplete = {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TASK_ID, intent.getStringExtra(EXTRA_TASK_ID)))
                    finish()
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy(this).apply {
            markManagedActivityForeground()
            if (mode() == KioskMode.STUDY) applyForCurrentTime(this@VocabularyActivity)
        }
    }

    companion object {
        internal const val EXTRA_TASK_ID = "vocabulary_task_id"
        private const val EXTRA_WORDS = "vocabulary_words"

        internal fun intent(context: Context, task: HomeworkTask, words: List<String>) =
            Intent(context, VocabularyActivity::class.java)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putStringArrayListExtra(EXTRA_WORDS, ArrayList(words))
    }
}

private enum class DictationSetup { SELECTED, RANDOM }

@Composable
private fun VocabularyScreen(words: List<String>, onExit: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dictionary = remember(context) { VocabularyDictionary(context.applicationContext) }
    val audio = remember(context) { VocabularyAudio(context) }
    DisposableEffect(audio) { onDispose { audio.close() } }

    var entries by remember { mutableStateOf<List<VocabularyEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(dictionary, words, loadAttempt) {
        loading = true
        entries = dictionary.load(words)
        loading = false
    }

    var queue by rememberSaveable { mutableStateOf(intArrayOf()) }
    var position by rememberSaveable { mutableIntStateOf(0) }
    var played by rememberSaveable { mutableStateOf(false) }
    var playAttempt by remember { mutableIntStateOf(0) }
    var playbackError by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf<DictationSetup?>(null) }
    var sample by remember { mutableStateOf<Pair<VocabularyEntry, Float>?>(null) }
    var sampleError by remember { mutableStateOf(false) }
    val dictating = queue.isNotEmpty()

    fun exit() {
        if (dictating) confirmExit = true else onExit()
    }
    BackHandler(onBack = ::exit)

    LaunchedEffect(sample) {
        val (entry, rate) = sample ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                sampleError = !audio.playWord(entry, rate)
            } finally {
                sample = null
            }
        }
    }

    val current = queue.getOrNull(position)?.let(entries::getOrNull)
    LaunchedEffect(current, position, playAttempt, confirmExit) {
        if (confirmExit) return@LaunchedEffect
        val entry = current ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (played || playbackError) return@repeatOnLifecycle
            if (!audio.dictate(entry)) {
                playbackError = true
            } else if (position == queue.lastIndex) {
                played = true
                onComplete()
            } else {
                played = true
            }
        }
    }

    fun start(indices: IntArray) {
        setup = null
        position = 0
        played = false
        playbackError = false
        queue = indices
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = ::exit) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回作业") }
                Text(if (dictating) "单词听写" else "英语背单词", style = MaterialTheme.typography.headlineMedium)
            }

            if (dictating) {
                Text("听写 ${position + 1} / ${queue.size}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        loading -> "正在加载词卡…"
                        playbackError -> "发音播放失败，请重试。"
                        played && position == queue.lastIndex -> "听写完成，正在打开拍照…"
                        played -> "写完后点「下一个」。"
                        else -> "正在播放，请听写。"
                    },
                )
                if (played && position < queue.lastIndex || playbackError) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            played = false
                            playbackError = false
                            playAttempt++
                        }) { Text(if (playbackError) "重试播放" else "再听一遍") }
                        if (played) {
                            Button(onClick = {
                                played = false
                                position++
                            }) { Text("下一个（${position + 2}/${queue.size}）") }
                        }
                    }
                }
            } else {
                Text(if (loading) "正在加载词卡…" else "共 ${words.size} 个单词")
                val ready = !loading && entries.isNotEmpty() && sample == null
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = ready, onClick = { start(entries.indices.toList().toIntArray()) }) { Text("听写全部") }
                    FilledTonalButton(enabled = ready, onClick = { setup = DictationSetup.SELECTED }) { Text("听写选中的") }
                    FilledTonalButton(enabled = ready, onClick = { setup = DictationSetup.RANDOM }) { Text("随机听写") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (sampleError) Text("发音播放失败，请重试。", color = MaterialTheme.colorScheme.error)
                if (!loading && entries.any { !it.hasMeanings }) {
                    OutlinedButton(enabled = sample == null, onClick = { loadAttempt++ }) { Text("重新查询缺失释义") }
                }
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(if (LocalConfiguration.current.screenWidthDp >= 720) 2 else 1),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.word }) { entry ->
                        VocabularyCard(entry, sample == null) { rate ->
                            sampleError = false
                            sample = entry to rate
                        }
                    }
                }
            }
        }
    }

    setup?.let { mode ->
        DictationSetupDialog(mode, entries, onDismiss = { setup = null }, onStart = ::start)
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出听写？") },
            text = { Text("这次听写进度将丢失。") },
            confirmButton = { TextButton(onClick = onExit) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续听写") } },
        )
    }
}

@Composable
private fun VocabularyCard(entry: VocabularyEntry, canPlay: Boolean, onPlay: (Float) -> Unit) {
    Card(onClick = { onPlay(1f) }, enabled = canPlay, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.word, style = MaterialTheme.typography.headlineMedium)
            if (entry.phonetic.isNotEmpty()) Text(entry.phonetic, style = MaterialTheme.typography.titleMedium)
            if (entry.chunks.isNotEmpty()) {
                Text(buildAnnotatedString {
                    entry.chunks.forEachIndexed { index, part ->
                        if (index > 0) append(" · ")
                        if (index == entry.stress) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
                        else append(part)
                    }
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = canPlay, onClick = { onPlay(1f) }) { Text("播放") }
                OutlinedButton(enabled = canPlay, onClick = { onPlay(0.75f) }) { Text("0.75×") }
            }
            if (!entry.hasMeanings) Text("暂时无法获取释义")
            if (entry.translations.isNotEmpty()) {
                Text("中文释义", style = MaterialTheme.typography.labelMedium)
                entry.translations.forEach { Text(it) }
            }
            if (entry.definitions.isNotEmpty()) {
                Text("英文释义", style = MaterialTheme.typography.labelMedium)
                entry.definitions.forEach { Text(it) }
            }
        }
    }
}

@Composable
private fun DictationSetupDialog(
    mode: DictationSetup,
    entries: List<VocabularyEntry>,
    onDismiss: () -> Unit,
    onStart: (IntArray) -> Unit,
) {
    val isRandom = mode == DictationSetup.RANDOM
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var count by remember { mutableStateOf(minOf(10, entries.size).toString()) }
    val randomCount = count.toIntOrNull()?.takeIf { it in 1..entries.size }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRandom) "随机听写" else "选择要听写的单词") },
        text = {
            if (isRandom) {
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it },
                    singleLine = true,
                    label = { Text("听写数量（1–${entries.size}）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = randomCount == null,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(entries) { index, entry ->
                        val checked = index in selected
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(value = checked, role = Role.Checkbox) {
                                    selected = if (it) selected + index else selected - index
                                }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(listOfNotNull(entry.word, entry.translations.firstOrNull()).joinToString(" · "))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = if (isRandom) randomCount != null else selected.isNotEmpty(),
                onClick = {
                    val indices = if (isRandom) entries.indices.shuffled().take(checkNotNull(randomCount))
                    else selected.sorted()
                    onStart(indices.toIntArray())
                },
            ) { Text("开始听写") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
