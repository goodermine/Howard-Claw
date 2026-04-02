package au.howardagent.agent

import android.content.Context
import android.util.Log
import au.howardagent.HowardApplication
import au.howardagent.data.TaskEntity

class CommandDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "CommandDispatcher"
        val CMD_PATTERN = Regex("""\[CMD:\s*([^\]]+)\]""")
    }

    private val executor = ToolExecutor(context)
    private val db       = HowardApplication.instance.database

    suspend fun dispatch(cmdString: String): String {
        val parts    = cmdString.trim().split(Regex("\\s+"), limit = 2)
        val toolName = parts[0].lowercase()
        val args     = if (parts.size > 1) parts[1] else ""

        Log.i(TAG, "Dispatching: tool=$toolName args=$args")

        val result = try {
            executor.execute(toolName, args)
        } catch (e: Exception) {
            "[howard] Error: ${e.message}"
        }

        db.taskDao().insert(TaskEntity(
            task    = cmdString,
            result  = result,
            tool    = toolName,
            success = !result.startsWith("[howard] Error")
        ))

        return result
    }

    fun extractCommands(text: String): List<String> =
        CMD_PATTERN.findAll(text).map { it.groupValues[1] }.toList()
}
