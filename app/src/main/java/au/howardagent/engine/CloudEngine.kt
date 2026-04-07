package au.howardagent.engine

import android.util.Log
import au.howardagent.data.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

enum class CloudProvider(
    val baseUrl: String,
    val defaultModel: String,
    val authHeader: String
) {
    OPENAI("https://api.openai.com/v1", "gpt-4o", "Authorization"),
    ANTHROPIC("https://api.anthropic.com/v1", "claude-sonnet-4-6", "x-api-key"),
    GEMINI("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", "Authorization"),
    OPENROUTER("https://openrouter.ai/api/v1", "meta-llama/llama-3.1-8b-instruct:free", "Authorization"),
    OLLAMA("", "llama3.1", ""); // Base URL configured by user

    fun authValue(key: String): String = when (this) {
        ANTHROPIC -> key
        else -> "Bearer $key"
    }
}

class CloudEngine(
    private val provider: CloudProvider,
    private val prefs: SecurePrefs
) : InferenceEngine {

    companion object {
        private const val TAG = "CloudEngine"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentCall: Call? = null

    override suspend fun infer(
        prompt: String,
        systemPrompt: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val baseUrl = if (provider == CloudProvider.OLLAMA) {
            prefs.ollamaBaseUrl.trimEnd('/')
        } else {
            provider.baseUrl
        }

        val apiKey = prefs.getApiKey(provider.name.lowercase())

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", prompt))
        }

        val body = JSONObject()
            .put("model", provider.defaultModel)
            .put("messages", messages)
            .put("stream", true)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val reqBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body)

        if (provider.authHeader.isNotEmpty() && apiKey.isNotEmpty()) {
            reqBuilder.addHeader(provider.authHeader, provider.authValue(apiKey))
        }

        if (provider == CloudProvider.ANTHROPIC) {
            reqBuilder.addHeader("anthropic-version", "2023-06-01")
        }

        val request = reqBuilder.build()
        currentCall = client.newCall(request)

        try {
            currentCall!!.execute().use { response ->
                if (!response.isSuccessful) {
                    onError("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                    return@withContext
                }

                val reader: BufferedReader = response.body!!.source().inputStream().bufferedReader()
                reader.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") return@forEachLine

                        try {
                            val json = JSONObject(data)
                            val delta = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .optJSONObject("delta")
                            val content = delta?.optString("content", "") ?: ""
                            if (content.isNotEmpty()) {
                                onToken(content)
                            }
                        } catch (_: Exception) { }
                    }
                }
                onComplete()
            }
        } catch (e: Exception) {
            if (e.message?.contains("Canceled") != true) {
                Log.e(TAG, "Cloud inference error: ${e.message}")
                onError(e.message ?: "Unknown error")
            }
        }
    }

    override fun stop() {
        currentCall?.cancel()
    }

    override fun release() {
        currentCall?.cancel()
    }
}
