package dev.cwtf.hidandseek.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cwtf.hidandseek.AppContainer
import dev.cwtf.hidandseek.bluetooth.TypeResult
import dev.cwtf.hidandseek.data.agent.AgentDecision
import dev.cwtf.hidandseek.data.agent.AgentGuardrails
import dev.cwtf.hidandseek.data.agent.AgentMode
import dev.cwtf.hidandseek.data.agent.AgentRateState
import dev.cwtf.hidandseek.data.agent.AgentTools
import dev.cwtf.hidandseek.data.agent.AgentAuditEntry
import dev.cwtf.hidandseek.data.agent.ApprovalReason
import dev.cwtf.hidandseek.data.agent.TypeToHostRequest
import android.net.Uri
import dev.cwtf.hidandseek.data.chat.ChatMessage
import dev.cwtf.hidandseek.data.chat.ChatRole
import dev.cwtf.hidandseek.data.chat.Conversation
import dev.cwtf.hidandseek.data.chat.MessageAttachment
import dev.cwtf.hidandseek.data.chat.ProcessedImage
import dev.cwtf.hidandseek.data.llm.ChatEvent
import dev.cwtf.hidandseek.data.llm.InvalidApiKey
import dev.cwtf.hidandseek.data.llm.LlmProvider
import dev.cwtf.hidandseek.data.llm.RateLimited
import dev.cwtf.hidandseek.data.llm.ToolsUnsupported
import dev.cwtf.hidandseek.data.llm.UnknownModel
import dev.cwtf.hidandseek.data.llm.VisionUnsupported
import dev.cwtf.hidandseek.data.llm.WireMessage
import dev.cwtf.hidandseek.data.llm.WireToolCall
import dev.cwtf.hidandseek.hid.TransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** A model request waiting on the user. */
data class PendingApproval(
    val request: TypeToHostRequest,
    val reason: ApprovalReason,
    val matchedPatterns: List<String>,
    internal val answer: CompletableDeferred<Boolean>,
)

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
    val settings = container.settings

    var composerText by mutableStateOf("")
        private set

    var streamingContent by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf<String?>(null)

    var pendingHostSend by mutableStateOf<String?>(null)
        private set

    // --- attachments --------------------------------------------------------

    var attachments by mutableStateOf<List<ProcessedImage>>(emptyList())
        private set

    val estimatedAttachmentTokens: Int get() = attachments.sumOf { it.estimatedTokens }

    /** Whether the selected model is known to be unable to read images. */
    val modelLacksVision: Boolean
        get() {
            val provider = providers.value.active ?: return false
            val model = activeConversation?.model ?: provider.defaultModel
            return provider.model(model)?.supportsVision == false
        }

    fun attach(uri: Uri) {
        val limits = settings.value.attachments
        if (attachments.size >= limits.maxImagesPerMessage) {
            status = "Up to ${limits.maxImagesPerMessage} images per message"
            return
        }
        viewModelScope.launch {
            container.imageProcessor.process(uri, limits).fold(
                onSuccess = { attachments = attachments + it },
                onFailure = { status = "Could not read that image: ${it.message}" },
            )
        }
    }

    fun removeAttachment(id: String) {
        attachments.firstOrNull { it.id == id }?.let(container.imageProcessor::delete)
        attachments = attachments.filterNot { it.id == id }
    }

    /**
     * Pulls text out of an image locally and puts it in the composer.
     *
     * Lands in the composer for review rather than being sent blind — accuracy
     * on photographed screens is imperfect, and this text may end up typed
     * into a machine.
     */
    fun extractTextFrom(image: ProcessedImage) {
        viewModelScope.launch {
            container.textRecognizer.extract(image.file).fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        status = "No text found in that image"
                    } else {
                        composerText = (composerText + "\n" + text).trim()
                        removeAttachment(image.id)
                    }
                },
                onFailure = { status = "Could not read text: ${it.message}" },
            )
        }
    }

    // --- agent state --------------------------------------------------------

    /**
     * Always starts at Ask.
     *
     * Auto is an in-session choice: it is never persisted, never restored, and
     * cannot be made the startup mode from Settings. Leaving it on by accident
     * therefore costs at most one session.
     */
    var agentMode by mutableStateOf(AgentMode.ASK)
        private set

    var pendingApproval by mutableStateOf<PendingApproval?>(null)
        private set

    private var rateState = AgentRateState()

    private var streamJob: Job? = null

    val isStreaming: Boolean get() = streamJob?.isActive == true

    val activeConversation: Conversation?
        get() = conversations.value.firstOrNull { it.id == activeConversationId.value }

    val hasProvider: Boolean get() = providers.value.active != null

    /** Whether the selected model is known to lack tool support. */
    val modelLacksToolSupport: Boolean
        get() {
            val provider = providers.value.active ?: return false
            val model = activeConversation?.model ?: provider.defaultModel
            return provider.model(model)?.supportsTools == false
        }

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

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { chats.setPinned(id, pinned) }
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

    // --- search -------------------------------------------------------------

    var searchQuery by mutableStateOf("")
        private set

    var searchResults by mutableStateOf<List<Pair<Conversation, ChatMessage>>>(emptyList())
        private set

    fun onSearchChange(query: String) {
        searchQuery = query
        viewModelScope.launch {
            searchResults = if (query.isBlank()) emptyList() else chats.search(query)
        }
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
        val images = attachments
        if ((text.isEmpty() && images.isEmpty()) || isStreaming) return

        composerText = ""
        attachments = emptyList()
        streamJob = viewModelScope.launch { runTurn(provider, text, images) }
    }

    private suspend fun runTurn(
        provider: LlmProvider,
        userText: String,
        images: List<ProcessedImage> = emptyList(),
    ) {
        val existing = activeConversationId.value?.let { id ->
            conversations.value.firstOrNull { it.id == id }
        }

        // Captured before the write: the messages flow reloads asynchronously,
        // so reading it back straight after inserting would race the insert.
        val priorMessages = if (existing != null) messages.value else emptyList()

        val conversation = existing ?: chats.createConversation(
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

        images.forEach { image ->
            chats.addAttachment(
                MessageAttachment(
                    id = image.id,
                    messageId = userMessage.id,
                    localPath = image.file.absolutePath,
                    mimeType = image.mimeType,
                    widthPx = image.widthPx,
                    heightPx = image.heightPx,
                    byteSize = image.byteSize,
                ),
            )
        }

        rateState = rateState.startTurn()

        // Images are inlined as data URIs on the newest user turn only. Sending
        // every past image on every request would be ruinously expensive and is
        // rarely what the user means.
        val history = (priorMessages + userMessage).toWire().toMutableList()
        if (images.isNotEmpty() && history.isNotEmpty()) {
            history[history.lastIndex] = history.last().copy(
                images = images.map(container.imageProcessor::toDataUri),
            )
        }

        runAgentLoop(provider, conversation, history)
    }

    /**
     * Streams a reply, executes any tool calls, and streams again with their
     * results — until the model stops asking for tools.
     *
     * Bounded by [MAX_TOOL_ROUNDS] as a backstop: the per-turn call limit
     * already caps typing requests, but a model looping on a read-only tool
     * would otherwise never terminate.
     */
    private suspend fun runAgentLoop(
        provider: LlmProvider,
        conversation: Conversation,
        initialHistory: List<WireMessage>,
    ) {
        val model = conversation.model?.takeIf { it.isNotBlank() } ?: provider.defaultModel
        if (model.isBlank()) {
            status = "Choose a model for this provider in Settings"
            return
        }

        var wire = trimToBudget(withHostContext(initialHistory))
        var round = 0

        while (round++ < MAX_TOOL_ROUNDS) {
            val result = streamOnce(provider, model, wire)

            // Text always gets persisted, whether or not tools were also called.
            if (result.text.isNotEmpty() || result.toolCalls.isEmpty()) {
                chats.addMessage(
                    ChatMessage(
                        conversationId = conversation.id,
                        role = ChatRole.ASSISTANT,
                        content = result.text,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        incomplete = result.failure != null,
                    ),
                )
            }

            result.failure?.let { status = describe(it) }
            if (result.toolCalls.isEmpty() || result.failure != null) return

            wire = wire + WireMessage(
                role = WireMessage.ASSISTANT,
                content = result.text.ifEmpty { null },
                toolCalls = result.toolCalls,
            )

            for (call in result.toolCalls) {
                val outcome = executeTool(call)
                wire = wire + WireMessage(
                    role = WireMessage.TOOL,
                    content = outcome,
                    toolCallId = call.id,
                )
                chats.addMessage(
                    ChatMessage(
                        conversationId = conversation.id,
                        role = ChatRole.SYSTEM,
                        content = "⌨ ${call.name}: $outcome",
                    ),
                )
            }
        }
    }

    private class StreamResult(
        val text: String,
        val toolCalls: List<WireToolCall>,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val failure: Throwable?,
    )

    private suspend fun streamOnce(
        provider: LlmProvider,
        model: String,
        wire: List<WireMessage>,
    ): StreamResult {
        val builder = StringBuilder()
        var toolCalls = emptyList<WireToolCall>()
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var failure: Throwable? = null

        streamingContent = ""
        try {
            container.llmClient.streamChat(
                provider = provider,
                apiKey = container.llmProviderRepository.apiKey(provider),
                model = model,
                messages = wire,
                // OFF withholds the tools entirely, so the model is never even
                // told that typing is possible.
                tools = if (agentMode == AgentMode.OFF) null else AgentTools.definitions(),
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

                    is ChatEvent.ToolCalls -> toolCalls = event.calls
                    ChatEvent.Completed -> Unit
                }
            }
        } catch (e: Throwable) {
            failure = e
        }
        streamingContent = null

        return StreamResult(builder.toString(), toolCalls, promptTokens, completionTokens, failure)
    }

    // --- tools --------------------------------------------------------------

    private suspend fun executeTool(call: WireToolCall): String = when (call.name) {
        AgentTools.GET_HOST_STATUS -> container.hidController.hostStatus()

        AgentTools.TYPE_TO_HOST -> {
            val request = AgentTools.parseTypeToHost(call.id, call.arguments)
            if (request == null) {
                "Error: could not read the arguments for this call"
            } else {
                runTypeToHost(request)
            }
        }

        AgentTools.PRESS_KEYS -> {
            val request = AgentTools.parsePressKeys(call.id, call.arguments)
            when {
                request == null -> "Error: could not read the arguments for this call"
                !connected() -> "Error: no device is connected"
                // A key combination goes through the same approval path as
                // text — ctrl+alt+delete is not less consequential than typing.
                !approveCombo(request.combo) -> "Declined by the user"
                else -> describeResult(container.hidController.pressCombo(request.combo))
            }
        }

        else -> "Error: unknown tool ${call.name}"
    }

    private suspend fun runTypeToHost(request: TypeToHostRequest): String {
        val agentSettings = settings.value.agent
        val effectiveMode = AgentGuardrails.effectiveMode(
            requested = agentMode,
            state = rateState,
            settings = agentSettings,
            connected = connected(),
        )
        if (effectiveMode != agentMode) agentMode = effectiveMode

        if (!connected()) {
            audit(request, approved = false, result = "No device connected")
            return "Error: no device is connected"
        }

        val decision = AgentGuardrails.evaluate(
            request = request,
            mode = effectiveMode,
            settings = agentSettings,
            state = rateState,
        )

        val approved = when (decision) {
            is AgentDecision.Deny -> {
                audit(request, approved = false, result = decision.reason)
                return "Declined: ${decision.reason}"
            }

            is AgentDecision.Allow -> true

            is AgentDecision.RequireApproval -> awaitApproval(
                request,
                decision.reason,
                AgentGuardrails.matchingPatterns(request.text, agentSettings.blocklist),
            )
        }

        if (!approved) {
            audit(request, approved = false, result = "Declined by the user")
            return "Declined by the user"
        }

        rateState = rateState.recordCall(System.currentTimeMillis())
        val result = container.hidController.typeText(request.text, request.pressEnter)
        val described = describeResult(result)
        audit(request, approved = true, result = described)
        return described
    }

    private fun describeResult(result: TypeResult): String = when (result) {
        is TypeResult.Delivered -> buildString {
            append("Typed ${result.chars} characters")
            if (result.skipped > 0) {
                append("; ${result.skipped} could not be produced by this keyboard layout")
            }
        }

        is TypeResult.Partial ->
            "Interrupted after ${result.charsDelivered} characters: ${result.cause.message}"

        is TypeResult.Rejected -> "Failed: ${result.cause.message}"
    }

    private suspend fun awaitApproval(
        request: TypeToHostRequest,
        reason: ApprovalReason,
        matched: List<String>,
    ): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingApproval = PendingApproval(request, reason, matched, answer)
        return answer.await()
    }

    private suspend fun approveCombo(combo: String): Boolean {
        val effectiveMode = AgentGuardrails.effectiveMode(
            requested = agentMode,
            state = rateState,
            settings = settings.value.agent,
            connected = connected(),
        )
        if (effectiveMode == AgentMode.AUTO) return true
        return awaitApproval(
            TypeToHostRequest(callId = UUID.randomUUID().toString(), text = combo),
            ApprovalReason.ASK_MODE,
            emptyList(),
        )
    }

    fun resolveApproval(allow: Boolean) {
        pendingApproval?.answer?.complete(allow)
        pendingApproval = null
    }

    private suspend fun audit(request: TypeToHostRequest, approved: Boolean, result: String) {
        chats.recordAgentEvent(
            AgentAuditEntry(
                id = UUID.randomUUID().toString(),
                deviceAddress = container.hidController.activeAddress.value,
                mode = agentMode,
                preview = request.text.take(80),
                charCount = request.text.length,
                approved = approved,
                result = result,
                atEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    // --- agent mode ---------------------------------------------------------

    /** Named to avoid clashing with the JVM setter generated for [agentMode]. */
    fun changeAgentMode(mode: AgentMode) {
        agentMode = mode
        rateState = rateState.copy(
            autoSinceEpochMs = if (mode == AgentMode.AUTO) System.currentTimeMillis() else null,
        )
    }

    /** Kill switch: back to Ask, and drop any key the host might be holding. */
    fun stopAgentTyping() {
        agentMode = AgentMode.ASK
        rateState = rateState.copy(autoSinceEpochMs = null)
        pendingApproval?.answer?.complete(false)
        pendingApproval = null
        viewModelScope.launch {
            container.hidController.releaseAllKeys()
            status = "Agent typing stopped"
        }
    }

    // --- context ------------------------------------------------------------

    /**
     * Optionally tells the model which machine it is typing into.
     *
     * Off by default: it is useful (the model gets the shell and shortcuts
     * right) but it also sends device details to a third party, so it is the
     * user's call.
     */
    private fun withHostContext(history: List<WireMessage>): List<WireMessage> {
        if (!settings.value.agent.injectHostContext) return history
        val address = container.hidController.activeAddress.value ?: return history
        val device = container.roster.value.find(address) ?: return history

        val context = buildString {
            append("The connected device is \"${device.displayName}\"")
            append(", operating system: ${device.hostOs.name.lowercase()}")
            append(", keyboard layout: ${container.hidController.layout.name}.")
            append(" Prefer commands and shortcuts appropriate to it.")
        }
        return listOf(WireMessage(WireMessage.SYSTEM, context)) + history
    }

    /**
     * Trims oldest-first to the configured character budget.
     *
     * Affects only what is sent to the API — nothing is deleted from stored
     * history, which is kept forever.
     */
    private fun trimToBudget(history: List<WireMessage>): List<WireMessage> {
        val budget = settings.value.agent.historyCharBudget
        if (budget <= 0) return history

        var total = 0
        val kept = ArrayDeque<WireMessage>()
        for (message in history.asReversed()) {
            val size = (message.content?.length ?: 0) + 32
            if (total + size > budget && kept.isNotEmpty()) break
            kept.addFirst(message)
            total += size
        }
        return kept.toList()
    }

    private fun List<ChatMessage>.toWire(): List<WireMessage> = this
        .filter { it.error == null && it.role != ChatRole.SYSTEM }
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

    private fun connected() = transportState.value == TransportState.CONNECTED

    private fun describe(error: Throwable): String = when (error) {
        is InvalidApiKey -> "Check your API key in Settings"
        is RateLimited -> error.retryAfterSeconds
            ?.let { "Rate limited — try again in ${it}s" }
            ?: "Rate limited by the provider"

        is UnknownModel -> "Model `${error.model}` was rejected — pick another in Settings"
        is ToolsUnsupported -> "This model does not support tool calls, so agent typing is off"
        is VisionUnsupported -> "This model cannot read images — pick a vision model in Settings"
        else -> error.message ?: "Request failed"
    }

    fun stopGenerating() {
        streamJob?.cancel()
        streamingContent = null
        pendingApproval?.answer?.complete(false)
        pendingApproval = null
        status = "Stopped"
    }

    // --- user-initiated sending to the host ---------------------------------

    fun requestHostSend(text: String) {
        if (!connected()) {
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

    private companion object {
        const val MAX_TOOL_ROUNDS = 6
    }
}
