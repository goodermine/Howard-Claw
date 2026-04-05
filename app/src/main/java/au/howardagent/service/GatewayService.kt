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
import java.util.zip.ZipInputStream

/**
 * Foreground service that manages the OpenClaw gateway.
 *
 * Architecture (based on codexUI/AnyClaw):
 * - Extracts a Termux bootstrap environment into app-private storage
 * - Installs Node.js via the Termux package system
 * - Runs OpenClaw as a Node.js subprocess via ProcessBuilder
 * - Sets LD_PRELOAD=libtermux-exec.so for proper exec() behavior
 *
 * Requires targetSdk <= 28 to allow executing binaries from app data dirs
 * (Android 10+ with targetSdk 29+ enforces W^X via SELinux).
 */
class GatewayService : Service() {

    companion object {
        const val TAG          = "GatewayService"
        const val CHANNEL_ID   = "howard_gateway"
        const val NOTIF_ID     = 1001
        const val GATEWAY_PORT = 18789
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nodeProcess: Process? = null

    // Termux-style paths rooted in app's private data dir
    private val prefixDir by lazy { File(filesDir, "usr") }
    private val binDir    by lazy { File(prefixDir, "bin") }
    private val libDir    by lazy { File(prefixDir, "lib") }
    private val homeDir   by lazy { File(filesDir, "home").also { it.mkdirs() } }
    private val tmpDir    by lazy { File(filesDir, "tmp").also { it.mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting OpenClaw gateway..."))

        scope.launch {
            try {
                extractBootstrapIfNeeded()
                extractOpenClawIfNeeded()
                startNodeProcess()
                monitorGateway()
            } catch (e: Exception) {
                Log.e(TAG, "Gateway startup failed: ${e.message}", e)
                updateNotification("Gateway error: ${e.message}")
            }
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

    // ── Bootstrap extraction ────────────────────────────────────────────────

    /**
     * Extract the Termux bootstrap environment (bootstrap-aarch64.zip) from
     * assets into filesDir/usr/. This provides bin/sh, bin/node (after install),
     * lib/libtermux-exec.so, and other essential binaries.
     */
    private fun extractBootstrapIfNeeded() {
        val marker = File(prefixDir, ".bootstrap_done")
        if (marker.exists()) {
            Log.i(TAG, "Bootstrap already extracted")
            return
        }

        Log.i(TAG, "Extracting Termux bootstrap...")
        updateNotification("Extracting runtime environment...")
        prefixDir.mkdirs()

        try {
            assets.open("bootstrap-aarch64.zip").use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val outFile = File(prefixDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                zip.copyTo(out)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract bootstrap: ${e.message}", e)
            updateNotification("Error: Bootstrap extraction failed")
            return
        }

        // Set execute permission on all binaries
        binDir.listFiles()?.forEach { it.setExecutable(true, false) }
        // Also set execute on libtermux-exec.so
        File(libDir, "libtermux-exec.so").let {
            if (it.exists()) it.setExecutable(true, false)
        }

        // Handle Termux symlinks file if present
        // The bootstrap zip may contain a SYMLINKS.txt file listing symlinks to create
        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (symlinksFile.exists()) {
            try {
                symlinksFile.readLines().forEach { line ->
                    val parts = line.split("←")
                    if (parts.size == 2) {
                        val target = parts[0].trim()
                        val linkPath = parts[1].trim()
                        val linkFile = File(prefixDir, linkPath)
                        val targetFile = File(prefixDir, target)
                        linkFile.parentFile?.mkdirs()
                        // On Android, symlinks via Os.symlink
                        try {
                            android.system.Os.symlink(targetFile.absolutePath, linkFile.absolutePath)
                        } catch (_: Exception) {
                            // Fallback: copy file
                            if (targetFile.exists()) targetFile.copyTo(linkFile, overwrite = true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Symlinks processing failed: ${e.message}")
            }
        }

        marker.createNewFile()
        Log.i(TAG, "Bootstrap extracted to ${prefixDir.absolutePath}")

        // Extract Node.js supplement (node binary + npm) on top of bootstrap
        extractNodeSupplement()
    }

    /**
     * Extract node-supplement.tar.gz (Node.js binary + npm from Termux) into
     * the prefix directory, overlaying onto the bootstrap.
     */
    private fun extractNodeSupplement() {
        try {
            assets.open("node-supplement.tar.gz").use { /* exists */ }
        } catch (_: Exception) {
            Log.w(TAG, "No node-supplement.tar.gz in assets — Node.js not bundled")
            return
        }

        Log.i(TAG, "Extracting Node.js supplement...")
        val supplementTar = File(tmpDir, "node-supplement.tar.gz")
        try {
            assets.open("node-supplement.tar.gz").use { input ->
                FileOutputStream(supplementTar).use { output ->
                    input.copyTo(output)
                }
            }

            // Use system tar (available on most Android via toybox)
            val pb = ProcessBuilder("tar", "-xzf", supplementTar.absolutePath,
                "-C", prefixDir.absolutePath)
                .redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            supplementTar.delete()

            if (exitCode != 0) {
                Log.e(TAG, "Node supplement extraction failed: $output")
                return
            }

            // Ensure node binary is executable
            File(binDir, "node").setExecutable(true, false)
            File(binDir, "npm").let { if (it.exists()) it.setExecutable(true, false) }
            Log.i(TAG, "Node.js supplement extracted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract Node supplement: ${e.message}", e)
        }
    }

    /**
     * Extract OpenClaw package from assets/openclaw.tar.gz into the Termux
     * prefix lib/node_modules/ directory.
     */
    private fun extractOpenClawIfNeeded() {
        val openclawDir = File(libDir, "node_modules/openclaw")
        val marker = File(openclawDir, ".openclaw_done")
        if (marker.exists()) {
            Log.i(TAG, "OpenClaw already extracted")
            return
        }

        Log.i(TAG, "Extracting OpenClaw...")
        updateNotification("Extracting OpenClaw...")

        val runtimeDir = File(filesDir, "runtime")
        runtimeDir.mkdirs()

        try {
            val openclawTar = File(runtimeDir, "openclaw.tar.gz")
            assets.open("openclaw.tar.gz").use { input ->
                FileOutputStream(openclawTar).use { output ->
                    input.copyTo(output)
                }
            }

            // Use the Termux-provided tar if available, otherwise try system tar
            val tarBin = File(binDir, "tar").let {
                if (it.exists() && it.canExecute()) it.absolutePath else "tar"
            }

            val nodeModulesDir = File(libDir, "node_modules")
            nodeModulesDir.mkdirs()

            val pb = ProcessBuilder(tarBin, "-xzf", openclawTar.absolutePath,
                "-C", nodeModulesDir.absolutePath)
                .redirectErrorStream(true)
            pb.environment().putAll(buildEnvironment())
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()

            if (exitCode != 0) {
                Log.e(TAG, "tar extraction failed (exit $exitCode): $output")
            }

            openclawTar.delete()
            marker.createNewFile()
            Log.i(TAG, "OpenClaw extracted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract OpenClaw: ${e.message}", e)
            updateNotification("Error: OpenClaw extraction failed")
        }
    }

    // ── Node.js process management ──────────────────────────────────────────

    private fun startNodeProcess() {
        val nodeBin = File(binDir, "node")
        val openclawDir = File(libDir, "node_modules/openclaw")

        if (!nodeBin.exists()) {
            Log.e(TAG, "Node binary not found at ${nodeBin.absolutePath}")
            updateNotification("Error: Node.js not installed — run bundle_assets.sh")
            return
        }

        if (!openclawDir.exists()) {
            Log.e(TAG, "OpenClaw not found at ${openclawDir.absolutePath}")
            updateNotification("Error: OpenClaw not installed")
            return
        }

        // Find the entry point
        val entryPoint = File(openclawDir, "dist/index.js").let {
            if (it.exists()) it.absolutePath
            else File(openclawDir, "openclaw.mjs").absolutePath
        }

        // Create OpenClaw config
        val configDir = File(homeDir, ".openclaw").also { it.mkdirs() }
        val configFile = File(configDir, "openclaw.json")
        if (!configFile.exists()) {
            configFile.writeText("""
                {
                    "port": $GATEWAY_PORT,
                    "host": "127.0.0.1"
                }
            """.trimIndent())
        }

        Log.i(TAG, "Starting Node: ${nodeBin.absolutePath} $entryPoint")

        val pb = ProcessBuilder(nodeBin.absolutePath, entryPoint)
            .directory(openclawDir)
            .redirectErrorStream(true)

        pb.environment().putAll(buildEnvironment())
        pb.environment()["PORT"] = GATEWAY_PORT.toString()
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

        updateNotification("Howard online — port $GATEWAY_PORT")
    }

    /**
     * Build the environment variables for Termux-style subprocess execution.
     * Mirrors the approach from codexUI/AnyClaw.
     */
    private fun buildEnvironment(): Map<String, String> {
        val termuxExecLib = File(libDir, "libtermux-exec.so")
        val env = mutableMapOf(
            "PREFIX" to prefixDir.absolutePath,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to "${binDir.absolutePath}:${binDir.absolutePath}/applets:/system/bin",
            "LD_LIBRARY_PATH" to libDir.absolutePath,
            "TERMUX_PREFIX" to prefixDir.absolutePath,
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
        )
        // LD_PRELOAD libtermux-exec.so for proper exec() behavior
        if (termuxExecLib.exists()) {
            env["LD_PRELOAD"] = termuxExecLib.absolutePath
        }
        // SSL certificates if available in Termux prefix
        val caCerts = File(prefixDir, "etc/tls/cert.pem")
        if (caCerts.exists()) {
            env["SSL_CERT_FILE"] = caCerts.absolutePath
            env["NODE_EXTRA_CA_CERTS"] = caCerts.absolutePath
        }
        return env
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

    // ── Notifications ───────────────────────────────────────────────────────

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
