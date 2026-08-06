package dev.cwtf.hidandseek.ui.type

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cwtf.hidandseek.AppContainer
import dev.cwtf.hidandseek.data.Snippet
import dev.cwtf.hidandseek.data.Snippets
import dev.cwtf.hidandseek.data.TextFileImportException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import dev.cwtf.hidandseek.bluetooth.HidController
import dev.cwtf.hidandseek.bluetooth.SendPreview
import dev.cwtf.hidandseek.bluetooth.TypeResult
import dev.cwtf.hidandseek.hid.Modifiers
import dev.cwtf.hidandseek.data.DeviceRecord
import dev.cwtf.hidandseek.data.HostOsTag
import dev.cwtf.hidandseek.hid.HidTarget
import dev.cwtf.hidandseek.hid.ReconnectPolicy
import dev.cwtf.hidandseek.hid.DrainDecision
import dev.cwtf.hidandseek.hid.DrainPlan
import dev.cwtf.hidandseek.hid.EditTrigger
import dev.cwtf.hidandseek.hid.TransportState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SendMode { STAGED, LIVE }

data class BroadcastResult(val address: String, val name: String, val status: String)

data class BroadcastState(
    val results: List<BroadcastResult>,
    val aborted: Boolean = false,
    val finished: Boolean = false,
)

/** Asked when switching into live mode with text already staged. */
enum class ModeSwitchChoice { SEND_FIRST, KEEP_UNSENT, CLEAR }

class TypeViewModel(private val container: AppContainer) : ViewModel() {

    private val controller: HidController = container.hidController

    var buffer by mutableStateOf(TextFieldValue())
        private set

    var attachedFileName by mutableStateOf<String?>(null)
        private set

    var mode by mutableStateOf(SendMode.STAGED)
        private set

    var pendingCount by mutableStateOf(0)
        private set

    var status by mutableStateOf<String?>(null)

    /** Non-null while a retraction is waiting on the user (SPEC 5.4.2 rule 5). */
    var overCapPrompt by mutableStateOf<DrainDecision.AskOverCap?>(null)
        private set

    var modeSwitchPrompt by mutableStateOf(false)
        private set

    val transportState = controller.transport.state
    val progress = controller.progress

    private var settleJob: Job? = null
    private var sendJob: Job? = null

    val isSending: Boolean get() = sendJob?.isActive == true

    // --- editing ------------------------------------------------------------

    fun onBufferChange(value: TextFieldValue) {
        val previousText = buffer.text
        buffer = value
        if (mode == SendMode.LIVE) {
            handleLiveEdit(previousText, value)
        }
    }

    private fun handleLiveEdit(previousText: String, value: TextFieldValue) {
        val compositionStart = value.composition?.start
        val decision = controller.drain.onEdit(
            buffer = value.text,
            compositionStart = compositionStart,
            trigger = detectTrigger(previousText, value.text),
        )
        applyDecision(decision)
        refreshPending()
    }

    /**
     * Classifies an edit so the flush triggers can fire.
     *
     * Only growth at the tail counts as a space/enter trigger; a paste or an
     * edit elsewhere falls back to the settle delay.
     */
    private fun detectTrigger(previous: String, current: String): EditTrigger = when {
        current.length <= previous.length -> EditTrigger.TYPING
        current.length - previous.length > 1 -> EditTrigger.PASTE
        current.lastOrNull() == '\n' -> EditTrigger.ENTER
        current.lastOrNull() == ' ' -> EditTrigger.SPACE
        else -> EditTrigger.TYPING
    }

    private fun applyDecision(decision: DrainDecision) {
        when (decision) {
            is DrainDecision.Idle -> Unit

            is DrainDecision.Defer -> {
                settleJob?.cancel()
                settleJob = viewModelScope.launch {
                    delay(decision.afterMs.toLong())
                    applyDecision(
                        controller.drain.onSettleElapsed(buffer.text, buffer.composition?.start),
                    )
                }
            }

            is DrainDecision.Execute -> {
                settleJob?.cancel()
                execute(decision.plan)
            }

            is DrainDecision.AskOverCap -> {
                settleJob?.cancel()
                overCapPrompt = decision
            }
        }
    }

    private fun execute(plan: DrainPlan) {
        if (plan is DrainPlan.Idle) return
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            report(controller.execute(plan))
            refreshPending()
        }
    }

    fun catchUpNow() {
        applyDecision(controller.drain.onManualFlush(buffer.text, buffer.composition?.start))
    }

    private fun refreshPending() {
        pendingCount = if (mode == SendMode.LIVE) {
            controller.drain.pendingCount(buffer.text, buffer.composition?.start)
        } else {
            0
        }
    }

    // --- over-cap prompt ----------------------------------------------------

    fun resolveOverCap(retype: Boolean) {
        val prompt = overCapPrompt ?: return
        overCapPrompt = null
        if (retype) {
            execute(prompt.plan)
        } else {
            // Skip and resync: leave the host as it is and stop chasing it.
            execute(DrainPlan.Resync(controller.drain.sentText + prompt.plan.thenType))
            status = "Host left out of sync — it keeps what it already had"
        }
        refreshPending()
    }

    // --- mode ---------------------------------------------------------------

    fun requestMode(next: SendMode) {
        if (next == mode) return
        if (next == SendMode.LIVE && buffer.text.isNotEmpty()) {
            modeSwitchPrompt = true
            return
        }
        applyMode(next)
    }

    fun resolveModeSwitch(choice: ModeSwitchChoice) {
        modeSwitchPrompt = false
        when (choice) {
            ModeSwitchChoice.SEND_FIRST -> {
                controller.drain.reset()
                applyMode(SendMode.LIVE)
                catchUpNow()
            }

            ModeSwitchChoice.KEEP_UNSENT -> {
                controller.drain.assumeHostHolds(buffer.text)
                applyMode(SendMode.LIVE)
            }

            ModeSwitchChoice.CLEAR -> {
                buffer = TextFieldValue()
                attachedFileName = null
                controller.drain.reset()
                applyMode(SendMode.LIVE)
            }
        }
    }

    fun dismissModeSwitch() {
        modeSwitchPrompt = false
    }

    /** Named to avoid clashing with the JVM setter generated for [mode]. */
    private fun applyMode(next: SendMode) {
        mode = next
        if (next == SendMode.STAGED) {
            settleJob?.cancel()
        } else {
            controller.drain.reset()
        }
        refreshPending()
    }

    // --- send options -------------------------------------------------------

    var appendEnter by mutableStateOf(false)
        private set
    var stripIndent by mutableStateOf(false)
        private set

    fun toggleAppendEnter() {
        appendEnter = !appendEnter
    }

    fun toggleStripIndent() {
        stripIndent = !stripIndent
    }

    /** Applies the send options to the raw buffer. */
    private fun prepared(text: String): String =
        if (stripIndent) text.lines().joinToString("\n") { it.trimStart() } else text

    // --- preview ------------------------------------------------------------

    var preview by mutableStateOf<SendPreview?>(null)
        private set

    fun showPreview() {
        preview = controller.previewSend(prepared(buffer.text), appendEnter)
    }

    fun dismissPreview() {
        preview = null
    }

    // --- staged send --------------------------------------------------------

    /** A send waiting on confirmation because of its size. */
    var pendingConfirm by mutableStateOf<Int?>(null)
        private set

    fun send() {
        if (isSending) return
        val text = prepared(buffer.text)
        if (text.isEmpty()) return

        val threshold = container.settings.value.typing.confirmSendOverChars
        if (threshold > 0 && text.length > threshold) {
            pendingConfirm = text.length
            return
        }
        performSend(text)
    }

    fun confirmLongSend() {
        pendingConfirm = null
        performSend(prepared(buffer.text))
    }

    fun cancelLongSend() {
        pendingConfirm = null
    }

    private fun performSend(text: String) {
        preview = null
        sendJob = viewModelScope.launch {
            report(controller.typeText(text, appendEnter))
        }
    }

    /** Types the clipboard rather than the buffer. */
    fun sendClipboard(text: String?) {
        if (isSending) return
        if (text.isNullOrEmpty()) {
            status = "Clipboard is empty"
            return
        }
        sendJob = viewModelScope.launch {
            report(controller.typeText(prepared(text), appendEnter))
        }
    }

    // --- macros -------------------------------------------------------------

    fun pressKey(usage: Int, modifiers: Modifiers) {
        if (isSending) return
        sendJob = viewModelScope.launch { report(controller.pressKey(usage, modifiers)) }
    }

    fun pressCombo(combo: String) {
        if (isSending) return
        sendJob = viewModelScope.launch { report(controller.pressCombo(combo)) }
    }

    fun pressConsumerKey(usage: Int) {
        if (isSending) return
        sendJob = viewModelScope.launch { report(controller.pressConsumerKey(usage)) }
    }

    // --- snippets -----------------------------------------------------------

    val snippets: StateFlow<Snippets> = container.snippetRepository.snippets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Snippets())

    /**
     * True while the buffer holds text from a snippet marked sensitive.
     *
     * Drives FLAG_SECURE, so a password staged for typing does not end up in a
     * screenshot or the recents thumbnail.
     */
    var bufferIsSensitive by mutableStateOf(false)
        private set

    fun saveSnippet(name: String, sensitive: Boolean) {
        val content = buffer.text
        if (content.isEmpty() || name.isBlank()) return
        viewModelScope.launch {
            container.snippetRepository.save(name.trim(), content, sensitive)
            status = "Saved snippet \"$name\""
        }
    }

    fun loadSnippet(snippet: Snippet) {
        val content = container.snippetRepository.contentOf(snippet)
        buffer = TextFieldValue(content, selection = TextRange(content.length))
        attachedFileName = null
        bufferIsSensitive = snippet.sensitive
        if (mode == SendMode.LIVE) controller.drain.reset()
        refreshPending()
    }

    fun deleteSnippet(id: String) {
        viewModelScope.launch { container.snippetRepository.delete(id) }
    }

    // --- text-file import ---------------------------------------------------

    fun attachFile(uri: Uri) {
        if (isSending) return
        viewModelScope.launch {
            status = "Loading file..."
            container.textFileReader.read(uri).fold(
                onSuccess = { file ->
                    buffer = TextFieldValue(
                        file.text,
                        selection = TextRange(file.text.length),
                    )
                    attachedFileName = file.name
                    bufferIsSensitive = false
                    if (mode == SendMode.LIVE) controller.drain.reset()
                    refreshPending()
                    status = "Loaded \"${file.name}\" (${file.text.length} characters)"
                },
                onFailure = { error ->
                    status = if (error is TextFileImportException) {
                        error.message
                    } else {
                        "Could not read that file"
                    }
                },
            )
        }
    }

    // --- broadcast send -----------------------------------------------------

    var broadcast by mutableStateOf<BroadcastState?>(null)
        private set

    private var broadcastJob: Job? = null

    /**
     * Sends the buffer to several devices, one after another.
     *
     * Sequential by necessity, not by choice: the platform holds one HID
     * connection at a time, so each device is connected, typed into, and
     * dropped before the next.
     */
    fun startBroadcast(devices: List<DeviceRecord>) {
        if (devices.isEmpty() || isSending) return
        val text = prepared(buffer.text)
        if (text.isEmpty()) return

        broadcastJob = viewModelScope.launch {
            broadcast = BroadcastState(
                results = devices.map { BroadcastResult(it.address, it.displayName, "Waiting") },
            )

            for ((index, device) in devices.withIndex()) {
                if (broadcast?.aborted == true) {
                    updateBroadcast(index) { it.copy(status = "Skipped") }
                    continue
                }

                updateBroadcast(index) { it.copy(status = "Connecting…") }
                val connected = controller.transport
                    .connect(HidTarget(device.address, device.name))

                if (connected.isFailure) {
                    updateBroadcast(index) {
                        it.copy(status = connected.exceptionOrNull()?.message ?: "Could not connect")
                    }
                    continue
                }

                controller.activeAddress.value = device.address
                updateBroadcast(index) { it.copy(status = "Typing…") }

                val result = controller.typeText(text, appendEnter)
                updateBroadcast(index) {
                    it.copy(
                        status = when (result) {
                            is TypeResult.Delivered -> "Sent ${result.chars} characters"
                            is TypeResult.Partial -> "Stopped at ${result.charsDelivered}"
                            is TypeResult.Rejected -> result.cause.message ?: "Failed"
                        },
                    )
                }
                controller.transport.disconnect()
            }

            broadcast = broadcast?.copy(finished = true)
        }
    }

    private fun updateBroadcast(index: Int, transform: (BroadcastResult) -> BroadcastResult) {
        broadcast = broadcast?.let { state ->
            state.copy(
                results = state.results.toMutableList().apply { this[index] = transform(this[index]) },
            )
        }
    }

    /** Stops after the device currently being typed into, not mid-send. */
    fun abortBroadcast() {
        broadcast = broadcast?.copy(aborted = true)
    }

    fun dismissBroadcast() {
        broadcastJob?.cancel()
        broadcast = null
    }

    // --- live-mode watermark ------------------------------------------------

    /**
     * How much of the buffer the host already holds.
     *
     * Rendered as a dimmed prefix so the boundary between sent and pending text
     * is visible without having to trust a counter.
     */
    val sentPrefixLength: Int
        get() {
            if (mode != SendMode.LIVE) return 0
            val sent = controller.drain.sentText
            val current = buffer.text
            var i = 0
            while (i < sent.length && i < current.length && sent[i] == current[i]) i++
            return i
        }

    fun cancelSend() {
        sendJob?.cancel()
        viewModelScope.launch { controller.releaseAllKeys() }
        status = "Send stopped"
    }

    fun clear() {
        buffer = TextFieldValue()
        attachedFileName = null
        bufferIsSensitive = false
        if (mode == SendMode.LIVE) controller.drain.reset()
        refreshPending()
    }

    fun releaseAllKeys() {
        viewModelScope.launch {
            controller.releaseAllKeys()
            status = "All keys released"
        }
    }

    private fun report(result: TypeResult) {
        val delivered = when (result) {
            is TypeResult.Delivered -> result.chars
            is TypeResult.Partial -> result.charsDelivered
            is TypeResult.Rejected -> 0
        }
        controller.activeAddress.value?.let { address ->
            viewModelScope.launch {
                container.deviceRosterRepository.addCharsSent(address, delivered)
            }
        }

        status = when (result) {
            is TypeResult.Delivered -> when {
                result.chars == 0 -> null
                result.skipped > 0 -> "Sent ${result.chars} characters, skipped ${result.skipped}"
                else -> "Sent ${result.chars} characters"
            }

            is TypeResult.Partial ->
                "Stopped after ${result.charsDelivered} characters: ${result.cause.message}"

            is TypeResult.Rejected -> result.cause.message
        }
    }

    // --- connection ---------------------------------------------------------

    fun connect(address: String, name: String) {
        viewModelScope.launch {
            controller.transport.register()
            val result = controller.transport.connect(HidTarget(address, name))

            status = result.fold(
                onSuccess = { "Connected to $name" },
                onFailure = { it.message },
            )

            if (result.isSuccess) {
                // Using a host is what puts it in the roster — there is no
                // separate "save this device" step to forget to do.
                container.deviceRosterRepository.recordConnection(
                    address = address,
                    name = name,
                    atEpochMs = System.currentTimeMillis(),
                )
                // Set last: this drives per-device settings resolution, and the
                // roster entry has to exist before it resolves against it.
                controller.activeAddress.value = address

                controller.drain.onReconnected(
                    policy = container.settings.value.live.reconnectPolicy,
                    buffer = buffer.text,
                    compositionStart = buffer.composition?.start,
                )
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            controller.transport.disconnect()
            controller.drain.onDisconnected()
            controller.activeAddress.value = null
        }
    }

    /** Adopts a newly paired host and connects to it. */
    fun adoptAndConnect(target: HidTarget, hostOs: HostOsTag, nickname: String?, layoutId: String) {
        viewModelScope.launch {
            container.deviceRosterRepository.upsert(
                DeviceRecord(
                    address = target.address,
                    name = target.name,
                    nickname = nickname,
                    layoutId = layoutId,
                    hostOs = hostOs,
                ),
            )
            connect(target.address, target.name)
        }
    }

    /** Types a sample so the configured layout can be checked at a glance. */
    fun testTyping() {
        viewModelScope.launch {
            report(controller.typeText("HID & Seek test 123"))
        }
    }

    fun bondedDevices(): List<HidTarget> = controller.transport.bondedDevices()

    val knownAddresses: Set<String>
        get() = container.roster.value.devices.map { it.address }.toSet()

    /** Known devices first, then anything paired that is not in the roster yet. */
    fun pickerDevices(): List<DeviceRecord> {
        val roster = container.roster.value
        val known = roster.byRecency
        val knownAddresses = known.map { it.address }.toSet()
        val unadopted = controller.transport.bondedDevices()
            .filterNot { it.address in knownAddresses }
            .map { DeviceRecord(address = it.address, name = it.name) }
        return known + unadopted
    }

    val activeAddress: String? get() = controller.activeAddress.value

    val activeHostOs: HostOsTag
        get() = activeAddress
            ?.let(container.roster.value::find)
            ?.hostOs
            ?: HostOsTag.UNKNOWN

    fun registerAsKeyboard() {
        controller.transport.register().onFailure { status = it.message }
    }

    val canSend: Boolean get() = transportState.value == TransportState.CONNECTED
}
