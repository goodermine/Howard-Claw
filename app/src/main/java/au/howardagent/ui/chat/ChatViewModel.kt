package au.howardagent.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.howardagent.HowardApplication
import au.howardagent.agent.CommandDispatcher
import au.howardagent.agent.PromptBuilder
import au.howardagent.agent.SystemPrompts
import au.howardagent.data.HowardDatabase
import au.howardagent.data.MessageEntity
import au.howardagent.engine.EngineRouter
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

    private val app = application as HowardApplication
    private val prefs = app.securePrefs
    private val database = app.database
    private val router = EngineRouter(application, prefs)
    private val dispatcher = CommandDispatcher(application)

    private val conversationId: String = UUID.randomUUID().toString()

    private val _uiState = MutableStateFlow(ChatUiState(
        activeProvider = prefs.activeProvider.ifBlank { "local" }
    ))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private val cmdPattern = Regex("""\[CMD:(.+?)]""")

    fun send(userText: String) {
        val userMessage = ChatMessage(role = "user", content = userText)
        appendMessage(userMessage)
        persistMessage(userMessage)

        val streamingId = UUID.randomUUID().toString()
        appendMessage(ChatMessage(id = streamingId, role = "assistant", content = "", isStreaming = true))
        _uiState.update { it.copy(isGenerating = true, error = null) }

        generationJob = viewModelScope.launch {
            try {
                val engine = router.getEngine()
                val startTime = System.currentTimeMillis()
                var tokenCount = 0
                val contentBuilder = StringBuilder()

                val history = _uiState.value.messages
                    .filter { it.role == "user" || (it.role == "assistant" && !it.isStreaming) }
                val prompt = PromptBuilder.build(history)

                engine.infer(
                    prompt = prompt,
                    systemPrompt = SystemPrompts.HOWARD_BASE,
                    onToken = { token ->
                        contentBuilder.append(token)
                        tokenCount++
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val tokPerSec = if (elapsed > 0) tokenCount / elapsed else 0.0
                        updateStreamingMessage(streamingId) {
                            it.copy(content = contentBuilder.toString(), tokensPerSec = tokPerSec)
                        }
                    },
                    onComplete = {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val finalTokPerSec = if (elapsed > 0) tokenCount / elapsed else 0.0
                        val finalContent = contentBuilder.toString()

                        replaceMessage(streamingId, ChatMessage(
                            id = streamingId,
                            role = "assistant",
                            content = finalContent,
                            model = _uiState.value.activeProvider,
                            isStreaming = false,
                            tokensPerSec = finalTokPerSec
                        ))
                        persistMessage(ChatMessage(
                            id = streamingId, role = "assistant",
                            content = finalContent, model = _uiState.value.activeProvider,
                            tokensPerSec = finalTokPerSec
                        ))

                        _uiState.update { it.copy(isGenerating = false) }

                        // Dispatch any [CMD:] tokens
                        val commands = cmdPattern.findAll(finalContent).toList()
                        if (commands.isNotEmpty()) {
                            viewModelScope.launch {
                                for (match in commands) {
                                    val cmdText = match.groupValues[1].trim()
                                    val result = dispatcher.dispatch(cmdText)
                                    val toolMsg = ChatMessage(role = "tool", content = result)
                                    appendMessage(toolMsg)
                                    persistMessage(toolMsg)
                                }
                            }
                        }
                    },
                    onError = { error ->
                        removeMessage(streamingId)
                        _uiState.update { it.copy(isGenerating = false, error = error) }
                    }
                )
            } catch (e: Exception) {
                removeMessage(streamingId)
                _uiState.update { it.copy(isGenerating = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        router.stopCurrent()
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it },
                isGenerating = false
            )
        }
    }

    fun switchProvider(provider: String) {
        prefs.activeProvider = provider
        _uiState.update { it.copy(activeProvider = provider) }
    }

    fun clearConversation() {
        viewModelScope.launch {
            try { database.messageDao().clearConversation(conversationId) } catch (_: Exception) {}
            _uiState.update { it.copy(messages = emptyList(), error = null) }
        }
    }

    private fun appendMessage(message: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + message) }
    }

    private fun updateStreamingMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun replaceMessage(id: String, newMessage: ChatMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) newMessage else it })
        }
    }

    private fun removeMessage(id: String) {
        _uiState.update { state -> state.copy(messages = state.messages.filter { it.id != id }) }
    }

    private fun persistMessage(message: ChatMessage) {
        viewModelScope.launch {
            try {
                database.messageDao().insert(MessageEntity(
                    id = message.id,
                    conversationId = conversationId,
                    role = message.role,
                    content = message.content,
                    model = message.model
                ))
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        router.releaseAll()
    }
}
