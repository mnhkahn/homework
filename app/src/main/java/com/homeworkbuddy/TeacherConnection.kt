package com.homeworkbuddy

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object TeacherDeviceStore {
    private const val PREFS = "teacher_gateway_v1"
    private const val DEVICE_ID = "device_id"
    private const val DEVICE_NAME = "device_name"
    private const val WS_URL = "websocket_url"
    private const val TOKEN = "token"
    fun defaultDeviceId(context: Context): String = "homework-tablet-" + android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).takeLast(8)

    fun config(context: Context): TeacherConnectionConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(WS_URL, null) ?: return null
        if (!TeacherPairing.isConnectionUrl(url)) return null
        val token = prefs.getString(TOKEN, null)?.let(TeacherTokenCipher::decrypt)?.takeIf { it.isNotBlank() } ?: return null
        return TeacherConnectionConfig(
            prefs.getString(DEVICE_ID, defaultDeviceId(context)) ?: defaultDeviceId(context),
            prefs.getString(DEVICE_NAME, "学习平板") ?: "学习平板",
            url,
            token,
        )
    }

    @Synchronized fun save(context: Context, config: TeacherConnectionConfig) {
        require(TeacherPairing.isConnectionUrl(config.websocketUrl) && config.token.isNotBlank())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor
            .putString(DEVICE_ID, config.deviceId)
            .putString(DEVICE_NAME, config.deviceName)
            .putString(WS_URL, config.websocketUrl)
            .putString(TOKEN, TeacherTokenCipher.encrypt(config.token))
            .apply()
    }

    @Synchronized fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(DEVICE_ID)
            .remove(DEVICE_NAME)
            .remove(WS_URL)
            .remove(TOKEN)
            .apply()
    }

}

/** Runtime state is deliberately separate from the Trello "connected" state. */
data class TeacherConnectionSnapshot(val status: String, val lastError: String?)

object TeacherConnectionState {
    @Volatile var status: String = "未配对"
        private set
    @Volatile var lastConnectedAt: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set

    private val listeners = CopyOnWriteArraySet<(TeacherConnectionSnapshot) -> Unit>()

    fun snapshot() = TeacherConnectionSnapshot(status, lastError)

    fun addChangeListener(listener: (TeacherConnectionSnapshot) -> Unit) {
        listeners += listener
    }

    fun removeChangeListener(listener: (TeacherConnectionSnapshot) -> Unit) {
        listeners -= listener
    }

    fun connecting() { status = "正在连接"; lastError = null; notifyChanged() }
    fun connected() { status = "已连接"; lastConnectedAt = System.currentTimeMillis(); lastError = null; notifyChanged() }
    fun disconnected(error: String?) { status = "未连接"; lastError = error; notifyChanged() }

    private fun notifyChanged() {
        val snapshot = snapshot()
        listeners.forEach { it(snapshot) }
    }
}

object TeacherPairingClient {
    fun pair(context: Context, rawQr: String, requestedName: String): TeacherConnectionConfig {
        val pairing = TeacherPairing.request(rawQr)
        val body = JSONObject()
            .put("code", pairing.code)
            .put("device_id", TeacherDeviceStore.defaultDeviceId(context))
            .put("device_name", requestedName)
            .put("device_kind", "android")
        val connection = (URL(pairing.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val reason = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw IllegalStateException(reason.ifBlank { "配对失败，请重新生成二维码后再试" })
            }
            return TeacherPairing.response(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }
}

class TeacherConnectionService : Service() {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var worker = Executors.newSingleThreadExecutor()
    private val reconnectWorker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: WebSocket? = null
    private var connectionGeneration = 0L
    @Volatile private var connectionConfig: TeacherConnectionConfig? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    @Volatile private var sessionId: String? = null
    private var reconnectAttempts = 0
    @Volatile private var stopped = false
    private var cameraCaptureDepth = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopped = true
                resetConnection()
                TeacherConnectionState.disconnected("已解除绑定")
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAMERA_CAPTURE_BEGIN -> {
                cameraCaptureDepth++
                refreshForeground()
                return START_STICKY
            }
            ACTION_CAMERA_CAPTURE_END -> {
                cameraCaptureDepth = (cameraCaptureDepth - 1).coerceAtLeast(0)
                refreshForeground()
                return START_STICKY
            }
        }
        stopped = false
        if (intent?.action == ACTION_RECONNECT || (socket != null && connectionConfig != TeacherDeviceStore.config(this))) {
            resetConnection()
            reconnectAttempts = 0
        }
        refreshForeground()
        if (socket == null) connect()
        return START_STICKY
    }

    private fun refreshForeground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        // Opening the camera while the device is locked requires a camera-type
        // foreground service (Android 12+); carry the type only while a remote
        // capture runs so boot-time starts stay a plain remoteMessaging service.
        if (cameraCaptureDepth > 0 && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        runCatching {
            ServiceCompat.startForeground(this, HomeworkReminderReceiver.NOTIFICATION_ID, HomeworkReminderReceiver.foregroundNotification(this), types)
        }.onFailure { Log.w("TeacherConnection", "startForeground(types=$types) failed", it) }
    }

    override fun onDestroy() {
        stopped = true
        resetConnection(recreateWorker = false)
        TeacherConnectionState.disconnected("李老师连接已停止")
        reconnectWorker.shutdownNow()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    // Android 15 limits dataSync foreground services to a fixed runtime.  If
    // this callback is ignored Android kills the whole app a few seconds later,
    // which looks like a random flash/crash when returning from another app.
    override fun onTimeout(startId: Int) {
        stopConnectionAfterTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopConnectionAfterTimeout()
    }

    private fun stopConnectionAfterTimeout() {
        stopped = true
        resetConnection()
        TeacherConnectionState.disconnected("后台连接已停止，请重新打开应用")
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    /** Invalidate callbacks before cancelling the socket; old commands cannot move to the new binding. */
    @Synchronized private fun resetConnection(recreateWorker: Boolean = true) {
        connectionGeneration++
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val previous = socket
        socket = null
        sessionId = null
        connectionConfig = null
        worker.shutdownNow()
        if (recreateWorker) worker = Executors.newSingleThreadExecutor()
        previous?.cancel()
    }

    @Synchronized private fun isCurrent(webSocket: WebSocket, generation: Long): Boolean =
        !stopped && connectionGeneration == generation && socket === webSocket

    @Synchronized private fun connect() {
        if (stopped || socket != null) return
        val config = TeacherDeviceStore.config(this) ?: run {
            TeacherConnectionState.disconnected("尚未配对李老师")
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        TeacherConnectionState.connecting()
        val request = Request.Builder()
            .url(config.websocketUrl)
            .header("Device-Id", config.deviceId)
            .header("Authorization", "Bearer ${config.token}")
            .build()
        connectionConfig = config
        val generation = ++connectionGeneration
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response): Unit = synchronized(this@TeacherConnectionService) {
                if (!isCurrent(webSocket, generation)) { webSocket.cancel(); return@synchronized }
                socket = webSocket
                sessionId = null
                reconnectAttempts = 0
                reconnectFuture?.cancel(false)
                reconnectFuture = null
                webSocket.send(JSONObject()
                    .put("type", "hello").put("service", TeacherPairing.SERVICE)
                    .put("protocol", TeacherPairing.PROTOCOL).put("device_id", config.deviceId)
                    .toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@TeacherConnectionService) {
                if (isCurrent(webSocket, generation)) handleMessage(webSocket, config, text, generation)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@TeacherConnectionService) {
                if (!isCurrent(webSocket, generation)) return@synchronized
                resetConnection()
                TeacherConnectionState.disconnected(t.message ?: "连接失败")
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@TeacherConnectionService) {
                if (!isCurrent(webSocket, generation)) return@synchronized
                resetConnection()
                TeacherConnectionState.disconnected(reason.ifBlank { "连接已关闭" })
                reconnect()
            }
        })
    }

    private fun handleMessage(webSocket: WebSocket, config: TeacherConnectionConfig, text: String, generation: Long) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") == "hello") {
            val session = message.optString("session_id").takeIf { it.isNotBlank() }
            if (!TeacherPairing.isGateway(message) || session == null) {
                webSocket.close(1002, "李老师连接协议不兼容")
                return
            }
            sessionId = session
            TeacherConnectionState.connected()
            return
        }
        if (message.optString("type") != "mcp" || message.optString("session_id") != sessionId) return
        val payload = message.optJSONObject("payload") ?: return
        val method = payload.optString("method")
        val id = payload.opt("id") ?: return
        worker.execute {
            if (!isCurrent(webSocket, generation) || Thread.currentThread().isInterrupted) return@execute
            val response = try {
                val result = when (method) {
                    "initialize" -> JSONObject()
                        .put("protocolVersion", payload.optJSONObject("params")?.optString("protocolVersion", "2024-11-05"))
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "homework-buddy-android").put("version", "0.1.0"))
                    "tools/list" -> JSONObject().put("tools", McpTools.definitions())
                    "tools/call" -> McpTools.call(this, payload.optJSONObject("params") ?: JSONObject())
                    else -> throw IllegalArgumentException("不支持的 MCP 方法：$method")
                }
                JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
            } catch (error: Throwable) {
                val code = (error as? McpException)?.rpcCode ?: when {
                    error is kotlinx.coroutines.TimeoutCancellationException -> -32004
                    error.message?.contains("相机", ignoreCase = true) == true -> -32004
                    else -> -32000
                }
                JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", JSONObject().put("code", code).put("message", error.message ?: "执行失败"))
            }
            sendMcp(webSocket, response, generation)
        }
    }

    @Synchronized private fun sendMcp(webSocket: WebSocket, payload: JSONObject, generation: Long) {
        if (!isCurrent(webSocket, generation)) return
        val envelope = JSONObject().put("type", "mcp").put("payload", payload)
        sessionId?.let { envelope.put("session_id", it) }
        webSocket.send(envelope.toString())
    }

    @Synchronized private fun reconnect() {
        if (stopped) return
        val delay = (1_000L shl reconnectAttempts.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempts++
        reconnectFuture?.cancel(false)
        reconnectFuture = reconnectWorker.schedule({ if (!stopped) connect() }, delay, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val ACTION_RECONNECT = "com.homeworkbuddy.teacher.RECONNECT"
        private const val ACTION_STOP = "com.homeworkbuddy.teacher.STOP"
        private const val ACTION_CAMERA_CAPTURE_BEGIN = "com.homeworkbuddy.teacher.CAMERA_CAPTURE_BEGIN"
        private const val ACTION_CAMERA_CAPTURE_END = "com.homeworkbuddy.teacher.CAMERA_CAPTURE_END"
        fun connect(context: Context) = ContextCompat.startForegroundService(context, Intent(context, TeacherConnectionService::class.java))
        fun reconnect(context: Context) = ContextCompat.startForegroundService(context, Intent(context, TeacherConnectionService::class.java).setAction(ACTION_RECONNECT))
        fun disconnect(context: Context) = context.startService(Intent(context, TeacherConnectionService::class.java).setAction(ACTION_STOP))

        /** Adds/removes the camera foreground-service type while a remote capture runs. */
        fun beginCameraCapture(context: Context) {
            runCatching { context.startService(Intent(context, TeacherConnectionService::class.java).setAction(ACTION_CAMERA_CAPTURE_BEGIN)) }
        }
        fun endCameraCapture(context: Context) {
            runCatching { context.startService(Intent(context, TeacherConnectionService::class.java).setAction(ACTION_CAMERA_CAPTURE_END)) }
        }
    }
}
