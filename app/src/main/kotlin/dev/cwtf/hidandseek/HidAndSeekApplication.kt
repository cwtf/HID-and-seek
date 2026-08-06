package dev.cwtf.hidandseek

import android.app.Application

class HidAndSeekApplication : Application() {

    /**
     * Single owner of the HID pipeline and persistence for the process.
     *
     * The transport holds a Bluetooth profile proxy and at most one host
     * connection, so it cannot be per-screen — a second instance would fight
     * the first for the same platform service.
     */
    val container: AppContainer by lazy { AppContainer(this) }
}
