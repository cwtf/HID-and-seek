package dev.cwtf.hidandseek.ui.settings

/**
 * Settings destinations.
 *
 * All of these live off the bottom navigation bar on purpose — settings is
 * reached only from the gear icon, per SPEC 4.2.
 */
object SettingsRoutes {
    const val ROOT = "settings"
    const val DEVICES = "settings/devices"
    const val DEVICE_DETAIL = "settings/devices/{address}"
    const val CONNECTION = "settings/connection"
    const val TYPING = "settings/typing"
    const val LIVE = "settings/live"
    const val APPEARANCE = "settings/appearance"
    const val ABOUT = "settings/about"
    const val LLM = "settings/llm"
    const val LLM_PROVIDER = "settings/llm/{providerId}"
    const val LLM_MODELS = "settings/llm/{providerId}/models"

    fun deviceDetail(address: String) = "settings/devices/$address"

    fun llmProvider(id: String) = "settings/llm/$id"

    fun llmModels(id: String) = "settings/llm/$id/models"

    fun titleFor(route: String?): String = when {
        route == null -> "Settings"
        route == ROOT -> "Settings"
        route == DEVICES -> "Devices"
        route == DEVICE_DETAIL -> "Device"
        route == CONNECTION -> "Connection"
        route == TYPING -> "Keystroke timing"
        route == LIVE -> "Live typing"
        route == APPEARANCE -> "Appearance"
        route == ABOUT -> "About"
        route == LLM -> "LLM providers"
        route == LLM_PROVIDER -> "Provider"
        route == LLM_MODELS -> "Select model"
        else -> "Settings"
    }

    fun isSettings(route: String?): Boolean = route?.startsWith(ROOT) == true
}
