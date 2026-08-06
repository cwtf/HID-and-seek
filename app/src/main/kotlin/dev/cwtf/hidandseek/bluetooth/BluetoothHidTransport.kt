package dev.cwtf.hidandseek.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.cwtf.hidandseek.hid.ConsumerReport
import dev.cwtf.hidandseek.hid.HidTarget
import dev.cwtf.hidandseek.hid.HidTransport
import dev.cwtf.hidandseek.hid.KeyboardReport
import dev.cwtf.hidandseek.hid.LedState
import dev.cwtf.hidandseek.hid.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Bluetooth HID Device implementation of [HidTransport].
 *
 * Registers the app as a keyboard so hosts can pair with it, then delivers
 * reports over the interrupt channel.
 *
 * Only one host can be connected at a time — the platform's HID Device service
 * tracks a single device, so connecting elsewhere implicitly drops the current
 * link. Device *selection* is therefore a user-facing choice rather than
 * something the transport can multiplex.
 *
 * Permission is checked rather than assumed: every entry point returns a
 * failure when `BLUETOOTH_CONNECT` is missing, so a denied permission surfaces
 * as a handled error instead of a `SecurityException`.
 */
@SuppressLint("MissingPermission") // guarded by hasConnectPermission()
class BluetoothHidTransport(
    private val context: Context,
) : HidTransport {

    private val _state = MutableStateFlow(TransportState.UNREGISTERED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _hostLedState = MutableStateFlow(LedState.UNKNOWN)
    override val hostLedState: StateFlow<LedState> = _hostLedState.asStateFlow()

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var proxy: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    /** Reports must be delivered off the main thread. */
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "HID & Seek",
        "Keyboard input from an Android device",
        "HID & Seek",
        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
        HidReportDescriptor.BYTES,
    )

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "HID app registered=$registered")
            _state.value = if (registered) TransportState.REGISTERED else TransportState.UNREGISTERED
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.i(TAG, "connection state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    _state.value = TransportState.CONNECTED
                }

                BluetoothProfile.STATE_CONNECTING -> _state.value = TransportState.CONNECTING
                BluetoothProfile.STATE_DISCONNECTING -> _state.value = TransportState.DISCONNECTING
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    // Still registered, so the phone remains pairable as a keyboard.
                    _state.value = if (proxy != null) {
                        TransportState.REGISTERED
                    } else {
                        TransportState.DISCONNECTED
                    }
                    _hostLedState.value = LedState.UNKNOWN
                }
            }
        }

        override fun onSetReport(
            device: BluetoothDevice?,
            type: Byte,
            id: Byte,
            data: ByteArray?,
        ) {
            readLedState(data)
            proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            readLedState(data)
        }

        override fun onGetReport(
            device: BluetoothDevice?,
            type: Byte,
            id: Byte,
            bufferSize: Int,
        ) {
            // Hosts occasionally poll for current state; an all-keys-up report
            // is always a truthful answer because we never leave keys held.
            proxy?.replyReport(device, type, id, KeyboardReport.RELEASE_ALL.toBytes())
        }
    }

    /** Decodes the LED bitmask the host pushes to us. */
    private fun readLedState(data: ByteArray?) {
        val bits = data?.lastOrNull()?.toInt() ?: return
        _hostLedState.value = LedState.fromBits(bits)
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxyRef: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hid = proxyRef as? BluetoothHidDevice ?: return
            proxy = hid
            hid.registerApp(sdpSettings, null, null, callbackExecutor, hidCallback)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            proxy = null
            connectedDevice = null
            _state.value = TransportState.UNREGISTERED
        }
    }

    // --- registration --------------------------------------------------------

    /**
     * Publishes the SDP record so hosts can discover and pair with the phone.
     *
     * Idempotent; safe to call whenever the app comes to the foreground.
     */
    fun register(): Result<Unit> {
        if (!hasConnectPermission()) return Result.failure(MissingBluetoothPermission)
        val adapter = adapter ?: return Result.failure(BluetoothUnavailable)
        if (!adapter.isEnabled) return Result.failure(BluetoothDisabled)
        if (proxy != null) return Result.success(Unit)

        val ok = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        return if (ok) {
            Result.success(Unit)
        } else {
            Result.failure(HidProfileUnsupported)
        }
    }

    /** Stops advertising as a keyboard and releases the profile proxy. */
    fun unregister() {
        val hid = proxy ?: return
        if (hasConnectPermission()) {
            connectedDevice?.let { hid.disconnect(it) }
            hid.unregisterApp()
        }
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        proxy = null
        connectedDevice = null
        _state.value = TransportState.UNREGISTERED
    }

    // --- connection ----------------------------------------------------------

    override suspend fun connect(target: HidTarget): Result<Unit> {
        if (!hasConnectPermission()) return Result.failure(MissingBluetoothPermission)
        val hid = proxy ?: return Result.failure(NotRegistered)
        val adapter = adapter ?: return Result.failure(BluetoothUnavailable)

        val device = runCatching { adapter.getRemoteDevice(target.address) }
            .getOrElse { return Result.failure(it) }

        // Switching hosts means dropping the current one first; the platform
        // only tracks a single HID connection.
        connectedDevice?.takeIf { it.address != device.address }?.let { hid.disconnect(it) }

        _state.value = TransportState.CONNECTING
        if (!hid.connect(device)) {
            _state.value = TransportState.REGISTERED
            return Result.failure(ConnectRejected)
        }

        val reached = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            state.first { it == TransportState.CONNECTED }
        }
        return if (reached != null) {
            Result.success(Unit)
        } else {
            Result.failure(ConnectTimeout)
        }
    }

    override suspend fun disconnect() {
        if (!hasConnectPermission()) return
        val hid = proxy ?: return
        connectedDevice?.let { hid.disconnect(it) }
    }

    // --- sending -------------------------------------------------------------

    override suspend fun sendKeyboardReport(report: KeyboardReport): Result<Unit> =
        send(KeyboardReport.REPORT_ID, report.toBytes())

    override suspend fun sendConsumerReport(report: ConsumerReport): Result<Unit> =
        send(ConsumerReport.REPORT_ID, report.toBytes())

    private fun send(reportId: Int, payload: ByteArray): Result<Unit> {
        if (!hasConnectPermission()) return Result.failure(MissingBluetoothPermission)
        val hid = proxy ?: return Result.failure(NotRegistered)
        val device = connectedDevice ?: return Result.failure(NotConnected)

        return if (hid.sendReport(device, reportId, payload)) {
            Result.success(Unit)
        } else {
            Result.failure(ReportRejected)
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Hosts already bonded with this phone, offered for adoption into the roster. */
    fun bondedDevices(): List<HidTarget> {
        if (!hasConnectPermission()) return emptyList()
        return adapter?.bondedDevices.orEmpty().map { HidTarget(it.address, it.name ?: it.address) }
    }

    private companion object {
        const val TAG = "HidTransport"
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}

// Failure causes are objects rather than strings so the UI can map each to a
// specific recovery action instead of showing a generic error.
object MissingBluetoothPermission : Exception("Bluetooth permission not granted")
object BluetoothUnavailable : Exception("This device has no Bluetooth adapter")
object BluetoothDisabled : Exception("Bluetooth is turned off")
object HidProfileUnsupported : Exception("This device does not support the Bluetooth HID Device profile")
object NotRegistered : Exception("Not registered as a HID keyboard yet")
object NotConnected : Exception("No host is connected")
object ConnectRejected : Exception("The host refused the connection")
object ConnectTimeout : Exception("The host did not respond in time")
object ReportRejected : Exception("The host rejected a report")
