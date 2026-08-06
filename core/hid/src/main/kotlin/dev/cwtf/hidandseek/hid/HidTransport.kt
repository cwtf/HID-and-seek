package dev.cwtf.hidandseek.hid

import kotlinx.coroutines.flow.StateFlow

/** A host the app can act as a keyboard for. */
data class HidTarget(
    val address: String,
    val name: String,
)

enum class TransportState {
    /** No profile proxy, or the app has not registered as a HID device. */
    UNREGISTERED,

    /** SDP record published — the phone is visible to hosts as a keyboard. */
    REGISTERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
    ;

    val canSend: Boolean get() = this == CONNECTED
}

/**
 * Lock-key state as reported *by the host* via output reports.
 *
 * [capsLock] matters for correctness, not just display: when the host has Caps
 * Lock on, the shift state the layout asks for produces the opposite case.
 */
data class LedState(
    val capsLock: Boolean = false,
    val numLock: Boolean = false,
    val scrollLock: Boolean = false,
) {
    companion object {
        val UNKNOWN = LedState()

        /** Decodes the LED bitmask from a host output report. */
        fun fromBits(bits: Int) = LedState(
            numLock = bits and 0x01 != 0,
            capsLock = bits and 0x02 != 0,
            scrollLock = bits and 0x04 != 0,
        )
    }
}

/**
 * Delivery of HID reports to a host.
 *
 * Implementations are the only Android-aware part of the typing pipeline;
 * everything above this interface is pure Kotlin so it can be tested without a
 * device. See `FakeHidTransport` for the test double.
 */
interface HidTransport {
    val state: StateFlow<TransportState>
    val hostLedState: StateFlow<LedState>

    suspend fun connect(target: HidTarget): Result<Unit>
    suspend fun disconnect()

    suspend fun sendKeyboardReport(report: KeyboardReport): Result<Unit>
    suspend fun sendConsumerReport(report: ConsumerReport): Result<Unit>
}

/**
 * Releases every key and modifier.
 *
 * Called on cancel, on error, and by the panic key. A held modifier that is
 * never released leaves the host in a broken state that survives disconnecting,
 * so this is deliberately best-effort and never throws.
 */
suspend fun HidTransport.releaseAllKeys() {
    runCatching { sendKeyboardReport(KeyboardReport.RELEASE_ALL) }
}
