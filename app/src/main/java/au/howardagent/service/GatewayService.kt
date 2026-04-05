package au.howardagent.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import au.howardagent.R
import au.howardagent.connectors.OpenClawConnector
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class GatewayService : Service() {

    companion object {
        const val TAG        = "GatewayService"
        const val CHANNEL_ID = "howard_gateway"
        const val NOTIF_ID   = 1001
        const val GATEWAY_PORT = 18789
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nodeProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting OpenClaw gateway..."))

        scope.launch {
            extractAssetsIfNeeded()
            startNodeProcess()
            monitorGateway()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        nodeProcess?.destroyForcibly()
        scope.cancel()
        super.onDestroy()
    }

    private fun extractAssetsIfNeeded() {
        val runtimeDir = File(filesDir, "runtime")
        val marker     = File(runtimeDir, ".extracted")

        if (marker.exists()) {
            Log.i(TAG, "Assets already extracted")
            return
        }

        Log.i(TAG, "Extracting assets...")
        runtimeDir.mkdirs()

        // Node.js binary is in nativeLibraryDir as libnode.so — placed there
        // automatically by Android from jniLibs/arm64-v8a/ at install time.
        // No extraction needed for it.

        // Extract OpenClaw package (JavaScript — still bundled in assets)
        val openclawTar = File(runtimeDir, "openclaw.tar.gz")
        assets.open("openclaw.tar.gz").use { input ->
            FileOutputStream(openclawTar).use { output ->
                input.copyTo(output)
            }
        }

        // Untar OpenClaw
        val pb = ProcessBuilder("tar", "-xzf", openclawTar.absolutePath, "-C", runtimeDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        pb.waitFor()
        openclawTar.delete()
        Log.i(TAG, "OpenClaw extracted")

        marker.createNewFile()
        updateNotification("Assets extracted")
    }

    private fun startNodeProcess() {
        val runtimeDir   = File(filesDir, "runtime")
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val nodeBin      = File(nativeLibDir, "libnode.so")
        val openclawDir  = File(runtimeDir, "openclaw")

        if (!nodeBin.exists()) {
            Log.e(TAG, "Node binary not found at ${nodeBin.absolutePath}")
            updateNotification("Error: Node binary missing")
            return
        }

        Log.i(TAG, "Node binary: ${nodeBin.absolutePath} (${nodeBin.length()} bytes)")

        // Create tmp directory for OpenClaw
        val tmpDir = File(filesDir, "tmp").also { it.mkdirs() }

        // Create OpenClaw config
        val configDir = File(filesDir, ".openclaw").also { it.mkdirs() }
        val configFile = File(configDir, "openclaw.json")
        if (!configFile.exists()) {
            configFile.writeText("""
                {
                    "port": $GATEWAY_PORT,
                    "host": "127.0.0.1"
                }
            """.trimIndent())
        }

        // Find the entry point (OpenClaw's package.json: main = "dist/index.js")
        val entryPoint = File(openclawDir, "dist/index.js").let {
            if (it.exists()) it.absolutePath
            else File(openclawDir, "openclaw.mjs").absolutePath
        }

        Log.i(TAG, "Starting Node: ${nodeBin.absolutePath} $entryPoint")

        val pb = ProcessBuilder(nodeBin.absolutePath, entryPoint)
            .directory(openclawDir)
            .redirectErrorStream(true)

        // LD_LIBRARY_PATH must point to nativeLibraryDir so Node.js can find
        // its shared library dependencies (ICU, libc++, etc.)
        pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
        pb.environment()["HOME"]   = filesDir.absolutePath
        pb.environment()["PORT"]   = GATEWAY_PORT.toString()
        pb.environment()["OPENCLAW_CONFIG"] = configFile.absolutePath

        nodeProcess = pb.start()

        // Stream stdout to logcat
        scope.launch {
            try {
                nodeProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.i(TAG, "[node] $line")
                }
            } catch (_: Exception) {}
        }

        updateNotification("Howard online - port $GATEWAY_PORT")
    }

    private suspend fun monitorGateway() {
        val connector = OpenClawConnector()
        while (scope.isActive) {
            delay(30_000) // Check every 30s

            if (nodeProcess?.isAlive != true) {
                Log.w(TAG, "Node process died, restarting in 5s...")
                updateNotification("Gateway restarting...")
                delay(5000)
                startNodeProcess()
            }
        }
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
