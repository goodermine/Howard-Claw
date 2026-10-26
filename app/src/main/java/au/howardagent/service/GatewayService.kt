package au.howardagent.service

import android.app.*
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

        private val HEALTH_PATHS = listOf("/health", "/api/health", "/healthz", "/")

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

                val paths = BootstrapInstaller.getPaths(this@GatewayService)

                setStatus("installing_glibc")
                updateNotification("Installing glibc runtime...")
                extractGlibcLibs(paths)
                installGlibcCompat(paths)

                setStatus("installing_node")
                if (!isNodeInstalled(paths)) {
                    updateNotification("Installing Node.js (glibc)...")
                    installNode(paths) { msg -> updateNotification(msg) }
                }

                setStatus("installing_openclaw")
                if (!isOpenClawInstalled(paths)) {
                    updateNotification("Installing OpenClaw...")
                    extractOpenClaw(paths) { msg -> updateNotification(msg) }
                }

                setStatus("starting_server")
                startServer(paths)

                // Exponential backoff health check (from codex/fix-gateway-start-issue)
                var backoffMs = 1500L
                var online = false
                repeat(10) { attempt ->
                    delay(backoffMs)
                    if (isGatewayOnline()) {
                        online = true
                        return@repeat
                    }
                    Log.d(TAG, "Health check attempt ${attempt + 1}/10 failed")
                    backoffMs = (backoffMs * 2).coerceAtMost(8000L)
                }

                if (online) {
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

    // ── Shell helpers ─────────────────────────────────────────────────────

    private fun runInPrefix(
        paths: BootstrapInstaller.Paths,
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val env = buildShellEnvironment(paths)
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

    // ── Install checks ────────────────────────────────────────────────────

    private fun isNodeInstalled(paths: BootstrapInstaller.Paths): Boolean {
        val nodeReal = File(paths.homeDir, ".openclaw-android/node/bin/node.real")
        val glibcLdSo = File(paths.prefixDir, "glibc/lib/ld-linux-aarch64.so.1")
        return nodeReal.exists() && glibcLdSo.exists()
    }

    private fun isOpenClawInstalled(paths: BootstrapInstaller.Paths): Boolean {
        return File(paths.prefixDir, "lib/node_modules/openclaw").exists()
    }

    // ── glibc extraction ──────────────────────────────────────────────────

    private fun extractGlibcLibs(paths: BootstrapInstaller.Paths) {
        val glibcLdSo = File(paths.prefixDir, "glibc/lib/ld-linux-aarch64.so.1")
        if (glibcLdSo.exists()) {
            Log.i(TAG, "glibc libs already extracted")
            return
        }

        try {
            assets.open("glibc-libs.tar.gz").use { /* exists check */ }
        } catch (_: Exception) {
            throw RuntimeException(
                "glibc-libs.tar.gz not found in assets — rebuild APK with updated bundle_assets.sh"
            )
        }

        Log.i(TAG, "Extracting glibc runtime libraries...")
        val glibcDir = File(paths.prefixDir, "glibc/lib")
        glibcDir.mkdirs()

        val tmpFile = File(paths.tmpDir, "glibc-libs.tar.gz")
        try {
            File(paths.tmpDir).mkdirs()
            assets.open("glibc-libs.tar.gz").use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }

            val exitCode = runInPrefix(
                paths,
                "tar -xzf ${tmpFile.absolutePath} -C ${paths.prefixDir}/glibc && " +
                    "chmod 755 ${paths.prefixDir}/glibc/lib/ld-linux-aarch64.so.1 2>/dev/null && " +
                    "echo done"
            )
            tmpFile.delete()

            if (exitCode != 0 || !glibcLdSo.exists()) {
                throw RuntimeException("glibc extraction failed (exit $exitCode)")
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            tmpFile.delete()
            throw RuntimeException("glibc extraction failed: ${e.message}", e)
        }

        // glibc's getaddrinfo reads $PREFIX/glibc/etc/hosts for localhost resolution.
        // Without it, dns.lookup('localhost') can return 0.0.0.0 → gateway bind failure.
        val glibcEtc = File(paths.prefixDir, "glibc/etc")
        glibcEtc.mkdirs()
        val hostsFile = File(glibcEtc, "hosts")
        if (!hostsFile.exists()) {
            hostsFile.writeText(
                "127.0.0.1 localhost localhost.localdomain\n" +
                    "::1 localhost ip6-localhost ip6-loopback\n"
            )
        }

        Log.i(TAG, "glibc runtime extracted to ${paths.prefixDir}/glibc/")
    }

    private fun installGlibcCompat(paths: BootstrapInstaller.Paths) {
        val destDir = File(paths.prefixDir, "share/howard")
        destDir.mkdirs()
        val dest = File(destDir, "glibc-compat.js")
        if (dest.exists() && dest.length() > 0) return

        try {
            assets.open("glibc-compat.js").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "glibc-compat.js installed")
        } catch (e: Exception) {
            Log.w(TAG, "glibc-compat.js not found in assets — Android quirk fixes unavailable", e)
        }
    }

    // ── Node.js installation (glibc approach) ─────────────────────────────

    private fun installNode(
        paths: BootstrapInstaller.Paths,
        onProgress: (String) -> Unit,
    ) {
        if (extractNodeBundle(paths)) {
            onProgress("Node.js (glibc) extracted from bundle")
            return
        }
        throw RuntimeException(
            "node-supplement.tar.gz not found in assets — rebuild APK with updated bundle_assets.sh"
        )
    }

    private fun extractNodeBundle(paths: BootstrapInstaller.Paths): Boolean {
        try {
            assets.open("node-supplement.tar.gz").use { /* exists check */ }
        } catch (_: Exception) {
            return false
        }

        Log.i(TAG, "Extracting Node.js (glibc, linux-arm64)...")
        val nodeDir = File(paths.homeDir, ".openclaw-android/node")
        nodeDir.mkdirs()

        val tmpFile = File(paths.tmpDir, "node-supplement.tar.gz")
        try {
            File(paths.tmpDir).mkdirs()
            assets.open("node-supplement.tar.gz").use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }

            val exitCode = runInPrefix(
                paths,
                "tar -xzf ${tmpFile.absolutePath} -C ${nodeDir.absolutePath} && " +
                    "chmod 755 ${nodeDir.absolutePath}/bin/node.real 2>/dev/null && " +
                    "echo done"
            )
            tmpFile.delete()

            val nodeReal = File(nodeDir, "bin/node.real")
            if (exitCode == 0 && nodeReal.exists()) {
                // Verify the binary runs via glibc ld.so
                val glibcLdSo = "${paths.prefixDir}/glibc/lib/ld-linux-aarch64.so.1"
                val glibcLibDir = "${paths.prefixDir}/glibc/lib"
                val verifyPb = ProcessBuilder(
                    glibcLdSo, "--library-path", glibcLibDir,
                    nodeReal.absolutePath, "--version"
                )
                verifyPb.environment().clear()
                verifyPb.environment()["HOME"] = paths.homeDir
                verifyPb.environment()["TMPDIR"] = paths.tmpDir
                verifyPb.redirectErrorStream(true)

                val verifyProc = verifyPb.start()
                val version = verifyProc.inputStream.bufferedReader().readText().trim()
                val verifyCode = verifyProc.waitFor()

                if (verifyCode == 0 && version.startsWith("v")) {
                    Log.i(TAG, "Node.js $version verified via glibc ld.so")
                    return true
                }
                Log.e(TAG, "Node.js verification failed: exit=$verifyCode output='$version'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract Node bundle: ${e.message}", e)
        }
        tmpFile.delete()
        return false
    }

    // ── OpenClaw installation ─────────────────────────────────────────────

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
        val glibcLdSo = "${paths.prefixDir}/glibc/lib/ld-linux-aarch64.so.1"
        val glibcLibDir = "${paths.prefixDir}/glibc/lib"
        val nodeReal = "${paths.homeDir}/.openclaw-android/node/bin/node.real"
        val nodeDir = "${paths.homeDir}/.openclaw-android/node"
        val npmCli = "$nodeDir/lib/node_modules/npm/bin/npm-cli.js"

        val env = buildGlibcEnvironment(paths)
        val pb = ProcessBuilder(
            glibcLdSo, "--library-path", glibcLibDir,
            nodeReal, npmCli, "install", "-g", "openclaw",
            "--prefix", paths.prefixDir
        )
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, "[npm] $line")
            onProgress(line)
            line = reader.readLine()
        }
        val code = proc.waitFor()

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
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }

            val exitCode = runInPrefix(
                paths,
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

    // ── Server lifecycle ──────────────────────────────────────────────────

    private fun startServer(paths: BootstrapInstaller.Paths) {
        val glibcLdSo = File(paths.prefixDir, "glibc/lib/ld-linux-aarch64.so.1")
        val nodeReal = File(paths.homeDir, ".openclaw-android/node/bin/node.real")
        val openclawDir = File(paths.prefixDir, "lib/node_modules/openclaw")

        if (!glibcLdSo.exists()) {
            throw RuntimeException("glibc ld.so not found at ${glibcLdSo.absolutePath}")
        }
        if (!nodeReal.exists()) {
            throw RuntimeException("node.real not found at ${nodeReal.absolutePath}")
        }
        if (!openclawDir.exists()) {
            throw RuntimeException("OpenClaw not found at ${openclawDir.absolutePath}")
        }

        val entryPoint = when {
            File(openclawDir, "openclaw.mjs").exists() -> File(openclawDir, "openclaw.mjs").absolutePath
            File(openclawDir, "dist/index.js").exists() -> File(openclawDir, "dist/index.js").absolutePath
            File(openclawDir, "dist/index.mjs").exists() -> File(openclawDir, "dist/index.mjs").absolutePath
            File(openclawDir, "dist/entry.js").exists() -> File(openclawDir, "dist/entry.js").absolutePath
            File(openclawDir, "index.js").exists() -> File(openclawDir, "index.js").absolutePath
            else -> throw RuntimeException("No OpenClaw entry point found in ${openclawDir.absolutePath}")
        }

        val configDir = File(paths.homeDir, ".openclaw").also { it.mkdirs() }
        val configFile = File(configDir, "openclaw.json")
        if (!configFile.exists()) {
            configFile.writeText(
                "{\n" +
                    "  \"port\": $GATEWAY_PORT,\n" +
                    "  \"host\": \"127.0.0.1\"\n" +
                    "}"
            )
        }

        Log.i(TAG, "Starting server via glibc ld.so: ${nodeReal.absolutePath} $entryPoint gateway")

        val env = buildGlibcEnvironment(paths)
        val pb = ProcessBuilder(
            glibcLdSo.absolutePath,
            "--library-path", "${paths.prefixDir}/glibc/lib",
            nodeReal.absolutePath,
            entryPoint, "gateway"
        )
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

    // ── Environment ───────────────────────────────────────────────────────

    /**
     * Environment for running shell commands via $PREFIX/bin/sh (bootstrap utilities like tar).
     * Uses LD_PRELOAD for libtermux-exec path rewriting — needed for Bionic-linked bootstrap
     * binaries but must NOT be set for glibc processes.
     */
    private fun buildShellEnvironment(paths: BootstrapInstaller.Paths): Map<String, String> {
        val appDataDir = filesDir.parentFile?.absolutePath ?: "/data/user/0/au.howardagent"
        return buildMap {
            put("PREFIX", paths.prefixDir)
            put("HOME", paths.homeDir)
            put("PATH", "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin")
            put("LD_LIBRARY_PATH", "${paths.prefixDir}/lib")
            val termuxExecLib = File(paths.prefixDir, "lib/libtermux-exec.so")
            if (termuxExecLib.exists()) {
                put("LD_PRELOAD", termuxExecLib.absolutePath)
            }
            put("TERMUX_PREFIX", paths.prefixDir)
            put("TERMUX__PREFIX", paths.prefixDir)
            put("TERMUX__ROOTFS", paths.filesDir)
            put("TERMUX_APP__DATA_DIR", appDataDir)
            put("TERMUX_APP__LEGACY_DATA_DIR", "/data/data/com.termux")
            put("LANG", "en_US.UTF-8")
            put("TMPDIR", paths.tmpDir)
            put("TERM", "xterm-256color")
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")
            put("APT_CONFIG", "${paths.prefixDir}/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "${paths.prefixDir}/var/lib/dpkg")
            put("DPKG_ROOT", paths.prefixDir)
            put("SSL_CERT_FILE", "${paths.prefixDir}/etc/tls/cert.pem")
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            put("CURL_CA_BUNDLE", "${paths.prefixDir}/etc/tls/cert.pem")
            put("GIT_SSL_CAINFO", "${paths.prefixDir}/etc/tls/cert.pem")
            put("GIT_CONFIG_NOSYSTEM", "1")
        }
    }

    /**
     * Environment for running glibc Node.js via ld-linux-aarch64.so.1.
     * Critical: LD_PRELOAD must NOT be set — Bionic's libtermux-exec.so causes
     * "Could not find a PHDR" crashes when loaded into glibc processes.
     * Based on openclaw-android's EnvironmentBuilder + node wrapper.
     */
    private fun buildGlibcEnvironment(paths: BootstrapInstaller.Paths): Map<String, String> {
        val appDataDir = filesDir.parentFile?.absolutePath ?: "/data/user/0/au.howardagent"
        val nodeDir = "${paths.homeDir}/.openclaw-android/node"
        val binDir = "${paths.homeDir}/.openclaw-android/bin"
        val glibcCompat = "${paths.prefixDir}/share/howard/glibc-compat.js"

        return buildMap {
            put("PREFIX", paths.prefixDir)
            put("HOME", paths.homeDir)
            put("TMPDIR", paths.tmpDir)
            put("TMP", paths.tmpDir)
            put("TEMP", paths.tmpDir)

            put("PATH", "$binDir:$nodeDir/bin:${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin")
            put("LD_LIBRARY_PATH", "${paths.prefixDir}/glibc/lib:${paths.prefixDir}/lib")

            // DO NOT set LD_PRELOAD — glibc processes must not load Bionic libtermux-exec.so
            // libtermux-exec path rewriting vars (for processes that do need it)
            put("TERMUX_PREFIX", paths.prefixDir)
            put("TERMUX__PREFIX", paths.prefixDir)
            put("TERMUX__ROOTFS", paths.filesDir)
            put("TERMUX_APP__DATA_DIR", appDataDir)
            put("TERMUX_APP__LEGACY_DATA_DIR", "/data/data/com.termux")

            // glibc-compat.js fixes Android kernel quirks (os.cpus(), networkInterfaces, DNS, spawn)
            if (File(glibcCompat).exists()) {
                put("NODE_OPTIONS", "-r $glibcCompat")
            }

            // Prevent SIGSYS from openat2 syscall (Android seccomp)
            put("FS_SAFE_TEST_NO_OPENAT2", "1")
            // Prevent OpenClaw self-update
            put("OPENCLAW_NO_AUTO_UPDATE", "1")
            // Mark as glibc environment
            put("OA_GLIBC", "1")
            put("CONTAINER", "1")

            // apt/dpkg
            put("APT_CONFIG", "${paths.prefixDir}/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "${paths.prefixDir}/var/lib/dpkg")
            put("DPKG_ROOT", paths.prefixDir)

            // SSL/TLS
            put("SSL_CERT_FILE", "${paths.prefixDir}/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "${paths.prefixDir}/etc/tls/cert.pem")
            put("GIT_SSL_CAINFO", "${paths.prefixDir}/etc/tls/cert.pem")

            // Git
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_EXEC_PATH", "${paths.prefixDir}/libexec/git-core")
            put("GIT_TEMPLATE_DIR", "${paths.prefixDir}/share/git-core/templates")

            // Locale and terminal
            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")

            // Android
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            // OpenClaw workspace
            put("CLAWDHUB_WORKDIR", "${paths.homeDir}/.openclaw/workspace")
        }
    }

    // ── Health check (multi-path, from codex/fix-gateway-start-issue) ─────

    fun isGatewayOnline(): Boolean {
        for (path in HEALTH_PATHS) {
            try {
                val conn = URL("http://127.0.0.1:$GATEWAY_PORT$path")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) return true
            } catch (_: Exception) {
                // try next path
            }
        }
        return false
    }

    // ── Notifications ─────────────────────────────────────────────────────

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
