package io.openclaw.aria

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.openclaw.aria.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenClawClient(
    private val baseUrl: String,
    private val token: String,
    context: Context
) {
    interface StreamListener {
        fun onStart()
        fun onTextDelta(delta: String, fullText: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
    }

    // Device identity for OpenClaw auth
    private data class DeviceIdentity(
        val deviceId: String,
        val privateKey: EdDSAPrivateKey,
        val publicKeyRaw: ByteArray
    )

    private val deviceIdentity: DeviceIdentity = loadOrCreateDeviceIdentity(context)
    @Volatile private var challengeNonce: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // no timeout for WS
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val connectMutex = Mutex()
    private var connectResult: CompletableDeferred<Boolean>? = null
    private val listenerLock = Any()
    @Volatile private var currentListener: StreamListener? = null
    @Volatile private var currentRequestId: String? = null
    private val currentText = StringBuilder()
    private var receivedFirstChunk = AtomicBoolean(false)

    // Timeout handler — fires if no streaming data arrives
    private val timeoutHandler = Handler(Looper.getMainLooper())
    @Volatile private var timeoutRunnable: Runnable? = null

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WebSocket opened, waiting for challenge...")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WS message: ${text.take(2000)}")
            try {
                handleMessage(JSONObject(text))
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Parse error", e)
                fireError("Parse error: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (BuildConfig.DEBUG) Log.e(TAG, "WebSocket failure: ${t.message}", t)
            connected.set(false)
            webSocket = null
            connectResult?.complete(false)
            fireError("WebSocket error: ${t.message}")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WebSocket closed: $code $reason")
            connected.set(false)
            webSocket = null
        }
    }

    /** Thread-safe error dispatch: cancels timeout and notifies listener exactly once */
    private fun fireError(error: String) {
        cancelTimeout()
        val listener: StreamListener?
        synchronized(listenerLock) {
            listener = currentListener
            currentListener = null
            currentRequestId = null
        }
        listener?.onError(error)
    }

    /** Thread-safe completion dispatch: notifies listener exactly once */
    private fun fireComplete(text: String) {
        cancelTimeout()
        val listener: StreamListener?
        synchronized(listenerLock) {
            listener = currentListener
            currentListener = null
            currentRequestId = null
        }
        listener?.onComplete(text)
    }

    private fun startResponseTimeout() {
        cancelTimeout()
        receivedFirstChunk.set(false)
        val runnable = Runnable {
            if (currentListener != null) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Response timeout — no data received")
                fireError("Timeout: server not responding")
            }
        }
        timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, RESPONSE_TIMEOUT_MS)
    }

    private fun resetStreamIdleTimeout() {
        cancelTimeout()
        val runnable = Runnable {
            if (currentListener != null) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Stream idle timeout — no data for ${STREAM_IDLE_TIMEOUT_MS}ms")
                fireError("Timeout: response was interrupted")
            }
        }
        timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, STREAM_IDLE_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun handleMessage(msg: JSONObject) {
        val type = msg.getString("type")

        when (type) {
            "event" -> handleEvent(msg)
            "res" -> handleResponse(msg)
        }
    }

    private fun handleEvent(msg: JSONObject) {
        val event = msg.optString("event", "")
        val payload = msg.optJSONObject("payload")

        when (event) {
            "connect.challenge" -> {
                challengeNonce = payload?.optString("nonce", "") ?: ""
                sendConnect()
            }
            "agent" -> {
                val stream = payload?.optString("stream", "") ?: ""
                val data = payload?.optJSONObject("data")

                when (stream) {
                    "lifecycle" -> {
                        val phase = data?.optString("phase", "") ?: ""
                        if (phase == "start") {
                            currentListener?.onStart()
                        } else if (phase == "end") {
                            val text: String
                            synchronized(listenerLock) {
                                text = currentText.toString()
                            }
                            fireComplete(text)
                        } else if (phase == "error") {
                            val errorMsg = data?.optString("error", "Unknown error") ?: "Unknown error"
                            fireError(errorMsg)
                        }
                    }
                    "error" -> {
                        val reason = data?.optString("reason", "Unknown error") ?: "Unknown error"
                        if (BuildConfig.DEBUG) Log.w(TAG, "Stream error: $reason")
                        fireError(reason)
                    }
                    "assistant" -> {
                        val delta = data?.optString("delta", "") ?: ""
                        val fullText = data?.optString("text", "") ?: ""
                        if (delta.isNotEmpty()) {
                            receivedFirstChunk.set(true)
                            resetStreamIdleTimeout()
                            synchronized(listenerLock) {
                                currentText.clear()
                                currentText.append(fullText)
                            }
                            currentListener?.onTextDelta(delta, fullText)
                        }
                    }
                }
            }
            "chat" -> {
                val state = payload?.optString("state", "") ?: ""
                val message = payload?.optJSONObject("message")
                val role = message?.optString("role", "") ?: ""

                if (role == "assistant") {
                    val content = extractTextContent(message)
                    when (state) {
                        "delta" -> {
                            if (content.isNotEmpty()) {
                                receivedFirstChunk.set(true)
                                resetStreamIdleTimeout()
                                synchronized(listenerLock) {
                                    currentText.clear()
                                    currentText.append(content)
                                }
                                currentListener?.onTextDelta("", content)
                            }
                        }
                        "final" -> {
                            synchronized(listenerLock) {
                                if (content.isNotEmpty()) {
                                    currentText.clear()
                                    currentText.append(content)
                                }
                            }
                            val text: String
                            synchronized(listenerLock) {
                                text = currentText.toString()
                            }
                            fireComplete(text)
                        }
                    }
                }
            }
        }
    }

    private fun extractTextContent(message: JSONObject?): String {
        if (message == null) return ""
        return try {
            val arr = message.optJSONArray("content")
            if (arr != null) {
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val block = arr.getJSONObject(i)
                    if (block.optString("type") == "text") {
                        sb.append(block.optString("text", ""))
                    }
                }
                sb.toString()
            } else {
                message.optString("content", "")
            }
        } catch (e: Exception) {
            message.optString("content", "")
        }
    }

    private fun handleResponse(msg: JSONObject) {
        val id = msg.optString("id", "")
        val ok = msg.optBoolean("ok", false)

        if (id == "connect") {
            if (ok) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Connect handshake OK")
                connected.set(true)
                connectResult?.complete(true)
            } else {
                val error = msg.optJSONObject("error")?.optString("message", "Unknown error")
                if (BuildConfig.DEBUG) Log.e(TAG, "Connect handshake failed: $error")
                connectResult?.complete(false)
            }
        } else if ((id.startsWith("agent-") || id.startsWith("chat-")) && !ok) {
            val error = msg.optJSONObject("error")?.optString("message", "Unknown error") ?: "Unknown error"
            if (BuildConfig.DEBUG) Log.e(TAG, "Agent request failed: $error")
            fireError("Request denied: $error")
        }
    }

    private fun sendConnect() {
        val nonce = challengeNonce ?: ""
        val signedAtMs = System.currentTimeMillis()
        val role = "operator"
        val scopes = listOf("operator.read", "operator.write")
        val clientId = "openclaw-android"
        val clientMode = "cli"
        val platform = "android"

        // Build v3 signature payload
        val payload = listOf(
            "v3",
            deviceIdentity.deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAtMs.toString(),
            token,
            nonce,
            platform,
            "" // deviceFamily
        ).joinToString("|")

        // Sign with Ed25519
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(deviceIdentity.privateKey)
        engine.update(payload.toByteArray(Charsets.UTF_8))
        val signature = base64UrlEncode(engine.sign())

        val client = JSONObject().apply {
            put("id", clientId)
            put("version", "1.0.0")
            put("platform", platform)
            put("mode", clientMode)
        }

        val device = JSONObject().apply {
            put("id", deviceIdentity.deviceId)
            put("publicKey", base64UrlEncode(deviceIdentity.publicKeyRaw))
            put("signature", signature)
            put("signedAt", signedAtMs)
            put("nonce", nonce)
        }

        val auth = JSONObject().apply {
            put("token", token)
        }

        val params = JSONObject().apply {
            put("minProtocol", 3)
            put("maxProtocol", 3)
            put("client", client)
            put("role", role)
            put("scopes", JSONArray().apply {
                scopes.forEach { put(it) }
            })
            put("device", device)
            put("auth", auth)
            put("locale", "en-US")
            put("userAgent", "aria-android/1.0.0")
        }

        val frame = JSONObject().apply {
            put("type", "req")
            put("id", "connect")
            put("method", "connect")
            put("params", params)
        }

        webSocket?.send(frame.toString())
    }

    companion object {
        private const val TAG = "OpenClawClient"
        private const val RESPONSE_TIMEOUT_MS = 90_000L
        private const val STREAM_IDLE_TIMEOUT_MS = 30_000L

        private fun base64UrlEncode(data: ByteArray): String {
            return android.util.Base64.encodeToString(data, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        }

        private fun loadOrCreateDeviceIdentity(context: Context): DeviceIdentity {
            val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val file = File(context.filesDir, "device_identity.json")

            if (file.exists()) {
                try {
                    val json = JSONObject(file.readText())
                    val seedHex = json.getString("seed")
                    val seed = seedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val privKeySpec = EdDSAPrivateKeySpec(seed, spec)
                    val privKey = EdDSAPrivateKey(privKeySpec)
                    val pubKeySpec = EdDSAPublicKeySpec(privKeySpec.a, spec)
                    val pubKey = EdDSAPublicKey(pubKeySpec)
                    val pubKeyRaw = pubKey.abyte
                    val deviceId = sha256Hex(pubKeyRaw)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Loaded device identity: $deviceId")
                    return DeviceIdentity(deviceId, privKey, pubKeyRaw)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Failed to load device identity, regenerating", e)
                }
            }

            // Generate new keypair
            val kpg = KeyPairGenerator()
            val keyPair = kpg.generateKeyPair()
            val privKey = keyPair.private as EdDSAPrivateKey
            val pubKey = keyPair.public as EdDSAPublicKey
            val pubKeyRaw = pubKey.abyte
            val deviceId = sha256Hex(pubKeyRaw)
            val seedHex = privKey.seed.joinToString("") { "%02x".format(it) }

            val json = JSONObject().apply {
                put("version", 1)
                put("deviceId", deviceId)
                put("seed", seedHex)
            }
            file.writeText(json.toString())
            if (BuildConfig.DEBUG) Log.d(TAG, "Generated new device identity: $deviceId")
            return DeviceIdentity(deviceId, privKey, pubKeyRaw)
        }

        private fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun ensureConnected() {
        if (connected.get() && webSocket != null) return

        connectMutex.withLock {
            // Re-check after acquiring lock
            if (connected.get() && webSocket != null) return

            withContext(Dispatchers.IO) {
                val deferred = CompletableDeferred<Boolean>()
                connectResult = deferred

                val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
                if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to $wsUrl ...")
                val request = Request.Builder().url(wsUrl).build()
                webSocket = httpClient.newWebSocket(request, wsListener)

                val result = deferred.await()
                if (!result) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Connection failed!")
                    throw Exception("Failed to connect to OpenClaw gateway")
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "Connected successfully!")
            }
        }
    }

    suspend fun chat(message: String, sessionKey: String, listener: StreamListener, media: MediaAttachment? = null) {
        ensureConnected()

        val ws = webSocket
        if (ws == null) {
            listener.onError("WebSocket disconnected")
            return
        }

        // Cancel any previous pending request
        cancelTimeout()
        synchronized(listenerLock) {
            currentText.clear()
            currentListener = listener
        }

        // Start response timeout
        startResponseTimeout()

        val requestId = if (media != null) "chat-${UUID.randomUUID()}" else "agent-${UUID.randomUUID()}"
        synchronized(listenerLock) {
            currentRequestId = requestId
        }

        val frame: String
        if (media != null) {
            val ext = when {
                media.mimeType.contains("png") -> "png"
                media.mimeType.contains("mp4") -> "mp4"
                media.mimeType.contains("webm") -> "webm"
                else -> "jpg"
            }
            val params = JSONObject().apply {
                put("sessionKey", sessionKey)
                put("message", message)
                put("idempotencyKey", "aria-${UUID.randomUUID()}")
                put("attachments", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "${media.type}.$ext")
                        put("mimeType", media.mimeType)
                        put("content", media.base64)
                        put("encoding", "base64")
                    })
                })
            }
            frame = JSONObject().apply {
                put("type", "req")
                put("id", requestId)
                put("method", "chat.send")
                put("params", params)
            }.toString()
            if (BuildConfig.DEBUG) Log.d(TAG, "Sending chat.send with attachment: ${media.type} ${media.mimeType}")
        } else {
            val params = JSONObject().apply {
                put("message", message)
                put("sessionKey", sessionKey)
                put("idempotencyKey", "aria-${UUID.randomUUID()}")
            }
            frame = JSONObject().apply {
                put("type", "req")
                put("id", requestId)
                put("method", "agent")
                put("params", params)
            }.toString()
        }

        val sent = ws.send(frame)
        if (!sent) {
            fireError("Unable to send message (buffer full)")
        }
    }

    fun disconnect() {
        cancelTimeout()
        webSocket?.close(1000, "App closing")
        webSocket = null
        connected.set(false)
        synchronized(listenerLock) {
            currentListener = null
            currentRequestId = null
        }
    }

    fun isConnected(): Boolean = connected.get()
}
