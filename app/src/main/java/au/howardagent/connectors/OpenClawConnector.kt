package au.howardagent.connectors

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenClawConnector {

    companion object {
        private const val TAG      = "OpenClawConnector"
        private const val BASE_URL = "http://127.0.0.1:18789"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun isOnline(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL/health").build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun sendTask(task: String): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("message", task).toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$BASE_URL/api/message")
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(resp.body?.string() ?: "")
                } else {
                    Result.failure(Exception("HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendTask failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun connectWebSocket(
        onMessage:  (String) -> Unit,
        onOpen:     () -> Unit = {},
        onClosed:   () -> Unit = {},
        onError:    (String) -> Unit = {}
    ): WebSocket {
        val req = Request.Builder().url("ws://127.0.0.1:18789").build()
        return client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: Response) = onOpen()
            override fun onMessage(ws: WebSocket, text: String) = onMessage(text)
            override fun onClosing(ws: WebSocket, code: Int, reason: String) = onClosed()
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) = onError(t.message ?: "Unknown error")
        })
    }
}
