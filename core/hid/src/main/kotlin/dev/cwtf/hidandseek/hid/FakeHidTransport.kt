package dev.cwtf.hidandseek.hid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An in-memory [HidTransport] that records reports instead of transmitting them.
 *
 * Backs the unit tests and the app's developer "dry run" mode, where the full
 * typing pipeline can be exercised with no host present and no risk of typing
 * into someone's machine.
 */
class FakeHidTransport(
    initialState: TransportState = TransportState.REGISTERED,
) : HidTransport {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _hostLedState = MutableStateFlow(LedState.UNKNOWN)
    override val hostLedState: StateFlow<LedState> = _hostLedState.asStateFlow()

    private val _reports = mutableListOf<KeyboardReport>()

    /** Every keyboard report sent, in order, including the key-up releases. */
    val reports: List<KeyboardReport> get() = _reports.toList()

    /** Only the key-down reports — usually what a test wants to assert on. */
    val keyDownReports: List<KeyboardReport> get() = _reports.filterNot { it.isRelease }

    val consumerReports = mutableListOf<ConsumerReport>()

    var connectedTarget: HidTarget? = null
        private set

    /** When set, every subsequent send fails with this. Simulates a dead link. */
    var sendFailure: Throwable? = null

    /** When set, [connect] fails with this. */
    var connectFailure: Throwable? = null

    /** Fails the Nth send (1-based) then clears itself. Simulates a mid-send drop. */
    var failSendAtIndex: Int? = null

    private var sendCount = 0

    override suspend fun connect(target: HidTarget): Result<Unit> {
        connectFailure?.let {
            _state.value = TransportState.DISCONNECTED
            return Result.failure(it)
        }
        _state.value = TransportState.CONNECTING
        connectedTarget = target
        _state.value = TransportState.CONNECTED
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _state.value = TransportState.DISCONNECTING
        connectedTarget = null
        _state.value = TransportState.DISCONNECTED
    }

    override suspend fun sendKeyboardReport(report: KeyboardReport): Result<Unit> {
        sendCount++
        sendFailure?.let { return Result.failure(it) }
        failSendAtIndex?.let { target ->
            if (sendCount == target) {
                failSendAtIndex = null
                return Result.failure(IllegalStateException("Simulated link failure"))
            }
        }
        if (!_state.value.canSend) {
            return Result.failure(IllegalStateException("Not connected (${_state.value})"))
        }
        _reports += report
        return Result.success(Unit)
    }

    override suspend fun sendConsumerReport(report: ConsumerReport): Result<Unit> {
        sendFailure?.let { return Result.failure(it) }
        if (!_state.value.canSend) {
            return Result.failure(IllegalStateException("Not connected (${_state.value})"))
        }
        consumerReports += report
        return Result.success(Unit)
    }

    // --- test controls -------------------------------------------------------

    fun setState(state: TransportState) {
        _state.value = state
    }

    fun setHostLeds(leds: LedState) {
        _hostLedState.value = leds
    }

    fun clearRecordings() {
        _reports.clear()
        consumerReports.clear()
        sendCount = 0
    }

    /**
     * Reconstructs the text the host would have received, by decoding recorded
     * key-down reports back through [layout].
     *
     * This is what makes round-trip assertions possible: map text down to
     * reports, decode reports back up to text, and require the two to match.
     */
    fun decodeTypedText(layout: KeyLayout): String {
        val sb = StringBuilder()
        for (report in keyDownReports) {
            val usage = report.keys.firstOrNull() ?: continue
            when (usage) {
                Usage.BACKSPACE -> if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                Usage.ENTER -> sb.append('\n')
                Usage.TAB -> sb.append('\t')
                else -> layout.decode(report.modifiers, usage)?.let(sb::append)
            }
        }
        return sb.toString()
    }
}
