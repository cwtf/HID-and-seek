package dev.cwtf.hidandseek.bluetooth

import dev.cwtf.hidandseek.hid.ConsumerReport
import dev.cwtf.hidandseek.hid.KeyboardReport

/**
 * The HID report descriptor advertised to hosts.
 *
 * Two collections: a boot-compatible keyboard (report ID 1) and consumer
 * control for media keys (report ID 2). The keyboard collection deliberately
 * keeps boot-protocol layout so BIOS/UEFI firmware, which speaks only boot
 * protocol, can use it.
 *
 * Logical and usage maxima are 255 rather than the more common 101, so keypad
 * usages — needed by the Windows Alt-code escape path — stay in range.
 */
object HidReportDescriptor {

    val BYTES: ByteArray = byteArrayOf(
        // --- Keyboard collection ----------------------------------------
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), KeyboardReport.REPORT_ID.toByte(), //   Report ID (1)

        // Byte 0 — modifier bitmask, one bit per modifier key.
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0.toByte(), //   Usage Minimum (LeftControl)
        0x29, 0xE7.toByte(), //   Usage Maximum (RightGUI)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95.toByte(), 0x08, //   Report Count (8)
        0x81.toByte(), 0x02, //   Input (Data, Variable, Absolute)

        // Byte 1 — reserved.
        0x95.toByte(), 0x01, //   Report Count (1)
        0x75, 0x08, //   Report Size (8)
        0x81.toByte(), 0x01, //   Input (Constant)

        // LED output report — how the host tells us Caps Lock is on.
        0x95.toByte(), 0x05, //   Report Count (5)
        0x75, 0x01, //   Report Size (1)
        0x05, 0x08, //   Usage Page (LEDs)
        0x19, 0x01, //   Usage Minimum (Num Lock)
        0x29, 0x05, //   Usage Maximum (Kana)
        0x91.toByte(), 0x02, //   Output (Data, Variable, Absolute)
        0x95.toByte(), 0x01, //   Report Count (1)
        0x75, 0x03, //   Report Size (3)
        0x91.toByte(), 0x01, //   Output (Constant) — pad to a whole byte

        // Bytes 2..7 — six concurrent key slots.
        0x95.toByte(), 0x06, //   Report Count (6)
        0x75, 0x08, //   Report Size (8)
        0x15, 0x00, //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x00, //   Logical Maximum (255)
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0x00, //   Usage Minimum (0)
        0x29, 0xFF.toByte(), //   Usage Maximum (255)
        0x81.toByte(), 0x00, //   Input (Data, Array)
        0xC0.toByte(), // End Collection

        // --- Consumer control collection --------------------------------
        0x05, 0x0C, // Usage Page (Consumer)
        0x09, 0x01, // Usage (Consumer Control)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), ConsumerReport.REPORT_ID.toByte(), //   Report ID (2)
        0x15, 0x00, //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x03, //   Logical Maximum (1023)
        0x19, 0x00, //   Usage Minimum (0)
        0x2A, 0xFF.toByte(), 0x03, //   Usage Maximum (1023)
        0x75, 0x10, //   Report Size (16)
        0x95.toByte(), 0x01, //   Report Count (1)
        0x81.toByte(), 0x00, //   Input (Data, Array)
        0xC0.toByte(), // End Collection
    )
}
