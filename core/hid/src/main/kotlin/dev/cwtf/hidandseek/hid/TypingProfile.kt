package dev.cwtf.hidandseek.hid

import kotlin.random.Random

/**
 * Per-keystroke timing.
 *
 * Hosts drop input when typed at faster than they can consume it — the limit is
 * the host's input handling, not the Bluetooth link. BIOS screens, KVM
 * switches, and remote-desktop sessions are the worst offenders, hence [BIOS].
 *
 * Every value is user-configurable; these are the defaults.
 */
data class TypingProfile(
    val id: String,
    val displayName: String,
    /** Gap between one keystroke finishing and the next starting. */
    val interKeyDelayMs: Int = 12,
    /** How long a key is held before release. Some firmware ignores short taps. */
    val keyHoldMs: Int = 8,
    /** Pause after asserting modifiers so the host registers them first. */
    val modifierSettleMs: Int = 5,
    /** Extra pause after Enter, where hosts often do visible work. */
    val newlineExtraDelayMs: Int = 40,
    /** Extra pause after a dead key so host compose logic keeps up. */
    val deadKeyExtraDelayMs: Int = 25,
    /**
     * Extra pause before pressing the *same* key again.
     *
     * Hosts distinguish a repeated character from a held key by the gap between
     * release and the next press. Too short and the second press is swallowed
     * as key-repeat noise, so `ssss` arrives as `ss` — losing exactly the
     * duplicates while ordinary text is unaffected.
     *
     * This is a separate dial from [interKeyDelayMs] because the two problems
     * are different: raising the general delay to fix repeats would slow down
     * every send for the sake of a minority of characters.
     */
    val repeatedKeyExtraDelayMs: Int = 30,
    val humanize: Boolean = false,
) {

    init {
        require(interKeyDelayMs in 0..200) { "interKeyDelayMs out of range: $interKeyDelayMs" }
        require(keyHoldMs in 0..50) { "keyHoldMs out of range: $keyHoldMs" }
        require(modifierSettleMs in 0..50) { "modifierSettleMs out of range: $modifierSettleMs" }
        require(newlineExtraDelayMs in 0..500) { "newlineExtraDelayMs out of range" }
        require(deadKeyExtraDelayMs in 0..200) { "deadKeyExtraDelayMs out of range" }
        require(repeatedKeyExtraDelayMs in 0..300) { "repeatedKeyExtraDelayMs out of range" }
    }

    /** Rough characters per second, for the settings screen's live readout. */
    val estimatedCharsPerSecond: Double
        get() {
            val perChar = interKeyDelayMs + keyHoldMs + modifierSettleMs
            return if (perChar <= 0) Double.POSITIVE_INFINITY else 1000.0 / perChar
        }

    companion object {
        val FAST = TypingProfile(
            id = "fast",
            displayName = "Fast",
            interKeyDelayMs = 5,
            keyHoldMs = 5,
            newlineExtraDelayMs = 20,
            repeatedKeyExtraDelayMs = 20,
        )

        val NORMAL = TypingProfile(id = "normal", displayName = "Normal")

        val SAFE = TypingProfile(
            id = "safe",
            displayName = "Safe",
            interKeyDelayMs = 30,
            keyHoldMs = 15,
            modifierSettleMs = 10,
            newlineExtraDelayMs = 100,
            repeatedKeyExtraDelayMs = 60,
        )

        val BIOS = TypingProfile(
            id = "bios",
            displayName = "BIOS",
            interKeyDelayMs = 60,
            keyHoldMs = 25,
            modifierSettleMs = 15,
            newlineExtraDelayMs = 120,
            deadKeyExtraDelayMs = 60,
            repeatedKeyExtraDelayMs = 100,
        )

        val PRESETS = listOf(FAST, NORMAL, SAFE, BIOS)
        val DEFAULT = NORMAL

        fun byId(id: String): TypingProfile? = PRESETS.firstOrNull { it.id == id }
    }
}

/**
 * Perturbs delays for hosts or software that reject machine-perfect timing.
 * Injectable so tests stay deterministic.
 */
fun interface Jitter {
    fun apply(baseMs: Int): Int

    companion object {
        val NONE = Jitter { it }

        /** Gaussian, sigma = [sigmaFraction] of the base delay, clamped at zero. */
        fun gaussian(random: Random, sigmaFraction: Double = 0.3) = Jitter { base ->
            if (base <= 0) {
                0
            } else {
                val sigma = base * sigmaFraction
                // Box-Muller; kotlin.random has no nextGaussian.
                val u1 = random.nextDouble().coerceAtLeast(1e-12)
                val u2 = random.nextDouble()
                val z = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
                    kotlin.math.cos(2.0 * Math.PI * u2)
                (base + z * sigma).toInt().coerceAtLeast(0)
            }
        }
    }
}
