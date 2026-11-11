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
        val submissionRequired = intent.getBooleanExtra(EXTRA_SUBMISSION_REQUIRED, true)
        val readAlong = intent.getBooleanExtra(EXTRA_READ_ALONG, false)
        setContent {
            HomeTheme {
                VocabularyScreen(words, submissionRequired, readAlong, onExit = ::finish, onComplete = {
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
        private const val EXTRA_SUBMISSION_REQUIRED = "vocabulary_submission_required"
        private const val EXTRA_READ_ALONG = "vocabulary_read_along"

        internal fun intent(context: Context, task: HomeworkTask, words: List<String>) =
            Intent(context, VocabularyActivity::class.java)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_SUBMISSION_REQUIRED, !task.status.isSubmitted)
                .putExtra(EXTRA_READ_ALONG, task.type == HomeworkTaskType.ENGLISH_READING)
                .putStringArrayListExtra(EXTRA_WORDS, ArrayList(words))
    }
}

private enum class WordPracticeSetup { SELECTED, RANDOM }

private val ChineseCharacter = Regex("\\p{IsHan}")

@Composable
private fun VocabularyScreen(
    words: List<String>,
    submissionRequired: Boolean,
    readAlong: Boolean,
    onExit: () -> Unit,
    onComplete: () -> Unit,
) {
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
    var setup by remember { mutableStateOf<WordPracticeSetup?>(null) }
    var sample by remember { mutableStateOf<Pair<VocabularyEntry, Float>?>(null) }
    var sampleError by remember { mutableStateOf(false) }
    val practicing = queue.isNotEmpty()
    val practiceLabel = if (readAlong) "跟读" else "听写"

    fun exit() {
        if (practicing) confirmExit = true else onExit()
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
            val playbackSucceeded = if (readAlong) audio.playWord(entry, 0.75f) else audio.dictate(entry)
            if (!playbackSucceeded) {
                playbackError = true
            } else {
                played = true
                if (!readAlong && position == queue.lastIndex) {
                    if (submissionRequired) onComplete()
                    else queue = intArrayOf()
                }
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
                Text(
                    if (readAlong) "读单词" else if (practicing) "单词听写" else "英语背单词",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            if (practicing) {
                val lastWord = position == queue.lastIndex
                Text("$practiceLabel ${position + 1} / ${queue.size}", style = MaterialTheme.typography.headlineSmall)
                current?.let { entry ->
                    if (readAlong) Text(entry.word, style = MaterialTheme.typography.displaySmall)
                    entry.translations.firstOrNull {
                        ChineseCharacter.containsMatchIn(it) && !it.contains(entry.word, ignoreCase = true)
                    }?.let { hint ->
                        Text(hint, style = MaterialTheme.typography.headlineMedium)
                    }
                }
                Text(
                    when {
                        loading -> "正在加载词卡…"
                        playbackError -> "发音播放失败，请重试。"
                        readAlong && played && lastWord -> "跟读完最后一个单词后，进入课文阅读。"
                        readAlong && played -> "跟读后点「下一个」。"
                        readAlong -> "听示范发音，然后跟读。"
                        played && lastWord -> "听写完成，正在打开拍照…"
                        played -> "写完后点「下一个」。"
                        else -> "正在播放，请听写。"
                    },
                )
                if (played && (readAlong || !lastWord) || playbackError) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            played = false
                            playbackError = false
                            playAttempt++
                        }) { Text(if (playbackError) "重试播放" else "再听一遍") }
                        if (readAlong && lastWord) {
                            Button(onClick = onComplete) { Text("进入阅读") }
                        } else if (played || readAlong) {
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
                    Button(enabled = ready, onClick = { start(entries.indices.toList().toIntArray()) }) { Text("${practiceLabel}全部") }
                    FilledTonalButton(enabled = ready, onClick = { setup = WordPracticeSetup.SELECTED }) { Text("${practiceLabel}选中的") }
                    FilledTonalButton(enabled = ready, onClick = { setup = WordPracticeSetup.RANDOM }) { Text("随机$practiceLabel") }
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
        WordPracticeSetupDialog(mode, entries, practiceLabel, onDismiss = { setup = null }, onStart = ::start)
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出$practiceLabel？") },
            text = { Text("这次${practiceLabel}进度将丢失。") },
            confirmButton = { TextButton(onClick = onExit) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续$practiceLabel") } },
        )
    }
}

@Composable
private fun VocabularyCard(entry: VocabularyEntry, canPlay: Boolean, onPlay: (Float) -> Unit) {
    Card(onClick = { onPlay(1f) }, enabled = canPlay, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.word, style = MaterialTheme.typography.headlineMedium)
            entry.translations.forEach { Text(it, style = MaterialTheme.typography.titleLarge) }
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
            if (entry.definitions.isNotEmpty()) {
                Text("英文释义", style = MaterialTheme.typography.labelMedium)
                entry.definitions.forEach { Text(it) }
            }
        }
    }
}

@Composable
private fun WordPracticeSetupDialog(
    mode: WordPracticeSetup,
    entries: List<VocabularyEntry>,
    practiceLabel: String,
    onDismiss: () -> Unit,
    onStart: (IntArray) -> Unit,
) {
    val isRandom = mode == WordPracticeSetup.RANDOM
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var count by remember { mutableStateOf(minOf(10, entries.size).toString()) }
    val randomCount = count.toIntOrNull()?.takeIf { it in 1..entries.size }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRandom) "随机$practiceLabel" else "选择要${practiceLabel}的单词") },
        text = {
            if (isRandom) {
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it },
                    singleLine = true,
                    label = { Text("${practiceLabel}数量（1–${entries.size}）") },
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
            ) { Text("开始$practiceLabel") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
