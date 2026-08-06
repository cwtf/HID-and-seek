# HID & Seek

Turn your Android phone into a **Bluetooth keyboard for other devices** — PCs, Macs, Linux boxes, tablets, consoles, TVs, and KVM/embedded gear. Text composed on the phone is delivered to the connected host as real HID keystrokes, indistinguishable from a physical keyboard.

## Features

- **Text-file import** — load a config, script, or other text file into the staging buffer and type it over HID.
- **Staged typing** — compose a block of text and fire it at the host in one go.
- **Live typing** — stream every settled edit to the host as you type.
- **LLM chat tab** — talk to a model (OpenRouter, DeepSeek, OpenAI, or any OpenAI-compatible endpoint), then send its answer — or a single code block — to the host as keystrokes. Optionally let the agent type directly.
- **Image attachments** — photograph an error or BIOS screen, ask the model what it means, and type the fix straight back over HID.
- **Device picker** — the app remembers every host you've used; switching is one tap away on every screen.
- **Sequential broadcast** — send the same text to several hosts, one after another.

## Use cases

- Typing long passwords, config strings, or license keys into a machine with no easy input
- Driving a headless or KVM-attached box, BIOS/UEFI screens, install prompts
- Typing into a smart TV, console, or set-top box search field
- Asking an LLM for a shell command or snippet and delivering it to a host with no network path
- Accessibility — composing text comfortably on a phone and delivering it to a desktop

## Requirements

- Android 12 (API 31) or higher
- A host with Bluetooth keyboard (HID) support

## Installation

HID & Seek is distributed as a sideloaded APK and is not available on Google Play.

### Install a packaged APK

1. Download `app-release.apk` from the project's [GitHub Releases](https://github.com/cwtf/HID-and-seek/releases) page. If no release is listed, use the build-from-source instructions below.
2. Open the APK from your Android browser, Downloads app, or file manager.
3. If Android asks, allow **Install unknown apps** for the app that opened the APK.
4. Tap **Install**, then open HID & Seek and grant the requested **Nearby devices** permission.

Only install APKs downloaded from this repository. To install a downloaded APK from a computer with [ADB](https://developer.android.com/tools/adb), connect the phone and run:

```bash
adb install -r app-release.apk
```

After installation, start pairing from the receiving device's Bluetooth settings. The phone advertises itself as a keyboard named **HID & Seek**.

## Build from source

Requires JDK 17+ and an Android SDK with API 36.

```bash
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat :app:assembleDebug`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and can be installed with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project structure

- `app/` — Android application (Jetpack Compose, Material 3 Expressive, MVVM)
- `core/hid/` — pure-Kotlin core: HID reports, layout mapping, pacing, and the live-typing drain state machine. No Android dependencies; runs under fast JVM tests.

See `SPEC.md` for the full product & technical specification.

## License

MIT — see `LICENSE`. Copyright (c) 2026 cwtf.
