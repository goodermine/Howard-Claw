import { useState } from "react";

const ACCENT="#ff6b35",GREEN="#00ff88",BLUE="#38bdf8",PURPLE="#a78bfa",YELLOW="#facc15",PINK="#f472b6";
const LANG_COLORS:Record<string,string>={kotlin:"#a78bfa",cpp:"#ff6b35",xml:"#facc15",bash:"#86efac",toml:"#38bdf8",pro:"#94a3b8",py:"#86efac"};

const files = [
// ═══════════════════════════════════════════════════════════════════════════
// CHAT LAYER
// ═══════════════════════════════════════════════════════════════════════════
{id:"chat_vm",group:"Chat Layer",name:"ui/chat/ChatViewModel.kt",lang:"kotlin",color:GREEN,desc:"Owns inference coroutine. Streams tokens to UI. Intercepts [CMD:] tokens and dispatches tools. Manages conversation history.",
code:`package au.howardagent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.howardagent.HowardApplication
import au.howardagent.agent.CommandDispatcher
import au.howardagent.agent.PromptBuilder
import au.howardagent.data.MessageEntity
import au.howardagent.engine.EngineRouter
import au.howardagent.download.ModelRegistry
import au.howardagent.download.ModelDownloader
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,       // "user" | "assistant" | "tool"
    val content: String,
    val model: String = "",
    val isStreaming: Boolean = false,
    val tokensPerSec: Float = 0f
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val activeProvider: String = "local",
    val gatewayStatus: String = "starting",
    val error: String? = null
)

class ChatViewModel : ViewModel() {

    private val app        = HowardApplication.instance
    private val prefs      = app.securePrefs
    private val db         = app.database
    private val downloader = ModelDownloader(app)
    private val dispatcher = CommandDispatcher(app)
    private val router     = EngineRouter(prefs)
    private val conversationId = UUID.randomUUID().toString()

    private val _state = MutableStateFlow(ChatUiState(
        activeProvider = prefs.activeProvider
    ))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    // ── Load history ──────────────────────────────────────────────────────
    init {
        viewModelScope.launch {
            db.messageDao().getMessages(conversationId).collect { rows ->
                val msgs = rows.map { ChatMessage(role = it.role, content = it.content, model = it.model) }
                _state.update { it.copy(messages = msgs) }
            }
        }
    }

    // ── Send message ──────────────────────────────────────────────────────
    fun send(userText: String) {
        if (userText.isBlank() || _state.value.isGenerating) return

        val userMsg = ChatMessage(role = "user", content = userText)
        appendMessage(userMsg)
        persistMessage(userMsg)

        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, error = null) }

            // Placeholder streaming message
            val streamId = UUID.randomUUID().toString()
            val streamMsg = ChatMessage(id = streamId, role = "assistant", content = "", isStreaming = true)
            appendMessage(streamMsg)

            val buffer  = StringBuilder()
            val startMs = System.currentTimeMillis()
            var tokenCount = 0

            try {
                val localPath = getActiveModelPath()
                val engine = router.getEngine(localPath)
                val systemPrompt = PromptBuilder.build(
                    history = _state.value.messages.dropLast(1)
                )

                engine.infer(
                    prompt       = userText,
                    systemPrompt = systemPrompt,
                    onToken      = { token ->
                        buffer.append(token)
                        tokenCount++

                        // Check for complete CMD token
                        val cmdMatch = Regex("""\[CMD:\s*([^\]]+)\]""")
                            .find(buffer)

                        if (cmdMatch != null) {
                            val cmdStr = cmdMatch.groupValues[1]
                            val preCmd = buffer.substring(0, cmdMatch.range.first)
                            buffer.delete(0, cmdMatch.range.last + 1)

                            // Update message with pre-cmd text
                            updateStreamingMessage(streamId, preCmd)

                            // Dispatch command
                            viewModelScope.launch {
                                val result = dispatcher.dispatch(cmdStr)
                                val toolMsg = ChatMessage(
                                    role    = "tool",
                                    content = result,
                                    model   = "howard-tools"
                                )
                                appendMessage(toolMsg)
                                persistMessage(toolMsg)
                            }
                        } else {
                            val elapsed = (System.currentTimeMillis() - startMs) / 1000f
                            val tps     = if (elapsed > 0) tokenCount / elapsed else 0f
                            updateStreamingMessage(streamId, buffer.toString(), tps)
                        }
                    },
                    onComplete = {
                        val elapsed = (System.currentTimeMillis() - startMs) / 1000f
                        val tps     = if (elapsed > 0) tokenCount / elapsed else 0f
                        val finalMsg = ChatMessage(
                            id      = streamId,
                            role    = "assistant",
                            content = buffer.toString(),
                            model   = prefs.activeProvider,
                            tokensPerSec = tps
                        )
                        replaceMessage(streamId, finalMsg)
                        persistMessage(finalMsg)
                        _state.update { it.copy(isGenerating = false) }
                    },
                    onError = { err ->
                        removeMessage(streamId)
                        _state.update { it.copy(isGenerating = false, error = err) }
                    }
                )
            } catch (e: Exception) {
                removeMessage(streamId)
                _state.update { it.copy(isGenerating = false, error = e.message) }
            }
        }
    }

    fun stopGeneration() {
        router.stopCurrent()
        _state.update { it.copy(isGenerating = false) }
    }

    fun switchProvider(provider: String) {
        prefs.activeProvider = provider
        _state.update { it.copy(activeProvider = provider) }
    }

    fun clearConversation() {
        viewModelScope.launch {
            db.messageDao().clearConversation(conversationId)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private fun appendMessage(msg: ChatMessage) {
        _state.update { it.copy(messages = it.messages + msg) }
    }

    private fun updateStreamingMessage(id: String, content: String, tps: Float = 0f) {
        _state.update { s ->
            s.copy(messages = s.messages.map {
                if (it.id == id) it.copy(content = content, tokensPerSec = tps) else it
            })
        }
    }

    private fun replaceMessage(id: String, new: ChatMessage) {
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == id) new else it })
        }
    }

    private fun removeMessage(id: String) {
        _state.update { it.copy(messages = it.messages.filter { m -> m.id != id }) }
    }

    private fun persistMessage(msg: ChatMessage) {
        viewModelScope.launch {
            db.messageDao().insert(MessageEntity(
                conversationId = conversationId,
                role           = msg.role,
                content        = msg.content,
                model          = msg.model
            ))
        }
    }

    private suspend fun getActiveModelPath(): String? {
        val active = db.modelDao().getActiveModel() ?: return null
        val file   = downloader.modelFile(
            ModelRegistry.getById(active.id) ?: return null
        )
        return if (file.exists()) file.absolutePath else null
    }

    override fun onCleared() {
        router.releaseAll()
        super.onCleared()
    }
}
`},

{id:"chat_screen",group:"Chat Layer",name:"ui/chat/ChatScreen.kt",lang:"kotlin",color:GREEN,desc:"Main chat UI — LazyColumn messages, streaming token display, model switcher chips, input bar, stop button",
code:`package au.howardagent.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ChatScreen(
    onNavigateToTools: () -> Unit,
    onNavigateToSettings: () -> Unit,
    vm: ChatViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    // Auto-scroll to latest message
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            HowardTopBar(
                gatewayStatus        = state.gatewayStatus,
                onNavigateToTools    = onNavigateToTools,
                onNavigateToSettings = onNavigateToSettings,
                onClear              = vm::clearConversation
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Model switcher ────────────────────────────────────────────
            ModelSwitcher(
                active   = state.activeProvider,
                onSwitch = vm::switchProvider,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )

            // ── Message list ──────────────────────────────────────────────
            LazyColumn(
                state           = listState,
                modifier        = Modifier.weight(1f),
                contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item { EmptyState() }
                }
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                if (state.isGenerating && state.messages.lastOrNull()?.isStreaming != true) {
                    item { ThinkingIndicator() }
                }
            }

            // ── Error banner ──────────────────────────────────────────────
            AnimatedVisibility(visible = state.error != null) {
                state.error?.let { err ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(err, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── Input bar ─────────────────────────────────────────────────
            InputBar(
                text        = inputText,
                onTextChange = { inputText = it },
                isGenerating = state.isGenerating,
                onSend = {
                    if (inputText.isNotBlank()) {
                        vm.send(inputText.trim())
                        inputText = ""
                    }
                },
                onStop = vm::stopGeneration,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowardTopBar(
    gatewayStatus: String,
    onNavigateToTools: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onClear: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Howard", fontWeight = FontWeight.Bold)
                val (dotColor, statusText) = when (gatewayStatus) {
                    "online"   -> Color(0xFF00C853) to "online"
                    "starting" -> Color(0xFFFFAB00) to "starting"
                    else       -> Color(0xFFD50000) to "offline"
                }
                Surface(shape = RoundedCornerShape(50), color = dotColor.copy(alpha = 0.15f)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(Modifier.size(6.dp).background(dotColor, RoundedCornerShape(50)))
                        Text(statusText, fontSize = 10.sp, color = dotColor)
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onNavigateToTools) {
                Icon(Icons.Default.Build, "Tools")
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "More")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Clear conversation") }, onClick = { onClear(); menuExpanded = false }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { onNavigateToSettings(); menuExpanded = false }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                }
            }
        }
    )
}

@Composable
fun ModelSwitcher(active: String, onSwitch: (String) -> Unit, modifier: Modifier = Modifier) {
    val providers = listOf("local","openai","anthropic","gemini","kimi","openrouter")
    val labels    = mapOf("local" to "Local 🔒","openai" to "GPT","anthropic" to "Claude","gemini" to "Gemini","kimi" to "Kimi","openrouter" to "OpenRouter")

    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        providers.forEach { p ->
            val isActive = p == active
            FilterChip(
                selected = isActive,
                onClick  = { onSwitch(p) },
                label    = { Text(labels[p] ?: p, fontSize = 12.sp) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor     = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    when (msg.role) {
        "user" -> UserBubble(msg)
        "assistant" -> AssistantBubble(msg)
        "tool" -> ToolResultBubble(msg)
    }
}

@Composable
fun UserBubble(msg: ChatMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape  = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
            color  = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text     = msg.content,
                modifier = Modifier.padding(12.dp, 10.dp),
                color    = MaterialTheme.colorScheme.onPrimary,
                style    = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun AssistantBubble(msg: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape  = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            color  = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp, 12.dp)) {
                if (msg.content.isBlank() && msg.isStreaming) {
                    // Typing dots
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { i ->
                            Box(Modifier.size(6.dp).background(
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                RoundedCornerShape(50)
                            ))
                        }
                    }
                } else {
                    Text(
                        text  = msg.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (msg.tokensPerSec > 0f && !msg.isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${"%.1f".format(msg.tokensPerSec)} tok/s · ${msg.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ToolResultBubble(msg: ChatMessage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(8.dp),
        color    = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(modifier = Modifier.padding(10.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Terminal, null, modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.secondary)
            Text(msg.content, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text("Howard is thinking…", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🤖", fontSize = 48.sp)
        Text("Howard is ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Ask me anything or give me a task", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, tonalElevation = 4.dp) {
        Row(
            modifier = Modifier.padding(12.dp, 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Message Howard…") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(20.dp)
            )
            if (isGenerating) {
                FilledTonalIconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = text.isNotBlank()) {
                    Icon(Icons.Default.Send, "Send")
                }
            }
        }
    }
}
`},

// ═══════════════════════════════════════════════════════════════════════════
// AGENT LAYER
// ═══════════════════════════════════════════════════════════════════════════
{id:"system_prompts",group:"Agent Layer",name:"agent/SystemPrompts.kt",lang:"kotlin",color:GREEN,desc:"Howard's base system prompt + full [CMD:] tool grammar. Injected into every inference call.",
code:`package au.howardagent.agent

object SystemPrompts {

    const val HOWARD_BASE = """
You are Howard, a mobile-first AI agent running natively on Android.
You are helpful, concise, and action-oriented.

## Tool Grammar
When you need to perform a real action, emit a command token on its own line in EXACTLY this format:
  [CMD: tool_name arg1 arg2]

## Available Tools
  [CMD: github_sync <repo_url> <local_dir>]
    Clone or pull a git repository to the device.
    Example: [CMD: github_sync https://github.com/user/vox ~/workspace/vox]

  [CMD: file_organizer <source_dir>]
    Sort files in a directory into extension-named subdirectories.
    Example: [CMD: file_organizer ~/storage/downloads]

  [CMD: web_component_gen <ComponentName> <output_dir>]
    Scaffold a React JSX component file.
    Example: [CMD: web_component_gen UserCard ~/workspace/vox/components]

  [CMD: telegram_send <message>]
    Send a message to the configured Telegram channel.
    Example: [CMD: telegram_send Task complete — 3 files organised]

  [CMD: shell <bash_command>]
    Execute an arbitrary shell command. Use with care.
    Example: [CMD: shell ls -la ~/workspace]

## Rules
- Always explain what you are about to do BEFORE emitting a CMD token.
- Only emit one CMD token per response.
- After a CMD executes, a [howard-exec] result will appear — acknowledge it briefly.
- If a task doesn't need a tool, just answer in plain text.
- Keep responses concise. You are on mobile hardware.
- Never emit a CMD token unless you are certain the action is correct and safe.
"""

    const val OPENCLAW_BRIDGE = """
You are Howard's OpenClaw bridge.
The user is connected via Telegram.
Keep responses under 300 words.
Format tool results as clean plain text.
"""
}
`},

{id:"prompt_builder",group:"Agent Layer",name:"agent/PromptBuilder.kt",lang:"kotlin",color:GREEN,desc:"Constructs the full system prompt with memory context injected from recent task history",
code:`package au.howardagent.agent

import au.howardagent.ui.chat.ChatMessage

object PromptBuilder {

    /**
     * Build the system prompt.
     * Injects last N messages as memory context so Howard knows what just happened.
     */
    fun build(
        history: List<ChatMessage> = emptyList(),
        maxHistoryMessages: Int = 8
    ): String {
        val base = SystemPrompts.HOWARD_BASE.trimIndent()

        if (history.isEmpty()) return base

        val recentHistory = history
            .takeLast(maxHistoryMessages)
            .filter { it.role != "tool" }   // Don't inflate prompt with tool logs
            .joinToString("\n") { msg ->
                when (msg.role) {
                    "user"      -> "User: \${msg.content.take(200)}"
                    "assistant" -> "Howard: \${msg.content.take(200)}"
                    else        -> ""
                }
            }
            .trim()

        return if (recentHistory.isNotEmpty()) {
            "\$base\n\n## Recent Context\n\$recentHistory"
        } else {
            base
        }
    }
}
`},

{id:"command_dispatcher",group:"Agent Layer",name:"agent/CommandDispatcher.kt",lang:"kotlin",color:GREEN,desc:"Regex-matches [CMD: tool_name args] in token stream. Routes to ToolExecutor. Logs every dispatch to Room DB.",
code:`package au.howardagent.agent

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

    /**
     * Parse a raw CMD string (content inside [CMD: ...]) and execute.
     * Returns a formatted result string to inject back into the conversation.
     */
    suspend fun dispatch(cmdString: String): String {
        val parts    = cmdString.trim().split(Regex("\\s+"), limit = 2)
        val toolName = parts[0].lowercase()
        val args     = if (parts.size > 1) parts[1] else ""

        Log.i(TAG, "Dispatching: tool=\$toolName args=\$args")

        val result = try {
            executor.execute(toolName, args)
        } catch (e: Exception) {
            "[howard] Error: \${e.message}"
        }

        // Log to DB
        db.taskDao().insert(TaskEntity(
            task    = cmdString,
            result  = result,
            tool    = toolName,
            success = !result.startsWith("[howard] Error")
        ))

        return result
    }

    /** Extract all CMD tokens from a completed response string */
    fun extractCommands(text: String): List<String> =
        CMD_PATTERN.findAll(text).map { it.groupValues[1] }.toList()
}
`},

{id:"tool_executor",group:"Agent Layer",name:"agent/ToolExecutor.kt",lang:"kotlin",color:GREEN,desc:"Executes mapped tools: github_sync, file_organizer, web_component_gen, telegram_send, shell passthrough",
code:`package au.howardagent.agent

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
        Log.i(TAG, "Execute: \$tool '\$args'")
        when (tool) {
            "github_sync"       -> githubSync(args)
            "file_organizer"    -> fileOrganizer(args)
            "web_component_gen" -> webComponentGen(args)
            "telegram_send"     -> telegramSend(args)
            "shell"             -> shell(args)
            else                -> "[howard] Unknown tool: \$tool"
        }
    }

    // ── github_sync ───────────────────────────────────────────────────────
    private fun githubSync(args: String): String {
        val parts = args.split(" ", limit = 2)
        if (parts.size < 2) return "[howard] Usage: github_sync <repo_url> <local_dir>"
        val repoUrl   = parts[0]
        val targetDir = resolveDir(parts[1])

        return if (File(targetDir, ".git").exists()) {
            shell("git -C \$targetDir pull --rebase 2>&1")
                .let { "[howard] Pulled → \$targetDir\n\$it" }
        } else {
            File(targetDir).parentFile?.mkdirs()
            shell("git clone --depth=1 \$repoUrl \$targetDir 2>&1")
                .let { "[howard] Cloned \$repoUrl → \$targetDir\n\$it" }
        }
    }

    // ── file_organizer ────────────────────────────────────────────────────
    private fun fileOrganizer(args: String): String {
        val srcPath = resolveDir(args.trim().ifBlank { "~/storage/downloads" })
        val src     = File(srcPath)
        if (!src.exists()) return "[howard] Directory not found: \$srcPath"

        var moved = 0
        var skipped = 0
        src.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val ext  = file.extension.lowercase().ifBlank { "unknown" }
            val dest = File(src, ext).also { it.mkdirs() }
            if (file.renameTo(File(dest, file.name))) moved++ else skipped++
        }
        return "[howard] Organised \$srcPath\n[howard] Moved: \$moved · Skipped: \$skipped"
    }

    // ── web_component_gen ─────────────────────────────────────────────────
    private fun webComponentGen(args: String): String {
        val parts    = args.split(" ", limit = 2)
        val name     = parts[0].trim().replaceFirstChar { it.uppercase() }
        val outDir   = if (parts.size > 1) resolveDir(parts[1]) else "\$workspaceDir/components"
        File(outDir).mkdirs()
        val outFile  = File(outDir, "\$name.jsx")

        outFile.writeText("""
import { useState } from "react";

/**
 * \$name
 * Generated by Howard · \${java.time.LocalDate.now()}
 */
export default function \$name({ className = "" }) {
  const [active, setActive] = useState(false);

  return (
    <div className={\`howard-component \${className}\`}>
      <button onClick={() => setActive(!active)}>
        {active ? "Active" : "Inactive"}
      </button>
    </div>
  );
}
""".trimIndent())

        return "[howard] Component created: \${outFile.absolutePath}\n[howard] Lines: \${outFile.readLines().size}"
    }

    // ── telegram_send ─────────────────────────────────────────────────────
    private suspend fun telegramSend(args: String): String {
        if (!prefs.telegramEnabled) return "[howard] Telegram not configured"
        return try {
            TelegramConnector(prefs).sendMessage(args)
            "[howard] Telegram message sent"
        } catch (e: Exception) {
            "[howard] Telegram error: \${e.message}"
        }
    }

    // ── shell passthrough ─────────────────────────────────────────────────
    private fun shell(cmd: String): String {
        return try {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .directory(workspaceDir)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output.ifBlank { "[howard] Command completed (no output)" }
        } catch (e: Exception) {
            "[howard] Shell error: \${e.message}"
        }
    }

    private fun resolveDir(path: String): String =
        path.replace("~", context.filesDir.absolutePath)
}
`},

// ═══════════════════════════════════════════════════════════════════════════
// CONNECTORS
// ═══════════════════════════════════════════════════════════════════════════
{id:"telegram_connector",group:"Connectors",name:"connectors/TelegramConnector.kt",lang:"kotlin",color:BLUE,desc:"Telegram Bot API: sendMessage, startPolling for inbound tasks, formatResponse. Long-polling loop in coroutine.",
code:`package au.howardagent.connectors

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

    // ── Send message ───────────────────────────────────────────────────────
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
                .url("\$BASE\${token}/sendMessage")
                .post(body)
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    Log.i(TAG, "sendMessage: \${resp.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed: \${e.message}")
                throw e
            }
        }

    // ── Test connection ────────────────────────────────────────────────────
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.failure(Exception("No bot token"))
        try {
            val req = Request.Builder().url("\$BASE\${token}/getMe").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json     = JSONObject(resp.body!!.string())
                    val username = json.getJSONObject("result").getString("username")
                    Result.success("@\$username")
                } else {
                    Result.failure(Exception("HTTP \${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Long-polling for inbound tasks ─────────────────────────────────────
    fun pollUpdates(lastUpdateId: Long = 0L): Flow<TelegramMessage> = flow {
        var offset = lastUpdateId + 1
        while (currentCoroutineContext().isActive) {
            try {
                val url = "\$BASE\${token}/getUpdates?offset=\$offset&timeout=30"
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
                Log.e(TAG, "Poll error: \${e.message}")
                delay(10_000)
            }
        }
    }
}
`},

{id:"openclaw_connector",group:"Connectors",name:"connectors/OpenClawConnector.kt",lang:"kotlin",color:BLUE,desc:"HTTP IPC to the local OpenClaw gateway on ws://127.0.0.1:18789. Sends tasks, receives streamed responses.",
code:`package au.howardagent.connectors

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
        private val HEALTH_PATHS   = listOf("/health", "/api/health", "/healthz", "/")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class OnlineCheckResult(
        val online: Boolean,
        val checkedPath: String? = null,
        val error: String? = null
    )

    // ── Health check ───────────────────────────────────────────────────────
    suspend fun checkOnlineDetailed(): OnlineCheckResult = withContext(Dispatchers.IO) {
        var lastErr: String? = null

        for (path in HEALTH_PATHS) {
            try {
                val req = Request.Builder().url("\$BASE_URL\$path").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return@withContext OnlineCheckResult(
                            online = true,
                            checkedPath = path
                        )
                    }
                    lastErr = "HTTP \${resp.code} on \$path"
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }

        OnlineCheckResult(online = false, error = lastErr)
    }

    suspend fun isOnline(): Boolean = checkOnlineDetailed().online

    // ── Send task to OpenClaw gateway ──────────────────────────────────────
    suspend fun sendTask(task: String): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("message", task).toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("\$BASE_URL/api/message")
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(resp.body?.string() ?: "")
                } else {
                    Result.failure(Exception("HTTP \${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendTask failed: \${e.message}")
            Result.failure(e)
        }
    }

    // ── WebSocket streaming (for real-time token delivery) ─────────────────
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
`},

// ═══════════════════════════════════════════════════════════════════════════
// REMAINING ONBOARDING STEPS
// ═══════════════════════════════════════════════════════════════════════════
{id:"step3_openclaw",group:"Onboarding UI",name:"ui/onboarding/Step3_OpenClaw.kt",lang:"kotlin",color:BLUE,desc:"Onboarding step 3 — shows gateway extraction progress, verifies OpenClaw is running, marks step complete",
code:`package au.howardagent.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.howardagent.connectors.OpenClawConnector
import kotlinx.coroutines.delay

@Composable
fun OpenClawStep() {
    val connector = remember { OpenClawConnector() }
    var status    by remember { mutableStateOf<StepStatus>(StepStatus.CHECKING) }
    var retries   by remember { mutableIntStateOf(0) }
    var detail    by remember { mutableStateOf("Waiting for gateway readiness…") }

    LaunchedEffect(retries) {
        status = StepStatus.CHECKING
        detail = "Waiting for gateway readiness…"

        // Give extraction/startup phase a chance to settle.
        delay(1500)

        // Exponential backoff: 1s, 2s, 4s, ... up to 8s (about 60s total window).
        var backoffMs = 1000L
        repeat(10) { attempt ->
            val result = connector.checkOnlineDetailed()
            if (result.online) {
                status = StepStatus.DONE
                detail = "Gateway health check passed on \${result.checkedPath ?: "/health"}"
                return@LaunchedEffect
            }

            detail = "Attempt \${attempt + 1}/10 failed\${if (result.error != null) ": \${result.error}" else ""}"
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(8000L)
        }

        status = StepStatus.ERROR
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        Text("⚡", style = MaterialTheme.typography.displaySmall)
        Text("OpenClaw Gateway", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Howard uses OpenClaw to orchestrate tools and connect to Telegram. " +
            "The gateway is bundled inside the app — no separate install needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                StatusRow(
                    label  = "Extracting Node.js runtime",
                    status = if (status == StepStatus.CHECKING) StepStatus.LOADING else StepStatus.DONE
                )
                StatusRow(
                    label  = "Extracting OpenClaw package",
                    status = when (status) {
                        StepStatus.CHECKING -> StepStatus.LOADING
                        else -> StepStatus.DONE
                    }
                )
                StatusRow(
                    label  = "Starting gateway on port 18789",
                    status = status
                )
            }
        }

        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (status == StepStatus.ERROR) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )

        AnimatedVisibility(visible = status == StepStatus.DONE) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
                    Spacer(Modifier.width(8.dp))
                    Text("Gateway online · ws://127.0.0.1:18789",
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1B5E20))
                }
            }
        }

        AnimatedVisibility(visible = status == StepStatus.ERROR) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Gateway not responding after extended checks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { retries++ }) { Text("Retry") }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, status: StepStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (status) {
            StepStatus.LOADING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            StepStatus.DONE    -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
            StepStatus.ERROR   -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            StepStatus.CHECKING -> Box(Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (status == StepStatus.DONE) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

enum class StepStatus { CHECKING, LOADING, DONE, ERROR }
`},

{id:"step4_telegram",group:"Onboarding UI",name:"ui/onboarding/Step4_Telegram.kt",lang:"kotlin",color:BLUE,desc:"Onboarding step 4 — Telegram bot token + channel ID entry, test connection, enable polling toggle",
code:`package au.howardagent.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.howardagent.HowardApplication
import au.howardagent.connectors.TelegramConnector

@Composable
fun ConnectStep(onComplete: () -> Unit) {
    val prefs   = HowardApplication.instance.securePrefs
    var token   by remember { mutableStateOf(prefs.telegramBotToken) }
    var channel by remember { mutableStateOf(prefs.telegramChannelId) }
    var enabled by remember { mutableStateOf(prefs.telegramEnabled) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("📱", style = MaterialTheme.typography.displaySmall)
        Text("Telegram", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Connect Howard to Telegram so you can send tasks from anywhere. Optional — you can skip and use the chat screen instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // How to get a bot token
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("How to create a bot:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "1. Open Telegram and search @BotFather",
                    "2. Send /newbot and follow the steps",
                    "3. Copy the token it gives you",
                    "4. Paste it below"
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        OutlinedTextField(
            value        = token,
            onValueChange = {
                token             = it
                prefs.telegramBotToken = it
            },
            label        = { Text("Bot Token") },
            placeholder  = { Text("1234567890:ABC-...") },
            modifier     = Modifier.fillMaxWidth(),
            singleLine   = true,
            leadingIcon  = { if (token.isNotBlank()) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32)) }
        )

        OutlinedTextField(
            value        = channel,
            onValueChange = {
                channel                = it
                prefs.telegramChannelId = it
            },
            label       = { Text("Your Chat ID or Channel ID") },
            placeholder = { Text("-1001234567890 or @channelname") },
            modifier    = Modifier.fillMaxWidth(),
            singleLine  = true
        )

        // Test connection button
        Button(
            onClick = {
                testing = true; testResult = null
                scope.launch {
                    val result = TelegramConnector(prefs).testConnection()
                    testResult = result.getOrElse { "Error: \${it.message}" }
                    testing = false
                    if (result.isSuccess) {
                        TelegramConnector(prefs).sendMessage(
                            "✅ Howard is connected and ready. Send me a task!"
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled  = token.isNotBlank() && !testing
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (testing) "Testing…" else "Test Connection")
        }

        testResult?.let { result ->
            val isSuccess = result.startsWith("@")
            Card(colors = CardDefaults.cardColors(
                containerColor = if (isSuccess) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer
            )) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        null,
                        tint = if (isSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSuccess) "Connected as \$result" else result,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Enable inbound polling
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Receive tasks via Telegram", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Howard will poll for incoming messages and act on them",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked         = enabled,
                onCheckedChange = { enabled = it; prefs.telegramEnabled = it }
            )
        }
    }
}
`},

// ═══════════════════════════════════════════════════════════════════════════
// TOOLS SCREEN
// ═══════════════════════════════════════════════════════════════════════════
{id:"tools_screen",group:"Tools & Settings",name:"ui/tools/ToolsScreen.kt",lang:"kotlin",color:PURPLE,desc:"Tools dashboard — lists each tool with last-run status, manual trigger button, live execution log",
code:`package au.howardagent.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.howardagent.HowardApplication

data class ToolUi(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val exampleCmd: String
)

private val TOOLS = listOf(
    ToolUi("github_sync",       "GitHub Sync",         "Clone or pull any git repository", "🔄", "github_sync https://github.com/user/repo ~/workspace/repo"),
    ToolUi("file_organizer",    "File Organiser",      "Sort files into extension folders", "📂", "file_organizer ~/storage/downloads"),
    ToolUi("web_component_gen", "Component Generator", "Scaffold a React JSX component",   "⚛",  "web_component_gen UserCard ~/workspace/vox/components"),
    ToolUi("telegram_send",     "Telegram Send",       "Send message to Telegram channel", "✈️", "telegram_send Hello from Howard"),
    ToolUi("shell",             "Shell",               "Execute any bash command",          "💻", "shell ls -la ~/workspace"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val db     = HowardApplication.instance.database
    val tasks  by db.taskDao().getRecentTasks(20).collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Available Tools", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            }

            items(TOOLS, key = { it.id }) { tool ->
                val lastRun = tasks.firstOrNull { t -> t.tool == tool.id }
                ToolCard(tool = tool, lastRun = lastRun?.task, lastResult = lastRun?.result)
            }

            if (tasks.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Recent Executions", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
                items(tasks.take(10), key = { it.id }) { task ->
                    TaskLogRow(task = task.task, result = task.result, success = task.success)
                }
            }
        }
    }
}

@Composable
fun ToolCard(tool: ToolUi, lastRun: String?, lastResult: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tool.emoji, fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tool.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(tool.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    tool.exampleCmd,
                    modifier    = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style       = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color       = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (lastRun != null) {
                Spacer(Modifier.height(8.dp))
                Text("Last: \$lastRun", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TaskLogRow(task: String, result: String, success: Boolean) {
    Surface(
        shape  = MaterialTheme.shapes.small,
        color  = if (success) Color(0xFF0A1A0A) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                null,
                tint     = if (success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp).padding(top = 2.dp)
            )
            Column {
                Text(task,   style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                Text(result, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
`},

// ═══════════════════════════════════════════════════════════════════════════
// SETTINGS SCREENS
// ═══════════════════════════════════════════════════════════════════════════
{id:"settings_screen",group:"Tools & Settings",name:"ui/settings/SettingsScreen.kt",lang:"kotlin",color:YELLOW,desc:"Settings hub — navigation to Models, API Keys, Telegram, OpenClaw, About. Shows gateway status + device info.",
code:`package au.howardagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.howardagent.HowardApplication
import au.howardagent.BuildConfig
import au.howardagent.connectors.OpenClawConnector
import au.howardagent.download.DeviceDetector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs   = HowardApplication.instance.securePrefs
    val device  = remember { DeviceDetector.profile(context) }
    var subScreen by remember { mutableStateOf<String?>(null) }
    var gatewayOnline by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        gatewayOnline = OpenClawConnector().isOnline()
    }

    when (subScreen) {
        "models"   -> ModelsScreen    { subScreen = null }
        "apikeys"  -> ApiKeysScreen   { subScreen = null }
        "telegram" -> TelegramSettingsScreen { subScreen = null }
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier       = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // Device card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Device", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                InfoChip("RAM", "\${device.ramGb} GB")
                                InfoChip("CPU", "\${device.cpuCores} cores")
                                InfoChip("AI", device.suitability.label)
                            }
                        }
                    }
                }

                item {
                    // Gateway status card
                    Card(modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (gatewayOnline) Color(0xFF0A1A0A) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (gatewayOnline) Icons.Default.CheckCircle else Icons.Default.Warning,
                                null,
                                tint = if (gatewayOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("OpenClaw Gateway", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    if (gatewayOnline) "Online · ws://127.0.0.1:18789" else "Offline — restart app",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(4.dp)) }
                item { Text("Configuration", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }

                item { SettingsRow(Icons.Default.SmartToy,    "Models",    "Manage local models",           { subScreen = "models" }) }
                item { SettingsRow(Icons.Default.Key,          "API Keys",  "Cloud provider keys",           { subScreen = "apikeys" }) }
                item { SettingsRow(Icons.Default.Send,         "Telegram",  "Bot token & channel",           { subScreen = "telegram" }) }

                item { Spacer(Modifier.height(4.dp)) }
                item { Text("About", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            InfoRow("Version", BuildConfig.VERSION_NAME)
                            InfoRow("OpenClaw", if (gatewayOnline) "Online" else "Offline")
                            InfoRow("Local LLM", if (prefs.activeProvider == "local") "Active" else "Standby")
                            InfoRow("Telegram", if (prefs.telegramEnabled) "Enabled" else "Disabled")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title,    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
`},

// ═══════════════════════════════════════════════════════════════════════════
// INFERENCE SERVICE (background)
// ═══════════════════════════════════════════════════════════════════════════
{id:"inference_service",group:"Services",name:"service/InferenceService.kt",lang:"kotlin",color:ACCENT,desc:"Foreground service that owns the LocalEngine instance. Accepts tasks from Telegram polling. Routes to ToolExecutor. Broadcasts results.",
code:`package au.howardagent.service

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

        // Start Telegram polling if enabled
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

    // ── Task Handler ──────────────────────────────────────────────────────
    private suspend fun handleTask(task: String, replyTo: Long?) {
        updateNotification("Working: \${task.take(50)}…")
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
                    // Dispatch CMD tokens inline
                    val m = Regex("""\[CMD:\s*([^\]]+)\]""").find(buffer)
                    if (m != null) {
                        val cmd = m.groupValues[1]
                        buffer.delete(0, m.range.last + 1)
                        scope.launch {
                            val result = dispatcher.dispatch(cmd)
                            buffer.append("\n[Result] \$result\n")
                        }
                    }
                },
                onComplete = {
                    val response = buffer.toString().trim()
                    Log.i(TAG, "Task complete: \${response.take(100)}")
                    if (replyTo != null && prefs.telegramEnabled) {
                        scope.launch {
                            TelegramConnector(prefs).sendMessage(response, replyTo.toString())
                        }
                    }
                    updateNotification("Last task complete")
                },
                onError = { err ->
                    Log.e(TAG, "Inference error: \$err")
                    updateNotification("Error: \$err")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleTask failed: \${e.message}")
        }
    }

    // ── Telegram polling loop ─────────────────────────────────────────────
    private fun startTelegramPolling() {
        scope.launch {
            Log.i(TAG, "Starting Telegram polling")
            TelegramConnector(prefs).pollUpdates().collect { msg ->
                Log.i(TAG, "Telegram task from @\${msg.fromUsername}: \${msg.text}")
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
`},

// ═══════════════════════════════════════════════════════════════════════════
// VERSION CATALOG + PROGUARD
// ═══════════════════════════════════════════════════════════════════════════
{id:"libs_versions",group:"Build Config",name:"gradle/libs.versions.toml",lang:"toml",color:YELLOW,desc:"Complete version catalog — all dependency versions in one place",
code:`[versions]
agp              = "8.7.3"
kotlin           = "2.0.21"
ksp              = "2.0.21-1.0.28"
compose-bom      = "2024.12.01"
activity-compose = "1.9.3"
navigation       = "2.8.5"
lifecycle        = "2.8.7"
room             = "2.6.1"
work             = "2.10.0"
security-crypto  = "1.1.0-alpha06"
retrofit         = "2.11.0"
okhttp           = "4.12.0"
coroutines       = "1.9.0"
core-ktx         = "1.15.0"
appcompat        = "1.7.0"
material3        = "1.3.1"

[libraries]
# Compose
androidx-compose-bom              = { group = "androidx.compose",       name = "compose-bom",              version.ref = "compose-bom" }
androidx-ui                       = { group = "androidx.compose.ui",    name = "ui" }
androidx-ui-graphics              = { group = "androidx.compose.ui",    name = "ui-graphics" }
androidx-ui-tooling-preview       = { group = "androidx.compose.ui",    name = "ui-tooling-preview" }
androidx-material3                = { group = "androidx.compose.material3", name = "material3",            version.ref = "material3" }
androidx-activity-compose         = { group = "androidx.activity",      name = "activity-compose",         version.ref = "activity-compose" }
androidx-navigation-compose       = { group = "androidx.navigation",    name = "navigation-compose",       version.ref = "navigation" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle",  name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle",   name = "lifecycle-runtime-compose", version.ref = "lifecycle" }

# Room
androidx-room-runtime  = { group = "androidx.room", name = "room-runtime",  version.ref = "room" }
androidx-room-ktx      = { group = "androidx.room", name = "room-ktx",      version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler",  version.ref = "room" }

# WorkManager
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }

# Security
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "security-crypto" }

# Networking
retrofit                  = { group = "com.squareup.retrofit2", name = "retrofit",          version.ref = "retrofit" }
retrofit-converter-gson   = { group = "com.squareup.retrofit2", name = "converter-gson",    version.ref = "retrofit" }
okhttp                    = { group = "com.squareup.okhttp3",   name = "okhttp",            version.ref = "okhttp" }
okhttp-logging            = { group = "com.squareup.okhttp3",   name = "logging-interceptor", version.ref = "okhttp" }

# Coroutines
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

# Core
androidx-core-ktx  = { group = "androidx.core",     name = "core-ktx",  version.ref = "core-ktx"  }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }

# Testing
junit                  = { group = "junit",          name = "junit",           version = "4.13.2" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit",       version = "1.2.1"  }

[plugins]
android-application = { id = "com.android.application",        version.ref = "agp"    }
kotlin-android      = { id = "org.jetbrains.kotlin.android",   version.ref = "kotlin" }
kotlin-compose      = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp                 = { id = "com.google.devtools.ksp",         version.ref = "ksp"    }
`},

{id:"proguard",group:"Build Config",name:"app/proguard-rules.pro",lang:"pro",color:YELLOW,desc:"ProGuard rules — keeps JNI methods, Room entities, Retrofit models, OkHttp from stripping",
code:`# ── Howard ProGuard Rules ───────────────────────────────────────────────────

# Keep JNI methods (called from howard_jni.cpp)
-keepclasseswithmembernames class au.howardagent.engine.LocalEngine {
    native <methods>;
}

# Keep Room entities and DAOs
-keep class au.howardagent.data.** { *; }

# Keep all Kotlin data classes used for serialisation
-keep class au.howardagent.download.ModelInfo { *; }
-keep class au.howardagent.connectors.TelegramMessage { *; }

# OkHttp + Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Kotlin metadata
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Security crypto
-keep class androidx.security.crypto.** { *; }

# Suppress missing class warnings for unused modules
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
`},

// ═══════════════════════════════════════════════════════════════════════════
// ASSET BUNDLING SCRIPT
// ═══════════════════════════════════════════════════════════════════════════
{id:"bundle_script",group:"Build Scripts",name:"scripts/bundle_assets.sh",lang:"bash",color:GREEN,desc:"Run this ONCE on your Geekom A9 Max before building the APK. Downloads Node ARM64 + OpenClaw and places them in app/src/main/assets/",
code:`#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Asset Bundling Script
# Run on your Geekom A9 Max (Linux/WSL) before building the APK.
# Places Node.js ARM64 binary + OpenClaw package into app/src/main/assets/
# ─────────────────────────────────────────────────────────────────────────────
set -e

ASSETS_DIR="app/src/main/assets"
NODE_VERSION="22.12.0"
NODE_ARCH="linux-arm64"   # ARM64 Android target

echo "═══════════════════════════════════════════════"
echo "  HOWARD — Bundling Assets"
echo "═══════════════════════════════════════════════"

mkdir -p "\$ASSETS_DIR/node"

# ── 1. Download Node.js ARM64 binary ────────────────────────────────────────
echo ""
echo "[1/3] Downloading Node.js \$NODE_VERSION ARM64..."
NODE_URL="https://nodejs.org/dist/v\$NODE_VERSION/node-v\$NODE_VERSION-\$NODE_ARCH.tar.xz"
NODE_TAR="node-\$NODE_VERSION.tar.xz"

curl -L -o "/tmp/\$NODE_TAR" "\$NODE_URL"
tar -xf "/tmp/\$NODE_TAR" -C /tmp/

NODE_BIN="/tmp/node-v\$NODE_VERSION-\$NODE_ARCH/bin/node"
cp "\$NODE_BIN" "\$ASSETS_DIR/node/node"
chmod +x "\$ASSETS_DIR/node/node"
echo "    ✓ Node binary: \$(ls -lh \$ASSETS_DIR/node/node | awk '{print \$5}')"

# ── 2. Pack OpenClaw as tar.gz ───────────────────────────────────────────────
echo ""
echo "[2/3] Packing OpenClaw npm package..."

# Install openclaw globally to get its files
npm install -g openclaw@latest --prefix /tmp/openclaw_install

# Create a clean package directory
mkdir -p /tmp/openclaw_bundle
cp -r /tmp/openclaw_install/lib/node_modules/openclaw /tmp/openclaw_bundle/openclaw

# Strip dev dependencies to reduce size
cd /tmp/openclaw_bundle/openclaw
npm prune --omit=dev 2>/dev/null || true
cd -

# Tar it up
tar -czf "\$ASSETS_DIR/openclaw.tar.gz" -C /tmp/openclaw_bundle openclaw
echo "    ✓ OpenClaw bundle: \$(ls -lh \$ASSETS_DIR/openclaw.tar.gz | awk '{print \$5}')"

# ── 3. Verify ────────────────────────────────────────────────────────────────
echo ""
echo "[3/3] Verifying assets..."
echo "    node binary:     \$(file \$ASSETS_DIR/node/node)"
echo "    openclaw.tar.gz: \$(ls -lh \$ASSETS_DIR/openclaw.tar.gz)"

TOTAL=\$(du -sh \$ASSETS_DIR | cut -f1)
echo ""
echo "═══════════════════════════════════════════════"
echo "  Assets ready. Total size: \$TOTAL"
echo "  Now build the APK:"
echo "  ./gradlew :app:assembleRelease"
echo "═══════════════════════════════════════════════"
`},

{id:"llama_submodule",group:"Build Scripts",name:"scripts/init_project.sh",lang:"bash",color:GREEN,desc:"Full project initialisation — git submodule for llama.cpp, keystore generation for signing, initial Gradle sync",
code:`#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Project Initialisation
# Run once after cloning the repository.
# ─────────────────────────────────────────────────────────────────────────────
set -e

echo "═══════════════════════════════════════════════"
echo "  HOWARD — Project Init"
echo "═══════════════════════════════════════════════"

# ── 1. Add llama.cpp as git submodule ────────────────────────────────────────
echo ""
echo "[1/4] Adding llama.cpp submodule..."
if [ ! -d "app/src/main/cpp/llama.cpp/.git" ]; then
    git submodule add https://github.com/ggml-org/llama.cpp \
        app/src/main/cpp/llama.cpp
    git submodule update --init --recursive
    echo "    ✓ llama.cpp submodule added"
else
    echo "    ✓ llama.cpp already present, updating..."
    git submodule update --remote app/src/main/cpp/llama.cpp
fi

# ── 2. Generate debug keystore ───────────────────────────────────────────────
echo ""
echo "[2/4] Generating debug keystore..."
KEYSTORE="app/howard-debug.jks"
if [ ! -f "\$KEYSTORE" ]; then
    keytool -genkeypair \
        -alias howard \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -keystore "\$KEYSTORE" \
        -dname "CN=Howard Agent, O=Howard, C=AU" \
        -storepass howard123 \
        -keypass howard123
    echo "    ✓ Keystore generated: \$KEYSTORE"
else
    echo "    ✓ Keystore already exists"
fi

# ── 3. Create local.properties ───────────────────────────────────────────────
echo ""
echo "[3/4] Creating local.properties..."
if [ ! -f "local.properties" ]; then
    echo "sdk.dir=\$ANDROID_HOME" > local.properties
    echo "ndk.dir=\$ANDROID_NDK_HOME" >> local.properties
    echo "    ✓ local.properties written"
fi

# ── 4. Create placeholder drawables ──────────────────────────────────────────
echo ""
echo "[4/4] Creating placeholder resources..."
DRAWABLE_DIR="app/src/main/res/drawable"
mkdir -p "\$DRAWABLE_DIR"

cat > "\$DRAWABLE_DIR/ic_howard_notif.xml" << 'ICON_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm-2,14.5v-9l6,4.5-6,4.5z"/>
</vector>
ICON_EOF
echo "    ✓ Notification icon created"

echo ""
echo "═══════════════════════════════════════════════"
echo "  Init complete. Next steps:"
echo "  1. Run scripts/bundle_assets.sh"
echo "  2. Open in Android Studio"
echo "  3. Sync Gradle"
echo "  4. Build → Generate Signed APK"
echo "═══════════════════════════════════════════════"
`},

// ═══════════════════════════════════════════════════════════════════════════
// STRINGS + THEME EXTRAS
// ═══════════════════════════════════════════════════════════════════════════
{id:"strings_xml",group:"Resources",name:"src/main/res/values/strings.xml",lang:"xml",color:YELLOW,desc:"All user-facing strings — onboarding copy, tool descriptions, error messages",
code:`<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Howard</string>
    <string name="app_tagline">Your mobile-first AI agent</string>

    <!-- Onboarding -->
    <string name="notice_title">Welcome to Howard</string>
    <string name="notice_subtitle">Your mobile-first AI agent</string>
    <string name="notice_cta">Got it</string>
    <string name="onboarding_step_model">Choose Model</string>
    <string name="onboarding_step_keys">API Keys</string>
    <string name="onboarding_step_openclaw">OpenClaw</string>
    <string name="onboarding_step_telegram">Telegram</string>

    <!-- Model chooser -->
    <string name="model_recommended">Recommended</string>
    <string name="model_add_custom">Add custom model</string>
    <string name="model_add_custom_desc">Import your own GGUF model</string>
    <string name="model_api_key">Use your own API key</string>
    <string name="model_api_key_desc">Connect to OpenAI, Anthropic, Gemini, Kimi and more</string>
    <string name="model_download">Download</string>
    <string name="model_downloading">Downloading…</string>
    <string name="model_privacy_warning">By using an API key, your messages are sent to external servers. Your keys are stored encrypted on-device and never leave your phone.</string>

    <!-- Chat -->
    <string name="chat_placeholder">Message Howard…</string>
    <string name="chat_empty_title">Howard is ready</string>
    <string name="chat_empty_subtitle">Ask me anything or give me a task</string>
    <string name="chat_stop">Stop</string>
    <string name="chat_thinking">Howard is thinking…</string>

    <!-- Tools -->
    <string name="tool_github_sync">GitHub Sync</string>
    <string name="tool_file_organizer">File Organiser</string>
    <string name="tool_component_gen">Component Generator</string>
    <string name="tool_telegram">Telegram Send</string>
    <string name="tool_shell">Shell</string>

    <!-- Settings -->
    <string name="settings_title">Settings</string>
    <string name="settings_models">Models</string>
    <string name="settings_api_keys">API Keys</string>
    <string name="settings_telegram">Telegram</string>
    <string name="settings_openclaw">OpenClaw</string>
    <string name="settings_about">About</string>

    <!-- Errors -->
    <string name="error_model_not_found">No model downloaded. Go to Settings → Models.</string>
    <string name="error_gateway_offline">OpenClaw gateway is offline. Restart the app.</string>
    <string name="error_inference_failed">Inference failed. Check your model or API key.</string>
    <string name="error_telegram_no_token">No Telegram bot token. Add one in Settings.</string>

    <!-- Notifications -->
    <string name="notif_gateway_online">Howard online · port 18789</string>
    <string name="notif_gateway_starting">Howard is starting…</string>
    <string name="notif_inference_ready">Howard inference ready</string>
</resources>
`},

{id:"typography",group:"Resources",name:"ui/theme/Type.kt",lang:"kotlin",color:ACCENT,desc:"Typography scale — JetBrains Mono for code/monospace, Outfit for display, Source Sans 3 for body",
code:`package au.howardagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import au.howardagent.R

// Download these from Google Fonts and place in res/font/
// outfit_regular.ttf, outfit_semibold.ttf, outfit_bold.ttf
// source_sans3_regular.ttf, source_sans3_medium.ttf
// jetbrainsmono_regular.ttf

val OutfitFamily = FontFamily(
    Font(R.font.outfit_regular,  FontWeight.Normal),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold,     FontWeight.Bold)
)

val SourceSansFamily = FontFamily(
    Font(R.font.source_sans3_regular, FontWeight.Normal),
    Font(R.font.source_sans3_medium,  FontWeight.Medium)
)

val MonoFamily = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal)
)

val HowardTypography = Typography(
    displayLarge = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold,   fontSize = 57.sp),
    displayMedium= TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold,   fontSize = 45.sp),
    displaySmall = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold,   fontSize = 36.sp),

    headlineLarge  = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold,    fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold,    fontSize = 28.sp),
    headlineSmall  = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.SemiBold,fontSize = 24.sp),

    titleLarge  = TextStyle(fontFamily = OutfitFamily,     fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = OutfitFamily,     fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall  = TextStyle(fontFamily = OutfitFamily,     fontWeight = FontWeight.Medium,   fontSize = 14.sp),

    bodyLarge  = TextStyle(fontFamily = SourceSansFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = SourceSansFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall  = TextStyle(fontFamily = SourceSansFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),

    labelLarge  = TextStyle(fontFamily = OutfitFamily,    fontWeight = FontWeight.Medium,  fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = SourceSansFamily,fontWeight = FontWeight.Medium,  fontSize = 12.sp),
    labelSmall  = TextStyle(fontFamily = MonoFamily,      fontWeight = FontWeight.Normal,  fontSize = 11.sp)
)

val HowardShapes = androidx.compose.material3.Shapes()
`},
];

// ─────────────────────────────────────────────────────────────────────────────
const GROUP_ORDER=["Chat Layer","Agent Layer","Connectors","Onboarding UI","Tools & Settings","Services","Build Config","Build Scripts","Resources"];
const GROUP_COLORS:Record<string,string>={"Chat Layer":GREEN,"Agent Layer":"#00ff88","Connectors":BLUE,"Onboarding UI":BLUE,"Tools & Settings":YELLOW,"Services":ACCENT,"Build Config":YELLOW,"Build Scripts":GREEN,"Resources":YELLOW};
const LANG_COLORS:Record<string,string>={kotlin:"#a78bfa",cpp:ACCENT,xml:YELLOW,bash:"#86efac",toml:BLUE,pro:"#94a3b8"};
const GROUP_ICONS:Record<string,string>={"Chat Layer":"💬","Agent Layer":"⚡","Connectors":"🔌","Onboarding UI":"🎯","Tools & Settings":"🔧","Services":"⚙️","Build Config":"📦","Build Scripts":"🛠","Resources":"🎨"};

const CopyButton=({code}:{code:string})=>{
  const[copied,setCopied]=useState(false);
  return <button onClick={()=>{navigator.clipboard.writeText(code);setCopied(true);setTimeout(()=>setCopied(false),1800);}} style={{background:copied?"#00ff8818":"#ffffff0a",border:`1px solid ${copied?"#00ff88":"#ffffff15"}`,color:copied?"#00ff88":"#64748b",padding:"3px 12px",borderRadius:3,fontSize:11,cursor:"pointer",fontFamily:"monospace",transition:"all 0.2s",whiteSpace:"nowrap"}}>{copied?"✓ copied":"copy"}</button>;
};

const TOTAL_FILES=files.length;
const groupCounts:Record<string,number>={};
files.forEach(f=>{groupCounts[f.group]=(groupCounts[f.group]||0)+1;});

export default function HowardComplete(){
  const[activeGroup,setActiveGroup]=useState("Chat Layer");
  const[activeFile,setActiveFile]=useState(files[0].id);
  const groupFiles=files.filter(f=>f.group===activeGroup);
  const file=files.find(f=>f.id===activeFile)??groupFiles[0];
  const accent=GROUP_COLORS[activeGroup]??ACCENT;

  return(
    <div style={{background:"#080810",minHeight:"100vh",fontFamily:"'Courier New',monospace",color:"#e2e8f0",display:"flex",flexDirection:"column"}}>
      {/* Header */}
      <div style={{background:"#0c0c18",borderBottom:"1px solid #1e293b",padding:"14px 24px",flexShrink:0}}>
        <div style={{display:"flex",alignItems:"center",gap:12,flexWrap:"wrap"}}>
          <span style={{fontSize:10,color:"#475569",letterSpacing:4}}>HOWARD_MOBILE</span>
          <span style={{color:"#334155"}}>›</span>
          <span style={{fontSize:18,fontWeight:700,color:"#f1f5f9",letterSpacing:4}}>COMPLETE APK</span>
          <span style={{fontSize:10,color:GREEN,border:"1px solid #00ff8844",padding:"2px 8px",borderRadius:3}}>ALL LAYERS</span>
          <span style={{fontSize:10,color:"#334155",marginLeft:"auto"}}>{TOTAL_FILES} FILES · {Object.keys(groupCounts).length} GROUPS</span>
        </div>
        {/* Progress bar */}
        <div style={{marginTop:10,display:"flex",gap:3}}>
          {GROUP_ORDER.map(g=>(
            <div key={g} title={g} style={{flex:groupCounts[g]||1,height:3,background:GROUP_COLORS[g]??"#334155",borderRadius:2,opacity:activeGroup===g?1:0.35,transition:"opacity 0.2s"}}/>
          ))}
        </div>
      </div>

      {/* Group tabs */}
      <div style={{display:"flex",borderBottom:"1px solid #1e293b",background:"#0c0c18",overflowX:"auto",flexShrink:0}}>
        {GROUP_ORDER.filter(g=>groupCounts[g]).map(g=>{
          const col=GROUP_COLORS[g];const active=g===activeGroup;
          return <button key={g} onClick={()=>{setActiveGroup(g);setActiveFile(files.find(f=>f.group===g)?.id??"");}} style={{background:"transparent",border:"none",borderBottom:`2px solid ${active?col:"transparent"}`,color:active?col:"#475569",padding:"8px 16px",fontSize:10,letterSpacing:1,cursor:"pointer",whiteSpace:"nowrap",transition:"all 0.15s"}}>
            {GROUP_ICONS[g]} {g.toUpperCase()} <span style={{color:"#334155",marginLeft:4}}>({groupCounts[g]})</span>
          </button>;
        })}
      </div>

      {/* Body */}
      <div style={{display:"flex",flex:1,overflow:"hidden"}}>
        {/* File list */}
        <div style={{width:210,borderRight:"1px solid #1e293b",background:"#0c0c18",overflowY:"auto",flexShrink:0}}>
          {groupFiles.map(f=>{
            const isActive=f.id===file?.id;
            return <div key={f.id} onClick={()=>setActiveFile(f.id)} style={{padding:"11px 14px",cursor:"pointer",borderLeft:`3px solid ${isActive?accent:"transparent"}`,background:isActive?`${accent}0d`:"transparent",transition:"all 0.12s"}}>
              <div style={{display:"flex",gap:5,marginBottom:3}}><span style={{fontSize:9,color:f.color,border:`1px solid ${f.color}40`,padding:"0 5px",borderRadius:2}}>{f.lang}</span></div>
              <div style={{fontSize:12,color:isActive?"#f1f5f9":"#94a3b8",fontWeight:isActive?600:400}}>{f.name.split("/").pop()}</div>
              <div style={{fontSize:10,color:"#334155",marginTop:1}}>{f.name.split("/").slice(0,-1).join("/")}</div>
            </div>;
          })}
        </div>

        {/* Code pane */}
        {file&&<div style={{flex:1,display:"flex",flexDirection:"column",overflow:"hidden"}}>
          <div style={{background:"#0c0c18",borderBottom:"1px solid #1e293b",padding:"10px 20px",display:"flex",alignItems:"center",gap:12,flexShrink:0}}>
            <span style={{fontSize:10,color:accent,border:`1px solid ${accent}44`,padding:"1px 8px",borderRadius:3}}>{file.lang.toUpperCase()}</span>
            <span style={{fontSize:13,color:"#f1f5f9",fontWeight:600,flex:1}}>{file.name}</span>
            <CopyButton code={file.code}/>
          </div>
          <div style={{background:"#0a0a14",borderBottom:"1px solid #1e293b",padding:"7px 20px",fontSize:11,color:"#64748b",flexShrink:0}}>{file.desc}</div>
          <div style={{flex:1,overflowY:"auto",background:"#060610"}}>
            <pre style={{margin:0,padding:"16px 20px",fontSize:11.5,lineHeight:1.75,color:LANG_COLORS[file.lang]??"#86efac",whiteSpace:"pre",overflow:"visible"}}>{file.code}</pre>
          </div>
        </div>}
      </div>
    </div>
  );
}
