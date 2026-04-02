package au.howardagent.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import au.howardagent.HowardApplication
import au.howardagent.R
import au.howardagent.agent.CommandDispatcher
import au.howardagent.agent.PromptBuilder
import au.howardagent.connectors.TelegramConnector
import au.howardagent.download.ModelDownloader
import au.howardagent.download.ModelRegistry
import au.howardagent.engine.EngineRouter
import kotlinx.coroutines.*

class InferenceService : Service() {

    companion object {
        const val TAG         = "InferenceService"
        const val CHANNEL_ID  = "howard_inference"
        const val NOTIF_ID    = 1002
        const val ACTION_TASK = "au.howardagent.ACTION_TASK"
        const val EXTRA_TASK  = "task"
    }

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs      = HowardApplication.instance.securePrefs
    private val db         = HowardApplication.instance.database
    private val downloader = ModelDownloader(HowardApplication.instance)
    private val dispatcher = CommandDispatcher(HowardApplication.instance)
    private val router     = EngineRouter(prefs)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Howard inference ready"))

        if (prefs.telegramEnabled && prefs.telegramBotToken.isNotBlank()) {
            startTelegramPolling()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.action == ACTION_TASK) {
                val task = it.getStringExtra(EXTRA_TASK) ?: return@let
                scope.launch { handleTask(task, replyTo = null) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        router.releaseAll()
        super.onDestroy()
    }

    private suspend fun handleTask(task: String, replyTo: Long?) {
        updateNotification("Working: ${task.take(50)}...")
        val buffer = StringBuilder()

        try {
            val localPath = getActiveModelPath()
            val engine    = router.getEngine(localPath)
            val system    = PromptBuilder.build()

            engine.infer(
                prompt       = task,
                systemPrompt = system,
                onToken      = { tok ->
                    buffer.append(tok)
                    val m = Regex("""\[CMD:\s*([^\]]+)\]""").find(buffer)
                    if (m != null) {
                        val cmd = m.groupValues[1]
                        buffer.delete(0, m.range.last + 1)
                        scope.launch {
                            val result = dispatcher.dispatch(cmd)
                            buffer.append("\n[Result] $result\n")
                        }
                    }
                },
                onComplete = {
                    val response = buffer.toString().trim()
                    Log.i(TAG, "Task complete: ${response.take(100)}")
                    if (replyTo != null && prefs.telegramEnabled) {
                        scope.launch {
                            TelegramConnector(prefs).sendMessage(response, replyTo.toString())
                        }
                    }
                    updateNotification("Last task complete")
                },
                onError = { err ->
                    Log.e(TAG, "Inference error: $err")
                    updateNotification("Error: $err")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleTask failed: ${e.message}")
        }
    }

    private fun startTelegramPolling() {
        scope.launch {
            Log.i(TAG, "Starting Telegram polling")
            TelegramConnector(prefs).pollUpdates().collect { msg ->
                Log.i(TAG, "Telegram task from @${msg.fromUsername}: ${msg.text}")
                handleTask(msg.text, replyTo = msg.chatId)
            }
        }
    }

    private suspend fun getActiveModelPath(): String? {
        val active = db.modelDao().getActiveModel() ?: return null
        val file   = downloader.modelFile(ModelRegistry.getById(active.id) ?: return null)
        return if (file.exists()) file.absolutePath else null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Howard Inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) =
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
