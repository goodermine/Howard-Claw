package au.howardagent.agent

import android.content.Context
import android.util.Log
import au.howardagent.HowardApplication
import au.howardagent.connectors.TelegramConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ToolExecutor(private val context: Context) {

    companion object { private const val TAG = "ToolExecutor" }

    private val workspaceDir = File(context.filesDir, "workspace").also { it.mkdirs() }
    private val prefs        = HowardApplication.instance.securePrefs

    suspend fun execute(tool: String, args: String): String = withContext(Dispatchers.IO) {
        Log.i(TAG, "Execute: $tool '$args'")
        when (tool) {
            "github_sync"       -> githubSync(args)
            "file_organizer"    -> fileOrganizer(args)
            "web_component_gen" -> webComponentGen(args)
            "telegram_send"     -> telegramSend(args)
            else                -> "[howard] Unknown tool: $tool"
        }
    }

    private fun githubSync(args: String): String {
        val parts = args.split(" ", limit = 2)
        if (parts.size < 2) return "[howard] Usage: github_sync <repo_url> <local_dir>"
        val repoUrl   = parts[0]
        val targetDir = resolveDir(parts[1])

        return if (File(targetDir, ".git").exists()) {
            runShell("git -C $targetDir pull --rebase 2>&1")
                .let { "[howard] Pulled -> $targetDir\n$it" }
        } else {
            File(targetDir).parentFile?.mkdirs()
            runShell("git clone --depth=1 $repoUrl $targetDir 2>&1")
                .let { "[howard] Cloned $repoUrl -> $targetDir\n$it" }
        }
    }

    private fun fileOrganizer(args: String): String {
        val srcPath = resolveDir(args.trim())
        val src     = File(srcPath)
        if (!src.exists()) return "[howard] Directory not found: $srcPath"

        var moved = 0
        var skipped = 0
        src.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val ext  = file.extension.lowercase().ifBlank { "unknown" }
            val dest = File(src, ext).also { it.mkdirs() }
            if (file.renameTo(File(dest, file.name))) moved++ else skipped++
        }
        return "[howard] Organised $srcPath\n[howard] Moved: $moved Skipped: $skipped"
    }

    private fun webComponentGen(args: String): String {
        val parts    = args.split(" ", limit = 2)
        val name     = parts[0].trim().replaceFirstChar { it.uppercase() }
        val outDir   = if (parts.size > 1) resolveDir(parts[1]) else "$workspaceDir/components"
        File(outDir).mkdirs()
        val outFile  = File(outDir, "$name.jsx")

        outFile.writeText("""
import { useState } from "react";

export default function $name({ className = "" }) {
  const [active, setActive] = useState(false);

  return (
    <div className={`howard-component ${'$'}{className}`}>
      <button onClick={() => setActive(!active)}>
        {active ? "Active" : "Inactive"}
      </button>
    </div>
  );
}
""".trimIndent())

        return "[howard] Component created: ${outFile.absolutePath}\n[howard] Lines: ${outFile.readLines().size}"
    }

    private suspend fun telegramSend(args: String): String {
        if (!prefs.telegramEnabled) return "[howard] Telegram not configured"
        return try {
            TelegramConnector(prefs).sendMessage(args)
            "[howard] Telegram message sent"
        } catch (e: Exception) {
            "[howard] Telegram error: ${e.message}"
        }
    }

    private fun runShell(cmd: String): String {
        return try {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .directory(workspaceDir)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output.ifBlank { "[howard] Command completed (no output)" }
        } catch (e: Exception) {
            "[howard] Shell error: ${e.message}"
        }
    }

    private fun resolveDir(path: String): String =
        path.replace("~", context.filesDir.absolutePath)
}
