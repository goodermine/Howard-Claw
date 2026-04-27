package au.howardagent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import au.howardagent.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GatewayService : Service() {

    companion object {
        const val TAG             = "GatewayService"
        const val CHANNEL_ID      = "howard_gateway"
        const val NOTIF_ID        = 1001
        const val GATEWAY_PORT    = 18789

        const val ACTION_START    = "au.howardagent.GATEWAY_START"
        const val ACTION_STOP     = "au.howardagent.GATEWAY_STOP"

        private val _status = MutableStateFlow("stopped")
        val status: StateFlow<String> = _status.asStateFlow()
    }

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverProcess: Process? = null
    private var startupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("OpenClaw gateway idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopGateway()
            else -> startGateway()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serverProcess?.destroyForcibly()
        scope.cancel()
        setStatus("stopped")
        super.onDestroy()
    }

    // ── Start / stop ──────────────────────────────────────────────────────

    private fun startGateway() {
        if (startupJob?.isActive == true) return

        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        startupJob = scope.launch {
            try {
                setStatus("extracting_bootstrap")
                if (!BootstrapInstaller.isBootstrapInstalled(this@GatewayService)) {
                    updateNotification("Extracting runtime environment...")
                    BootstrapInstaller.install(this@GatewayService) { msg ->
                        updateNotification(msg)
                    }
                }

                setStatus("installing_node")
                val paths = BootstrapInstaller.getPaths(this@GatewayService)
                if (!isNodeInstalled(paths)) {
                    updateNotification("Installing Node.js...")
                    installNode(paths) { msg -> updateNotification(msg) }
                }

                setStatus("installing_openclaw")
                if (!isOpenClawInstalled(paths)) {
                    updateNotification("Installing OpenClaw...")
                    extractOpenClaw(paths) { msg -> updateNotification(msg) }
                }

                setStatus("starting_server")
                startServer(paths)

                delay(3000)
                if (isGatewayOnline()) {
                    setStatus("online")
                    updateNotification("Howard online — port $GATEWAY_PORT")
                } else {
                    setStatus("error: server started but /health not reachable")
                    updateNotification("Gateway started but health check failed")
                }

                monitorServer(paths)
            } catch (e: Exception) {
                Log.e(TAG, "Gateway startup failed: ${e.message}", e)
                val msg = e.message ?: "Unknown error"
                setStatus("error: $msg")
                updateNotification("Gateway error: $msg")
            }
        }
    }

    private fun stopGateway() {
        startupJob?.cancel()
        startupJob = null
        serverProcess?.destroyForcibly()
        serverProcess = null
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        setStatus("stopped")
        updateNotification("OpenClaw gateway stopped")
    }

    // ── Status broadcast ──────────────────────────────────────────────────

    private fun setStatus(value: String) {
        _status.value = value
    }

    // ── Shell helpers (from codexUI) ───────────────────────────────────────

    private fun runInPrefix(
        paths: BootstrapInstaller.Paths,
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"

        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    // ── Install checks ─────────────────────────────────────────────────────

    private fun isNodeInstalled(paths: BootstrapInstaller.Paths): Boolean {
        return File(paths.prefixDir, "bin/node").exists()
    }

    private fun isOpenClawInstalled(paths: BootstrapInstaller.Paths): Boolean {
        return File(paths.prefixDir, "lib/node_modules/openclaw").exists()
    }

    // ── Node.js installation (from codexUI pattern) ────────────────────────

    private fun installNode(
        paths: BootstrapInstaller.Paths,
        onProgress: (String) -> Unit,
    ) {
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        if (extractNodeSupplement(paths)) {
            onProgress("Node.js extracted from bundle")
            return
        }

        onProgress("Downloading Node.js packages…")
        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated c-ares libicu libsqlite nodejs-lts npm 2>&1
        """.trimIndent()

        runInPrefix(paths, downloadCmd) { onProgress(it) }

        onProgress("Extracting Node.js packages…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _stage &&
            for deb in *.deb; do
                echo "Extracting ${'$'}deb..." &&
                dpkg-deb -x "${'$'}deb" _stage/ 2>&1
            done &&
            if [ -d "_stage$termuxPrefix" ]; then
                cp -a _stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_stage/usr" ]; then
                cp -a _stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _stage *.deb 2>/dev/null
            echo "done"
        """.trimIndent()

        val extractCode = runInPrefix(paths, extractCmd) { onProgress(it) }
        if (extractCode != 0) {
            Log.e(TAG, "dpkg-deb extract failed with code $extractCode")
        }

        onProgress("Fixing script paths…")
        val appDataDir = filesDir.parentFile?.absolutePath ?: "/data/user/0/au.howardagent"
        val fixCmd = """
            chmod 700 "$prefix/bin/node" 2>/dev/null

            NPM_CLI="$prefix/lib/node_modules/npm/bin/npm-cli.js"
            if [ -f "${'$'}NPM_CLI" ]; then
                rm -f "$prefix/bin/npm"
                cat > "$prefix/bin/npm" << 'WEOF'
#!/$appDataDir/files/usr/bin/sh
exec $appDataDir/files/usr/bin/node $appDataDir/files/usr/lib/node_modules/npm/bin/npm-cli.js "${'$'}@"
WEOF
                chmod 700 "$prefix/bin/npm"
            fi

            echo "Wrapper scripts created"
        """.trimIndent()
        runInPrefix(paths, fixCmd) { onProgress(it) }

        if (!isNodeInstalled(paths)) {
            throw RuntimeException("Node.js installation failed")
        }
        Log.i(TAG, "Node.js installed successfully")
    }

    private fun extractNodeSupplement(paths: BootstrapInstaller.Paths): Boolean {
        try {
            assets.open("node-supplement.tar.gz").use { /* exists check */ }
        } catch (_: Exception) {
            return false
        }

        Log.i(TAG, "Extracting Node.js from bundled supplement...")
        val tmpFile = File(paths.tmpDir, "node-supplement.tar.gz")
        try {
            File(paths.tmpDir).mkdirs()
            assets.open("node-supplement.tar.gz").use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val exitCode = runInPrefix(paths,
                "tar -xzf ${tmpFile.absolutePath} -C ${paths.prefixDir} && " +
                "chmod 700 ${paths.prefixDir}/bin/node 2>/dev/null && " +
                "echo done"
            )
            tmpFile.delete()

            if (exitCode == 0 && isNodeInstalled(paths)) {
                Log.i(TAG, "Node.js supplement extracted successfully")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract Node supplement: ${e.message}", e)
        }
        tmpFile.delete()
        return false
    }

    // ── OpenClaw installation ──────────────────────────────────────────────

    private fun extractOpenClaw(
        paths: BootstrapInstaller.Paths,
        onProgress: (String) -> Unit,
    ) {
        if (extractOpenClawFromAsset(paths)) {
            onProgress("OpenClaw extracted from bundle")
            return
        }

        if (!isNodeInstalled(paths)) {
            throw RuntimeException("Node.js required to install OpenClaw via npm")
        }

        onProgress("Installing OpenClaw via npm…")
        val prefix = paths.prefixDir
        val npmCli = "$prefix/lib/node_modules/npm/bin/npm-cli.js"

        val code = runInPrefix(paths,
            "node $npmCli install -g openclaw 2>&1"
        ) { onProgress(it) }

        if (code != 0) {
            Log.e(TAG, "npm install openclaw failed with code $code")
            throw RuntimeException("OpenClaw installation failed")
        }

        Log.i(TAG, "OpenClaw installed via npm")
    }

    private fun extractOpenClawFromAsset(paths: BootstrapInstaller.Paths): Boolean {
        try {
            assets.open("openclaw.tar.gz").use { /* exists check */ }
        } catch (_: Exception) {
            return false
        }

        Log.i(TAG, "Extracting OpenClaw from bundled asset...")
        val nodeModulesDir = File(paths.prefixDir, "lib/node_modules")
        nodeModulesDir.mkdirs()

        val tmpFile = File(paths.tmpDir, "openclaw.tar.gz")
        try {
            File(paths.tmpDir).mkdirs()
            assets.open("openclaw.tar.gz").use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val exitCode = runInPrefix(paths,
                "tar -xzf ${tmpFile.absolutePath} -C ${nodeModulesDir.absolutePath} && echo done"
            )
            tmpFile.delete()

            if (exitCode == 0 && isOpenClawInstalled(paths)) {
                Log.i(TAG, "OpenClaw extracted from asset")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract OpenClaw asset: ${e.message}", e)
        }
        tmpFile.delete()
        return false
    }

    // ── Server lifecycle ───────────────────────────────────────────────────

    private fun startServer(paths: BootstrapInstaller.Paths) {
        val nodeBin = File(paths.prefixDir, "bin/node")
        val openclawDir = File(paths.prefixDir, "lib/node_modules/openclaw")

        if (!nodeBin.exists()) {
            throw RuntimeException("Node binary not found at ${nodeBin.absolutePath}")
        }

        if (!openclawDir.exists()) {
            throw RuntimeException("OpenClaw not found at ${openclawDir.absolutePath}")
        }

        val entryPoint = when {
            File(openclawDir, "dist/index.js").exists() -> File(openclawDir, "dist/index.js").absolutePath
            File(openclawDir, "openclaw.mjs").exists() -> File(openclawDir, "openclaw.mjs").absolutePath
            File(openclawDir, "index.js").exists() -> File(openclawDir, "index.js").absolutePath
            else -> throw RuntimeException("No OpenClaw entry point found in ${openclawDir.absolutePath}")
        }

        val configDir = File(paths.homeDir, ".openclaw").also { it.mkdirs() }
        val configFile = File(configDir, "openclaw.json")
        if (!configFile.exists()) {
            configFile.writeText("""
                {
                    "port": $GATEWAY_PORT,
                    "host": "127.0.0.1"
                }
            """.trimIndent())
        }

        Log.i(TAG, "Starting server: node $entryPoint")

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val command = "exec node $entryPoint"

        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.environment()["PORT"] = GATEWAY_PORT.toString()
        pb.environment()["OPENCLAW_CONFIG"] = configFile.absolutePath
        pb.directory(openclawDir)
        pb.redirectErrorStream(true)

        serverProcess = pb.start()

        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    Log.i(TAG, "[openclaw] $line")
                    line = reader.readLine()
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun monitorServer(paths: BootstrapInstaller.Paths) {
        while (scope.isActive) {
            delay(30_000)

            val alive = try {
                serverProcess?.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }

            if (!alive) {
                Log.w(TAG, "Server process died, restarting in 5s...")
                setStatus("restarting")
                updateNotification("Gateway restarting...")
                delay(5000)
                try {
                    startServer(paths)
                    delay(3000)
                    if (isGatewayOnline()) {
                        setStatus("online")
                        updateNotification("Howard online — port $GATEWAY_PORT")
                    } else {
                        setStatus("error: process restarted but /health unreachable")
                    }
                } catch (e: Exception) {
                    setStatus("error: restart failed — ${e.message}")
                    updateNotification("Gateway error: ${e.message}")
                }
            }
        }
    }

    // ── Environment ────────────────────────────────────────────────────────

    private fun buildEnvironment(paths: BootstrapInstaller.Paths): Map<String, String> {
        return mapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
            "LD_PRELOAD" to "${paths.prefixDir}/lib/libtermux-exec.so",
            "TERMUX_PREFIX" to paths.prefixDir,
            "TERMUX__PREFIX" to paths.prefixDir,
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to paths.tmpDir,
            "TERM" to "xterm-256color",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "APT_CONFIG" to "${paths.prefixDir}/etc/apt/apt.conf",
            "DPKG_ADMINDIR" to "${paths.prefixDir}/var/lib/dpkg",
            "SSL_CERT_FILE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_SSL_CAINFO" to "${paths.prefixDir}/etc/tls/cert.pem",
            "NODE_OPTIONS" to "--openssl-config=${paths.prefixDir}/etc/tls/openssl.cnf",
        )
    }

    // ── Health check ──────────────────────────────────────────────────────

    fun isGatewayOnline(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$GATEWAY_PORT/health")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Exception) {
            false
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
