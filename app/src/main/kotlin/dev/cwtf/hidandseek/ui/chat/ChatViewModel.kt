package dev.cwtf.hidandseek.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cwtf.hidandseek.AppContainer
import dev.cwtf.hidandseek.bluetooth.TypeResult
import dev.cwtf.hidandseek.data.chat.ChatMessage
import dev.cwtf.hidandseek.data.chat.ChatRole
import dev.cwtf.hidandseek.data.chat.Conversation
import dev.cwtf.hidandseek.data.llm.ChatEvent
import dev.cwtf.hidandseek.data.llm.InvalidApiKey
import dev.cwtf.hidandseek.data.llm.LlmProvider
import dev.cwtf.hidandseek.data.llm.RateLimited
import dev.cwtf.hidandseek.data.llm.UnknownModel
import dev.cwtf.hidandseek.data.llm.WireMessage
import dev.cwtf.hidandseek.hid.TransportState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val chats = container.chatRepository

    val conversations: StateFlow<List<Conversation>> = chats.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeConversationId = MutableStateFlow<String?>(null)

    val messages: StateFlow<List<ChatMessage>> = activeConversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chats.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val providers = container.providers
    val transportState = container.hidController.transport.state

    var composerText by mutableStateOf("")
        private set

    /** Text streaming in right now. Persisted once the stream ends. */
    var streamingContent by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf<String?>(null)

    /** Set when a code block or selection is about to be typed to the host. */
    var pendingHostSend by mutableStateOf<String?>(null)
        private set

    private var streamJob: Job? = null

    val isStreaming: Boolean get() = streamJob?.isActive == true

    val activeConversation: Conversation?
        get() = conversations.value.firstOrNull { it.id == activeConversationId.value }

    val hasProvider: Boolean get() = providers.value.active != null

    // --- conversations ------------------------------------------------------

    fun selectConversation(id: String?) {
        activeConversationId.value = id
    }

    fun newConversation() {
        activeConversationId.value = null
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { chats.renameConversation(id, title) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chats.deleteConversation(id)
            if (activeConversationId.value == id) activeConversationId.value = null
        }
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch { chats.deleteMessage(id) }
    }

    // --- composing ----------------------------------------------------------

    fun onComposerChange(text: String) {
        composerText = text
    }

    fun send() {
        val provider = providers.value.active ?: run {
            status = "Add an LLM provider in Settings first"
            return
        }
        val text = composerText.trim()
        if (text.isEmpty() || isStreaming) return

        composerText = ""
        streamJob = viewModelScope.launch { runTurn(provider, text) }
    }

    private suspend fun runTurn(provider: LlmProvider, userText: String) {
        val existing = activeConversationId.value?.let { id ->
            conversations.value.firstOrNull { it.id == id }
        }

        // Captured before the write: the messages flow reloads asynchronously,
        // so reading it back straight after inserting would race the insert.
        val priorMessages = if (existing != null) messages.value else emptyList()

        val conversation = existing ?: chats.createConversation(
            // The first message makes a better title than "New chat", and the
            // user can rename it from the drawer.
            title = userText.lineSequence().first().take(60).ifBlank { "New chat" },
            providerId = provider.id,
            model = provider.defaultModel,
        ).also { activeConversationId.value = it.id }

        val userMessage = ChatMessage(
            conversationId = conversation.id,
            role = ChatRole.USER,
            content = userText,
        )
        chats.addMessage(userMessage)

        streamAssistantReply(provider, conversation, priorMessages + userMessage)
    }

    private suspend fun streamAssistantReply(
        provider: LlmProvider,
        conversation: Conversation,
        history: List<ChatMessage>,
    ) {
        val model = conversation.model?.takeIf { it.isNotBlank() } ?: provider.defaultModel
        if (model.isBlank()) {
            status = "Choose a model for this provider in Settings"
            return
        }

        val wire = history
            .filter { it.error == null }
            .map {
                WireMessage(
                    role = when (it.role) {
                        ChatRole.USER -> WireMessage.USER
                        ChatRole.ASSISTANT -> WireMessage.ASSISTANT
                        ChatRole.SYSTEM -> WireMessage.SYSTEM
                    },
                    content = it.content,
                )
            }

        val builder = StringBuilder()
        streamingContent = ""
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var failure: Throwable? = null

        try {
            container.llmClient.streamChat(
                provider = provider,
                apiKey = container.llmProviderRepository.apiKey(provider),
                model = model,
                messages = wire,
            ).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        builder.append(event.text)
                        streamingContent = builder.toString()
                    }

                    is ChatEvent.Usage -> {
                        promptTokens = event.promptTokens
                        completionTokens = event.completionTokens
                    }

                    ChatEvent.Completed -> Unit
                }
            }
        } catch (e: Throwable) {
            failure = e
        }

        streamingContent = null

        // Partial content is kept and marked incomplete rather than discarded —
        // half an answer is still worth reading, and often worth typing.
        val content = builder.toString()
        if (content.isNotEmpty() || failure == null) {
            chats.addMessage(
                ChatMessage(
                    conversationId = conversation.id,
                    role = ChatRole.ASSISTANT,
                    content = content,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    incomplete = failure != null,
                ),
            )
        }

        failure?.let { status = describe(it) }
    }

    private fun describe(error: Throwable): String = when (error) {
        is InvalidApiKey -> "Check your API key in Settings"
        is RateLimited -> error.retryAfterSeconds
            ?.let { "Rate limited — try again in ${it}s" }
            ?: "Rate limited by the provider"

        is UnknownModel -> "Model `${error.model}` was rejected — pick another in Settings"
        else -> error.message ?: "Request failed"
    }

    fun stopGenerating() {
        streamJob?.cancel()
        streamingContent = null
        status = "Stopped"
    }

    // --- sending to the host ------------------------------------------------

    /**
     * Stages text for the host and asks before sending.
     *
     * Confirmation is the default because this types into someone's machine;
     * the user sees exactly what will be sent before anything leaves.
     */
    fun requestHostSend(text: String) {
        if (transportState.value != TransportState.CONNECTED) {
            status = "Connect to a device first"
            return
        }
        pendingHostSend = text
    }

    fun cancelHostSend() {
        pendingHostSend = null
    }

    fun confirmHostSend(appendEnter: Boolean, stripIndent: Boolean) {
        val text = pendingHostSend ?: return
        pendingHostSend = null
        val payload = if (stripIndent) text.lines().joinToString("\n") { it.trimStart() } else text

        viewModelScope.launch {
            status = when (val result = container.hidController.typeText(payload, appendEnter)) {
                is TypeResult.Delivered -> "Typed ${result.chars} characters to the device"
                is TypeResult.Partial -> "Stopped after ${result.charsDelivered} characters"
                is TypeResult.Rejected -> result.cause.message
            }
        }
    }
}
