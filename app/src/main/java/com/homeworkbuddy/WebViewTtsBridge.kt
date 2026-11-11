package com.homeworkbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File
import java.util.Locale

/**
 * WebView has no Web Speech API, so the cyeam translate page cannot dictate.
 * This bridge backs the injected window.speechSynthesis polyfill with the
 * device TTS engine; the page itself stays unchanged.
 */
class WebViewTtsBridge(context: Context) : TextToSpeech.OnInitListener {
    private val cacheDir = context.cacheDir
    private var audioFile: File? = null
    private var player: MediaPlayer? = null
    private var playbackRate = 1f
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var webView: WebView? = null

    private var ready = false
    private var initFailed = false
    private var shutdown = false
    private var pendingAction: (() -> Unit)? = null

    private var utteranceCounter = 0
    private var currentUtteranceId: String? = null

    fun attach(view: WebView) {
        webView = view
    }

    override fun onInit(status: Int) {
        mainHandler.post {
            if (shutdown) return@post
            ready = status == TextToSpeech.SUCCESS
            initFailed = !ready
            if (!ready) {
                pendingAction = null
                notifyJs(JS_ON_ERROR)
                return@post
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Synthesis is silent; report start only when the audio is playing.
                }

                override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId, error = false)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId, error = true)

                override fun onError(utteranceId: String?, errorCode: Int) =
                    onUtteranceFinished(utteranceId, error = true)
            })
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    @JavascriptInterface
    fun speak(text: String, lang: String, rate: Float) {
        mainHandler.post {
            if (shutdown) return@post
            when {
                initFailed -> notifyJs(JS_ON_ERROR)
                !ready -> pendingAction = { speakNow(text, lang, rate) }
                else -> speakNow(text, lang, rate)
            }
        }
    }

    @JavascriptInterface
    fun cancel() {
        mainHandler.post {
            if (shutdown) return@post
            pendingAction = null
            interruptCurrent()
            tts?.stop()
        }
    }

    fun shutdown() {
        shutdown = true
        pendingAction = null
        interruptCurrent()
        tts?.stop()
        tts?.shutdown()
        tts = null
        webView = null
    }

    private fun speakNow(text: String, lang: String, rate: Float) {
        val engine = tts ?: return
        if (text.isBlank()) {
            interruptCurrent()
            notifyJs(JS_ON_DONE)
            return
        }
        val locale = runCatching { Locale.forLanguageTag(lang) }.getOrNull() ?: Locale.US
        val availability = engine.setLanguage(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            interruptCurrent()
            notifyJs(JS_ON_ERROR)
            return
        }
        interruptCurrent()
        engine.stop()
        // Some device engines ignore speech rate. Synthesize at normal speed and
        // apply the requested rate to playback, including the page's 0.1x option.
        engine.setSpeechRate(1f)
        playbackRate = if (rate.isFinite()) rate.coerceIn(0.1f, 3f) else 1f
        val id = "utterance-${++utteranceCounter}"
        currentUtteranceId = id
        try {
            val file = File.createTempFile("web-reading-", ".wav", cacheDir)
            audioFile = file
            if (engine.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                interruptCurrent()
                notifyJs(JS_ON_ERROR)
            }
        } catch (_: Exception) {
            interruptCurrent()
            notifyJs(JS_ON_ERROR)
        }
    }

    private fun interruptCurrent() {
        currentUtteranceId = null
        player?.release()
        player = null
        audioFile?.delete()
        audioFile = null
    }

    private fun onUtteranceFinished(utteranceId: String?, error: Boolean) {
        mainHandler.post {
            if (shutdown || utteranceId == null || utteranceId != currentUtteranceId) return@post
            if (error) {
                interruptCurrent()
                notifyJs(JS_ON_ERROR)
                return@post
            }
            try {
                val audio = MediaPlayer()
                player = audio
                audio.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                audio.setDataSource(requireNotNull(audioFile).absolutePath)
                audio.setOnPreparedListener {
                    if (player !== audio || utteranceId != currentUtteranceId) return@setOnPreparedListener
                    try {
                        audio.playbackParams = PlaybackParams().setSpeed(playbackRate).setPitch(1f)
                        audio.start()
                        notifyJs(JS_ON_START)
                    } catch (_: Exception) {
                        interruptCurrent()
                        notifyJs(JS_ON_ERROR)
                    }
                }
                audio.setOnCompletionListener {
                    if (player === audio && utteranceId == currentUtteranceId) {
                        interruptCurrent()
                        notifyJs(JS_ON_DONE)
                    }
                }
                audio.setOnErrorListener { _, _, _ ->
                    if (player === audio && utteranceId == currentUtteranceId) {
                        interruptCurrent()
                        notifyJs(JS_ON_ERROR)
                    }
                    true
                }
                audio.prepareAsync()
            } catch (_: Exception) {
                interruptCurrent()
                notifyJs(JS_ON_ERROR)
            }
        }
    }

    private fun notifyJs(callbackName: String) {
        val view = webView ?: return
        view.evaluateJavascript("window.$callbackName && window.$callbackName()", null)
    }

    companion object {
        private const val JS_ON_START = "__androidTtsOnStart"
        private const val JS_ON_DONE = "__androidTtsOnDone"
        private const val JS_ON_ERROR = "__androidTtsOnError"

        /** Installs a window.speechSynthesis polyfill only when the engine lacks one. */
        const val POLYFILL_JS = """
(function () {
    if (typeof window.__androidTts === 'undefined') return;
    if (window.speechSynthesis && window.SpeechSynthesisUtterance) return;

    var VOICES = [
        { voiceURI: 'android-tts-zh', name: 'Android TTS Chinese', lang: 'zh-CN', localService: true, default: false },
        { voiceURI: 'android-tts-en', name: 'Android TTS English', lang: 'en-US', localService: true, default: true }
    ];

    var current = null;

    function fire(utterance, handlerName, event) {
        if (!utterance) return;
        var handler = utterance[handlerName];
        if (typeof handler === 'function') {
            try { handler.call(utterance, event || {}); } catch (ignored) {}
        }
    }

    function utteranceLang(utterance) {
        return (utterance.voice && utterance.voice.lang) || utterance.lang || 'en-US';
    }

    function speakCurrent() {
        window.__androidTts.speak(String(current.text || ''), String(utteranceLang(current)), Number(current.rate) || 1);
    }

    var api = {
        speaking: false,
        paused: false,
        pending: false,
        onvoiceschanged: null,
        getVoices: function () { return VOICES.slice(); },
        speak: function (utterance) {
            if (!utterance) return;
            api.cancel();
            current = utterance;
            api.speaking = true;
            api.paused = false;
            speakCurrent();
        },
        cancel: function () {
            var utterance = current;
            current = null;
            api.speaking = false;
            api.paused = false;
            window.__androidTts.cancel();
            fire(utterance, 'onerror', { error: 'canceled' });
        },
        pause: function () {
            if (!api.speaking || api.paused) return;
            api.paused = true;
            window.__androidTts.cancel();
        },
        resume: function () {
            if (!api.paused || !current) return;
            api.paused = false;
            speakCurrent();
        }
    };

    window.__androidTtsOnStart = function () {
        fire(current, 'onstart');
    };
    window.__androidTtsOnDone = function () {
        var utterance = current;
        current = null;
        api.speaking = false;
        api.paused = false;
        fire(utterance, 'onend');
    };
    window.__androidTtsOnError = function () {
        var utterance = current;
        current = null;
        api.speaking = false;
        api.paused = false;
        fire(utterance, 'onerror', { error: 'synthesis-failed' });
    };

    window.SpeechSynthesisUtterance = function (text) {
        this.text = text || '';
        this.lang = '';
        this.voice = null;
        this.rate = 1;
        this.pitch = 1;
        this.volume = 1;
        this.onstart = null;
        this.onend = null;
        this.onerror = null;
        this.onpause = null;
        this.onresume = null;
    };

    try {
        window.speechSynthesis = api;
    } catch (e) {
        Object.defineProperty(window, 'speechSynthesis', { value: api });
    }
})();
"""
    }
}
