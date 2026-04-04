package au.howardagent.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import au.howardagent.HowardApplication
import au.howardagent.R
import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GatewayService : Service() {

    companion object {
        const val TAG        = "GatewayService"
        const val CHANNEL_ID = "howard_gateway"
        const val NOTIF_ID   = 1001
        const val GATEWAY_PORT = 18789
    }

    private var server: HowardGatewayServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting OpenClaw gateway..."))

        try {
            server = HowardGatewayServer(GATEWAY_PORT)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Gateway server started on port $GATEWAY_PORT")
            updateNotification("Howard online — port $GATEWAY_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start gateway: ${e.message}", e)
            updateNotification("Gateway error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Howard Gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Howard")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_howard_notif)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}

/**
 * In-process HTTP server implementing OpenAI-compatible /v1/chat/completions,
 * /health, and /api/message endpoints. Replaces the Node.js + OpenClaw gateway.
 */
private class HowardGatewayServer(port: Int) : NanoHTTPD(port) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/health" -> {
                newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
            }

            uri == "/api/message" && method == Method.POST -> {
                handleMessage(session)
            }

            uri == "/v1/chat/completions" && method == Method.POST -> {
                handleChatCompletions(session)
            }

            uri == "/v1/models" && method == Method.GET -> {
                handleListModels()
            }

            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    """{"error":"Not found","path":"$uri"}""")
            }
        }
    }

    private fun handleMessage(session: IHTTPSession): Response {
        val body = readBody(session)
        val json = JSONObject(body)
        val message = json.optString("message", "")

        if (message.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                """{"error":"Missing 'message' field"}""")
        }

        // Convert simple message to chat completions format and forward
        val chatBody = JSONObject().apply {
            put("model", "default")
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", message)
            }))
        }

        val result = forwardToProvider(chatBody)
        return newFixedLengthResponse(Response.Status.OK, "application/json", result)
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        val body = readBody(session)
        val json = JSONObject(body)
        val result = forwardToProvider(json)
        return newFixedLengthResponse(Response.Status.OK, "application/json", result)
    }

    private fun handleListModels(): Response {
        val prefs = HowardApplication.instance.securePrefs
        val models = JSONArray()

        if (prefs.openaiKey.isNotBlank()) models.put(modelObj("openai/gpt-4o"))
        if (prefs.anthropicKey.isNotBlank()) models.put(modelObj("anthropic/claude-sonnet-4-20250514"))
        if (prefs.geminiKey.isNotBlank()) models.put(modelObj("gemini/gemini-2.0-flash"))
        if (prefs.openrouterKey.isNotBlank()) models.put(modelObj("openrouter/auto"))
        models.put(modelObj("local/default"))

        val response = JSONObject().apply {
            put("object", "list")
            put("data", models)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    private fun modelObj(id: String): JSONObject = JSONObject().apply {
        put("id", id)
        put("object", "model")
        put("owned_by", id.substringBefore("/"))
    }

    /**
     * Forward a chat completions request to the active cloud provider.
     * Falls through providers in priority order: OpenAI > Anthropic > Gemini > OpenRouter.
     */
    private fun forwardToProvider(chatBody: JSONObject): String {
        val prefs = HowardApplication.instance.securePrefs

        // Try providers in order
        return when {
            prefs.openaiKey.isNotBlank() -> forwardOpenAI(chatBody, prefs.openaiKey)
            prefs.anthropicKey.isNotBlank() -> forwardAnthropic(chatBody, prefs.anthropicKey)
            prefs.geminiKey.isNotBlank() -> forwardGemini(chatBody, prefs.geminiKey)
            prefs.openrouterKey.isNotBlank() -> forwardOpenRouter(chatBody, prefs.openrouterKey)
            else -> JSONObject().apply {
                put("error", JSONObject().apply {
                    put("message", "No API keys configured. Add keys in Settings.")
                    put("type", "configuration_error")
                })
            }.toString()
        }
    }

    private fun forwardOpenAI(chatBody: JSONObject, apiKey: String): String {
        if (!chatBody.has("model") || chatBody.getString("model") == "default") {
            chatBody.put("model", "gpt-4o")
        }
        return proxyPost(
            url = "https://api.openai.com/v1/chat/completions",
            body = chatBody.toString(),
            headers = mapOf("Authorization" to "Bearer $apiKey")
        )
    }

    private fun forwardAnthropic(chatBody: JSONObject, apiKey: String): String {
        // Convert OpenAI format to Anthropic format
        val messages = chatBody.getJSONArray("messages")
        val anthropicBody = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", chatBody.optInt("max_tokens", 4096))
            put("messages", messages)
        }
        return proxyPost(
            url = "https://api.anthropic.com/v1/messages",
            body = anthropicBody.toString(),
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
    }

    private fun forwardGemini(chatBody: JSONObject, apiKey: String): String {
        // Convert to Gemini format
        val messages = chatBody.getJSONArray("messages")
        val contents = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = if (msg.getString("role") == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg.getString("content"))))
            })
        }
        val geminiBody = JSONObject().put("contents", contents)
        return proxyPost(
            url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey",
            body = geminiBody.toString(),
            headers = emptyMap()
        )
    }

    private fun forwardOpenRouter(chatBody: JSONObject, apiKey: String): String {
        if (!chatBody.has("model") || chatBody.getString("model") == "default") {
            chatBody.put("model", "openai/gpt-4o")
        }
        return proxyPost(
            url = "https://openrouter.ai/api/v1/chat/completions",
            body = chatBody.toString(),
            headers = mapOf("Authorization" to "Bearer $apiKey")
        )
    }

    private fun proxyPost(url: String, body: String, headers: Map<String, String>): String {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val reqBuilder = Request.Builder().url(url).post(reqBody)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        reqBuilder.addHeader("User-Agent", "Howard-Agent/1.0")

        return try {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                resp.body?.string() ?: """{"error":"Empty response from provider"}"""
            }
        } catch (e: Exception) {
            Log.e("GatewayServer", "Proxy error: ${e.message}", e)
            JSONObject().apply {
                put("error", JSONObject().apply {
                    put("message", "Provider request failed: ${e.message}")
                    put("type", "proxy_error")
                })
            }.toString()
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(contentLength)
        session.inputStream.read(buf, 0, contentLength)
        return String(buf)
    }
}
