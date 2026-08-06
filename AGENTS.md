# Repository Guidelines

## Project Structure & Module Organization

This is a Kotlin/Gradle Android project with two modules:

- `app/` contains the Android 12+ application, including Jetpack Compose UI, Bluetooth HID services, settings, chat/LLM integration, and Android resources under `app/src/main/res/`.
- `core/hid/` is an Android-independent Kotlin library for HID reports, keyboard layouts, typing pacing, and live-drain behavior.
- Unit tests mirror production packages in each module's `src/test/kotlin/` tree.
- `SPEC.md` is the detailed product and architecture reference; update it when behavior or constraints materially change.

Do not commit generated `build/` output, IDE metadata, `local.properties`, signing keys, or captures.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper (on Windows, replace `./gradlew` with `gradlew.bat`):

- `./gradlew :app:assembleDebug` builds the sideloadable debug APK.
- `./gradlew :core:hid:test` runs the fast, Android-free HID tests.
- `./gradlew :app:testDebugUnitTest` runs app-layer JVM unit tests.
- `./gradlew :app:lintDebug` runs Android lint checks.
- `./gradlew build` performs the broad project build and verification tasks.

JDK 17 and an Android SDK with API 36 are required. Keep dependency versions centralized in `gradle/libs.versions.toml`.

## Coding Style & Naming Conventions

Follow standard Kotlin style with four-space indentation, trailing commas in multiline declarations, and small, focused files. Use `PascalCase` for types and Compose functions, `camelCase` for functions/properties, and `UPPER_SNAKE_CASE` for constants. Package names stay under `dev.cwtf.hidandseek` and reflect the directory structure. Prefer immutable data and pure logic in `core/hid`; keep Android dependencies in `app`. Document safety-sensitive HID timing, Bluetooth, and agent guardrail behavior. The core module treats compiler warnings as errors.

## Testing Guidelines

Tests use JUnit Jupiter and `kotlin.test`; coroutine code may use `kotlinx-coroutines-test` and Turbine. Name files `<Subject>Test.kt` and use descriptive backtick test names, such as `` `repeated key receives extra delay` ``. Add regression tests beside the affected module. There is no enforced coverage percentage; prioritize report bytes, timing, failure cleanup, settings precedence, parsing, and guardrails.

## Commit & Pull Request Guidelines

History follows Conventional Commit prefixes such as `feat:`, `fix:`, and `chore:`. Keep commits focused and use an imperative summary. Pull requests should explain user-visible behavior, identify affected modules, link relevant issues or `SPEC.md` sections, and list verification commands. Include screenshots or recordings for Compose UI changes and call out Bluetooth-device or Android-version testing when applicable.
