package dev.cwtf.hidandseek.data

import kotlinx.serialization.Serializable

@Serializable
enum class HostOsTag { UNKNOWN, WINDOWS, MACOS, LINUX, ANDROID, IOS, TV, OTHER }

/**
 * A host the user has connected to, with its own settings.
 *
 * The override fields are nullable on purpose: null means "follow the global
 * default", so changing the default in Settings moves every device that has not
 * been given an explicit answer. A device pinned to `BIOS` keeps it.
 */
@Serializable
data class DeviceRecord(
    val address: String,
    val name: String,
    val nickname: String? = null,

    /** Null follows [TypingSettings.defaultLayoutId]. */
    val layoutId: String? = null,

    /** Null follows the global typing profile. */
    val profileId: String? = null,

    /** Null follows the global live-mode settings. */
    val livePresetId: String? = null,

    val hostOs: HostOsTag = HostOsTag.UNKNOWN,
    val autoReconnect: Boolean = true,
    val isDefault: Boolean = false,
    val lastConnectedAtEpochMs: Long? = null,
    val charsSent: Long = 0,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class DeviceRoster(
    val devices: List<DeviceRecord> = emptyList(),
) {
    fun find(address: String): DeviceRecord? = devices.firstOrNull { it.address == address }

    val default: DeviceRecord? get() = devices.firstOrNull { it.isDefault }

    /** Most recently used first, which is the order the picker shows. */
    val byRecency: List<DeviceRecord>
        get() = devices.sortedByDescending { it.lastConnectedAtEpochMs ?: Long.MIN_VALUE }

    fun upsert(record: DeviceRecord): DeviceRoster {
        val existing = devices.indexOfFirst { it.address == record.address }
        return copy(
            devices = if (existing >= 0) {
                devices.toMutableList().apply { this[existing] = record }
            } else {
                devices + record
            },
        )
    }

    fun remove(address: String): DeviceRoster =
        copy(devices = devices.filterNot { it.address == address })

    /** Only one device can be the launch default, so this clears the others. */
    fun setDefault(address: String): DeviceRoster =
        copy(devices = devices.map { it.copy(isDefault = it.address == address) })
}
