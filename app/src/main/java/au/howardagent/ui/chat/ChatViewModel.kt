package au.howardagent.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.howardagent.HowardApp
import au.howardagent.command.CommandDispatcher
import au.howardagent.data.local.AppDatabase
import au.howardagent.data.local.MessageEntity
import au.howardagent.engine.EngineRouter
import au.howardagent.security.SecurePrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val model: String = "",
    val isStreaming: Boolean = false,
    val tokensPerSec: Double = 0.0
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val activeProvider: String = "local",
    val gatewayStatus: String = "offline",
    val error: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HowardApp
    private val securePrefs = SecurePrefs(application)
    private val database = AppDatabase.getInstance(application)
    private val downloader get() = app.modelDownloader
    private val dispatcher = CommandDispatcher(application)
    private val router = EngineRouter(application)

    private val conversationId: String = UUID.randomUUID().toString()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private val cmdPattern = Regex("""\[CMD:(.+?)]""")

    init {
        loadHistory()
        checkGatewayStatus()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            try {
                val entities = database.messageDao().getMessagesForConversation(conversationId)
                val messages = entities.map { entity ->
                    ChatMessage(
                        id = entity.id,
                        role = entity.role,
                        content = entity.content,
                        model = entity.model
                    )
                }
                _uiState.update { it.copy(messages = messages) }
            } catch (_: Exception) {
                // Fresh conversation, no history
            }
        }
    }

    private fun checkGatewayStatus() {
        viewModelScope.launch {
            try {
                val online = au.howardagent.openclaw.OpenClawConnector.isOnline(app)
                _uiState.update {
                    it.copy(gatewayStatus = if (online) "online" else "offline")
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(gatewayStatus = "offline") }
            }
        }
    }

    fun send(userText: String) {
        val userMessage = ChatMessage(
            role = "user",
            content = userText
        )
        appendMessage(userMessage)
        persistMessage(userMessage)

        val streamingId = UUID.randomUUID().toString()
        val streamingMessage = ChatMessage(
            id = streamingId,
            role = "assistant",
            content = "",
            isStreaming = true
        )
        appendMessage(streamingMessage)
        _uiState.update { it.copy(isGenerating = true, error = null) }

        generationJob = viewModelScope.launch {
            try {
                val modelPath = getActiveModelPath()
                val engine = router.getEngine(_uiState.value.activeProvider)

                val startTime = System.currentTimeMillis()
                var tokenCount = 0
                val contentBuilder = StringBuilder()

                engine.infer(
                    modelPath = modelPath,
                    prompt = userText,
                    conversationId = conversationId,
                    onToken = { token ->
                        contentBuilder.append(token)
                        tokenCount++
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val tokPerSec = if (elapsed > 0) tokenCount / elapsed else 0.0

                        updateStreamingMessage(streamingId) {
                            it.copy(
                                content = contentBuilder.toString(),
                                tokensPerSec = tokPerSec
                            )
                        }
                    },
                    onComplete = { modelName ->
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val finalTokPerSec = if (elapsed > 0) tokenCount / elapsed else 0.0
                        val finalContent = contentBuilder.toString()

                        replaceMessage(streamingId, ChatMessage(
                            id = streamingId,
                            role = "assistant",
                            content = finalContent,
                            model = modelName,
                            isStreaming = false,
                            tokensPerSec = finalTokPerSec
                        ))

                        persistMessage(ChatMessage(
                            id = streamingId,
                            role = "assistant",
                            content = finalContent,
                            model = modelName,
                            tokensPerSec = finalTokPerSec
                        ))

                        // Detect and dispatch [CMD:] tokens
                        val commands = cmdPattern.findAll(finalContent)
                        for (match in commands) {
                            val cmdText = match.groupValues[1].trim()
                            try {
                                val result = dispatcher.dispatch(cmdText, conversationId)
                                val toolMsg = ChatMessage(
                                    role = "tool",
                                    content = result
                                )
                                appendMessage(toolMsg)
                                persistMessage(toolMsg)
                            } catch (e: Exception) {
                                val errorMsg = ChatMessage(
                                    role = "tool",
                                    content = "Command failed: ${e.message}"
                                )
                                appendMessage(errorMsg)
                            }
                        }

                        _uiState.update { it.copy(isGenerating = false) }
                    },
                    onError = { error ->
                        removeMessage(streamingId)
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                error = error
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                removeMessage(streamingId)
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _uiState.update { state ->
            val updated = state.messages.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            }
            state.copy(messages = updated, isGenerating = false)
        }
    }

    fun switchProvider(provider: String) {
        _uiState.update { it.copy(activeProvider = provider) }
    }

    fun clearConversation() {
        viewModelScope.launch {
            try {
                database.messageDao().deleteConversation(conversationId)
            } catch (_: Exception) { }
            _uiState.update { it.copy(messages = emptyList(), error = null) }
        }
    }

    // ---- Helper methods ----

    private fun appendMessage(message: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + message) }
    }

    private fun updateStreamingMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state ->
            val updated = state.messages.map { msg ->
                if (msg.id == id) transform(msg) else msg
            }
            state.copy(messages = updated)
        }
    }

    private fun replaceMessage(id: String, newMessage: ChatMessage) {
        _uiState.update { state ->
            val updated = state.messages.map { msg ->
                if (msg.id == id) newMessage else msg
            }
            state.copy(messages = updated)
        }
    }

    private fun removeMessage(id: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages.filter { it.id != id })
        }
    }

    private fun persistMessage(message: ChatMessage) {
        viewModelScope.launch {
            try {
                database.messageDao().insert(
                    MessageEntity(
                        id = message.id,
                        conversationId = conversationId,
                        role = message.role,
                        content = message.content,
                        model = message.model,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) { }
        }
    }

    private suspend fun getActiveModelPath(): String {
        val activeModel = database.modelDao().getActiveModel()
        return activeModel?.filePath ?: ""
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        router.release()
    }
}
