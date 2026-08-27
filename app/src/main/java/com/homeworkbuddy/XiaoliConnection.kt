package com.homeworkbuddy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArraySet

private const val XIAOLI_CHANNEL = "xiaoli_connection"
private const val XIAOLI_NOTIFICATION_ID = 7101

data class XiaoliConnectionConfig(val deviceId: String, val deviceName: String, val websocketUrl: String, val token: String)

object XiaoliDeviceStore {
    private const val PREFS = "xiaoli_device"
    private const val DEVICE_ID = "device_id"
    private const val DEVICE_NAME = "device_name"
    private const val WS_URL = "websocket_url"
    private const val TOKEN = "token"

    fun defaultDeviceId(context: Context): String = "homework-tablet-" + android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).takeLast(8)

    fun config(context: Context): XiaoliConnectionConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(WS_URL, null) ?: return null
        val token = prefs.getString(TOKEN, null)?.let(DeviceTokenCipher::decrypt) ?: return null
        return XiaoliConnectionConfig(
            prefs.getString(DEVICE_ID, defaultDeviceId(context)) ?: defaultDeviceId(context),
            prefs.getString(DEVICE_NAME, "学习平板") ?: "学习平板",
            url,
            token,
        )
    }

    fun save(context: Context, config: XiaoliConnectionConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DEVICE_ID, config.deviceId)
            .putString(DEVICE_NAME, config.deviceName)
            .putString(WS_URL, config.websocketUrl)
            .putString(TOKEN, DeviceTokenCipher.encrypt(config.token))
            .apply()
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}

/** Runtime state is deliberately separate from the Trello "connected" state. */
data class XiaoliConnectionSnapshot(val status: String, val lastError: String?)

object XiaoliConnectionState {
    @Volatile var status: String = "未配置"
        private set
    @Volatile var lastConnectedAt: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set

    private val listeners = CopyOnWriteArraySet<(XiaoliConnectionSnapshot) -> Unit>()

    fun snapshot() = XiaoliConnectionSnapshot(status, lastError)

    fun addChangeListener(listener: (XiaoliConnectionSnapshot) -> Unit) {
        listeners += listener
    }

    fun removeChangeListener(listener: (XiaoliConnectionSnapshot) -> Unit) {
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

/** Thread-safe bridge for device-originated MCP notifications such as video frames. */
object XiaoliMcpNotifications {
    @Volatile private var sender: ((String, JSONObject) -> Boolean)? = null

    fun register(value: (String, JSONObject) -> Boolean) { sender = value }
    fun clear() { sender = null }
    fun publish(method: String, params: JSONObject): Boolean = sender?.invoke(method, params) ?: false
}

/**
 * QR format is server-owned and intentionally small. The QR contains a short lived pair URL and code:
 * {"pair_url":"https://gateway.example/xiaozhi/pair","code":"one-time-code"}.
 * The endpoint returns {"device":{"id":"...","name":"..."},"websocket":{"url":"wss://...","token":"..."}}.
 */
object XiaoliPairingClient {
    fun pair(context: Context, rawQr: String, requestedName: String): XiaoliConnectionConfig {
        val qr = JSONObject(rawQr)
        val pairUrl = qr.getString("pair_url")
        val body = JSONObject()
            .put("code", qr.getString("code"))
            .put("device_id", XiaoliDeviceStore.defaultDeviceId(context))
            .put("device_name", requestedName)
            .put("device_kind", "android")
        val connection = (URL(pairUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
        val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrDefault("配对失败"))
        val result = JSONObject(text)
        val device = result.optJSONObject("device")
        val websocket = result.getJSONObject("websocket")
        return XiaoliConnectionConfig(
            device?.optString("id").orEmpty().ifBlank { XiaoliDeviceStore.defaultDeviceId(context) },
            device?.optString("name").orEmpty().ifBlank { requestedName },
            websocket.getString("url"),
            websocket.getString("token"),
        )
    }
}

class XiaoliConnectionService : Service() {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadExecutor()
    private val reconnectWorker = Executors.newSingleThreadScheduledExecutor()
    private var socket: WebSocket? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var sessionId: String? = null
    private var reconnectAttempts = 0
    private var stopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopped = true
            socket?.close(1000, "user disconnected")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(XIAOLI_NOTIFICATION_ID, notification("正在连接小李…"))
        stopped = false
        if (socket == null) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        XiaoliMcpNotifications.clear()
        reconnectFuture?.cancel(true)
        socket?.cancel()
        worker.shutdownNow()
        reconnectWorker.shutdownNow()
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
        reconnectFuture?.cancel(true)
        socket?.close(1000, "foreground service time limit reached")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized private fun connect() {
        if (stopped || socket != null) return
        val config = XiaoliDeviceStore.config(this) ?: run {
            XiaoliConnectionState.disconnected("尚未绑定设备")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        XiaoliConnectionState.connecting()
        val request = Request.Builder()
            .url(config.websocketUrl)
            .header("Device-Id", config.deviceId)
            .header("Authorization", config.token)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket != null && socket !== webSocket) { webSocket.cancel(); return }
                socket = webSocket
                reconnectAttempts = 0
                reconnectFuture?.cancel(false)
                reconnectFuture = null
                XiaoliMcpNotifications.register(::publishMcpNotification)
                webSocket.send(JSONObject()
                    .put("type", "hello")
                    .put("version", 1)
                    .put("transport", "websocket")
                    .put("device_id", config.deviceId)
                    .put("features", JSONObject().put("mcp", true).put("audio", false))
                    .put("client_info", JSONObject().put("name", "homework-buddy-android").put("version", "0.1.0"))
                    .toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                socket = null
                XiaoliMcpNotifications.clear()
                XiaoliConnectionState.disconnected(t.message ?: "连接失败")
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                socket = null
                XiaoliMcpNotifications.clear()
                XiaoliConnectionState.disconnected(reason.ifBlank { "连接已关闭" })
                reconnect()
            }
        })
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") == "hello") {
            sessionId = message.optString("session_id").ifBlank { null }
            XiaoliConnectionState.connected()
            updateNotification("已连接小李")
            return
        }
        if (message.optString("type") != "mcp") return
        val payload = message.optJSONObject("payload") ?: return
        val method = payload.optString("method")
        val id = payload.opt("id") ?: return
        worker.execute {
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
                JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", JSONObject().put("code", -32000).put("message", error.message ?: "执行失败"))
            }
            sendMcp(webSocket, response)
        }
    }

    private fun sendMcp(webSocket: WebSocket, payload: JSONObject) {
        val envelope = JSONObject().put("type", "mcp").put("payload", payload)
        sessionId?.let { envelope.put("session_id", it) }
        webSocket.send(envelope.toString())
    }

    /** Sends a server-defined MCP notification while the device connection is active. */
    fun publishMcpNotification(method: String, params: JSONObject): Boolean {
        val webSocket = socket ?: return false
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        val envelope = JSONObject().put("type", "mcp").put("payload", payload)
        sessionId?.let { envelope.put("session_id", it) }
        return webSocket.send(envelope.toString())
    }

    @Synchronized private fun reconnect() {
        if (stopped) return
        val delay = (1_000L shl reconnectAttempts.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempts++
        reconnectFuture?.cancel(false)
        reconnectFuture = reconnectWorker.schedule({ if (!stopped) connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(XIAOLI_CHANNEL, "小李连接", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, XIAOLI_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("作业小伙伴").setContentText(text).setOngoing(true).build()
    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java).notify(XIAOLI_NOTIFICATION_ID, notification(text))

    companion object {
        private const val ACTION_STOP = "com.homeworkbuddy.xiaoli.STOP"
        fun connect(context: Context) = ContextCompat.startForegroundService(context, Intent(context, XiaoliConnectionService::class.java))
        fun disconnect(context: Context) = context.startService(Intent(context, XiaoliConnectionService::class.java).setAction(ACTION_STOP))
    }
}
