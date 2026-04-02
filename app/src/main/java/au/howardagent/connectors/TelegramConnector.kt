package au.howardagent.connectors

import android.util.Log
import au.howardagent.data.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TelegramMessage(
    val updateId: Long,
    val chatId: Long,
    val text: String,
    val fromUsername: String
)

class TelegramConnector(private val prefs: SecurePrefs) {

    companion object {
        private const val TAG = "TelegramConnector"
        private const val BASE = "https://api.telegram.org/bot"
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    private val token     get() = prefs.telegramBotToken
    private val channelId get() = prefs.telegramChannelId

    suspend fun sendMessage(text: String, chatId: String = channelId): Unit =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext
            val body = JSONObject()
                .put("chat_id",    chatId)
                .put("text",       text)
                .put("parse_mode", "Markdown")
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("${BASE}${token}/sendMessage")
                .post(body)
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    Log.i(TAG, "sendMessage: ${resp.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed: ${e.message}")
                throw e
            }
        }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.failure(Exception("No bot token"))
        try {
            val req = Request.Builder().url("${BASE}${token}/getMe").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json     = JSONObject(resp.body!!.string())
                    val username = json.getJSONObject("result").getString("username")
                    Result.success("@$username")
                } else {
                    Result.failure(Exception("HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun pollUpdates(lastUpdateId: Long = 0L): Flow<TelegramMessage> = flow {
        var offset = lastUpdateId + 1
        while (currentCoroutineContext().isActive) {
            try {
                val url = "${BASE}${token}/getUpdates?offset=$offset&timeout=30"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        delay(5000)
                        return@use
                    }
                    val json    = JSONObject(resp.body!!.string())
                    val results = json.getJSONArray("result")
                    for (i in 0 until results.length()) {
                        val update  = results.getJSONObject(i)
                        val updateId = update.getLong("update_id")
                        offset = updateId + 1

                        if (update.has("message")) {
                            val msg      = update.getJSONObject("message")
                            val chatId   = msg.getJSONObject("chat").getLong("id")
                            val text     = msg.optString("text", "")
                            val from     = msg.optJSONObject("from")?.optString("username", "unknown") ?: "unknown"
                            if (text.isNotBlank()) {
                                emit(TelegramMessage(updateId, chatId, text, from))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Poll error: ${e.message}")
                delay(10_000)
            }
        }
    }
}
