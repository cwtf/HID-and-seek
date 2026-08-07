# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

HID & Seek turns an Android phone into a Bluetooth HID keyboard for another machine. `SPEC.md` is the
long-form product/architecture reference; `AGENTS.md` holds contributor conventions. Both are worth
consulting for feature work — but see "SPEC drift" below.

## Commands

Windows shell (`gradlew.bat`); on POSIX use `./gradlew`.

```bash
gradlew.bat :app:assembleDebug
```

```bash
gradlew.bat :core:hid:test
```

```bash
gradlew.bat :app:testDebugUnitTest
```

```bash
gradlew.bat :app:lintDebug
```

Single test class or method (JUnit 5, standard Gradle filter):

```bash
gradlew.bat :core:hid:test --tests "*LiveDrainTest*"
```

Requires JDK 17 and Android SDK API 36. Debug APK lands in `app/build/outputs/apk/debug/`.
Install with `adb install -r <apk>`.

`:core:hid` sets `allWarningsAsErrors` — a warning there fails the build. `:app` does not.

## Module boundary (the one structural rule)

- `core/hid/` — pure Kotlin/JVM. HID report encoding, layout mapping, pacing, the live-typing drain
  state machine. No Android imports, no I/O, no clock reads outside injectable interfaces. Everything
  here is unit-testable in milliseconds.
- `app/` — everything Android: Compose UI, `BluetoothHidDevice`, DataStore, SQLite, OkHttp.

New logic goes in `core/hid` unless it genuinely needs Android. `HidTransport` is the seam:
`BluetoothHidTransport` (app) implements it, `FakeHidTransport` (core) is the test double.

## Typing pipeline

Text reaches the host through a fixed chain, each stage pure except the last:

```
text → LayoutMapper → List<KeyStroke> → ReportScheduler → List<TimedReport> → TypingPacer → HidTransport
```

- `LayoutMapper` resolves characters against a `KeyLayout`, handles dead keys, and applies
  `UnmappablePolicy` (skip / substitute / unicode-escape). It also compensates for host Caps Lock —
  the host's LED state changes what shift produces, so it is an input to mapping, not just display.
- `ReportScheduler` (pure) expands strokes into exact reports and delays: modifier-settle reports
  before the key, extra delay after newlines/dead keys, and an extra gap when the same usage repeats
  (otherwise the host swallows the second press as key-repeat noise).
- `TypingPacer` corrects timing against a monotonic `PacerClock` rather than accumulating `delay()`,
  so slow Bluetooth writes don't compound into drift. On failure or cancellation it always calls
  `releaseAllKeys()` — a stuck modifier survives disconnection and breaks the host.
- `HidController` (app) owns the pipeline and serialises every send through a single `Mutex`, so
  staged sends and the live drain can never interleave reports.

## Live typing: `LiveDrain`

Buffer-first, not keystroke-interception. The staging buffer is authoritative; the host is a lagging
replica. `LiveDrain.sentText` holds the literal text the host received (not an index — an index goes
stale the moment the buffer changes). Two rules produce all behaviour:

1. Never transmit inside an active IME composing region (`compositionStart` cuts the buffer).
2. On divergence, take the longest common prefix, backspace the remainder, retype.

Rule 2 collapses append / backspace / autocorrect rewrite / mid-buffer edit into one `DrainPlan`.
`LiveDrain` is synchronous and side-effect free — it never sends; the caller (`TypeViewModel`) owns
the settle timer and `HidController.execute(plan)` performs the I/O. Results are reported back
incrementally (`onTextTyped` / `onBackspacesApplied`) so a half-failed send leaves `sentText`
describing reality, since the next plan is computed from it. All lengths are counted in code points.

## Settings resolution

Global settings and per-device overrides merge in one pure place: `SettingsResolver.resolve()` →
`ResolvedConfig`. `AppContainer` combines `settings ⊗ roster ⊗ activeAddress` into a single flow
collected by `HidController.applyConfig`, so a slider change and a device switch take the same path.
Config applies on the *next* send — changing settings never interrupts live typing.

## LLM chat and the agent

- `LlmClient` speaks OpenAI-compatible chat completions over OkHttp SSE; any provider with that shape
  works (`ProviderPreset.ALL` lists the presets, including local Ollama/LM Studio).
- `AgentTools` defines `type_to_host`, `press_keys`, `get_host_status` as OpenAI function definitions.
  Providers without tool support fall back to a fenced-code-block convention parsed in `ChatViewModel`.
- `AgentGuardrails` is pure and stands between a model and someone's real keyboard. Order is load-bearing:
  hard caps (char cap, per-turn and per-minute rate) apply in *every* mode and cannot be approved away;
  Ask mode then requires approval; only Auto with all checks passed reaches `Allow`. Auto is deliberately
  temporary — it lapses to Ask on expiry and on disconnect (`effectiveMode`). Changes here need tests.

## Storage

- **Settings / roster / snippets / providers** — DataStore + kotlinx.serialization JSON
  (`Repositories.kt`). `ignoreUnknownKeys` plus a default on every field, so older/newer files load
  instead of wiping config. Keep new fields defaulted.
- **Secrets** (API keys, sensitive snippets) — `SecretStore`: per-value AES-GCM with a non-exportable
  Android Keystore key, alias used as AAD, `gcm1:` payload prefix. Only aliases are serialised into
  DataStore. `androidx.security.crypto` remains a dependency purely for the one-time legacy migration.
- **Chat history** — hand-written `SQLiteOpenHelper` (`ChatDatabase`), *not* Room: KSP has no build for
  Kotlin 2.4.10. FTS4 index kept in sync by triggers; foreign keys enabled in `onConfigure` or cascade
  deletes silently no-op. Migrations must be additive — history is kept forever.

## Wiring and UI

No DI framework. `AppContainer` does manual construction and owns the app-scope coroutine scope, the
connection watcher (foreground service lifecycle + exponential-backoff reconnect). ViewModels are built
in `HidAndSeekApp` via `viewModelFactory { initializer { … } }` with the container passed in.

Compose + Material 3 **Expressive**, single Activity, two top-level routes (`type`, `chat`) with
Settings as a nested nav graph (`SettingsRoutes`). Layout adapts: tabs on phones, `WideNavigationRail`
on wide screens.

## Pinned versions — do not bump casually

`gradle/libs.versions.toml` carries the reasoning inline; the short version:

- **AGP 9.0.0** — Android Studio AI-252 refuses to sync anything newer. Command-line builds succeed
  with a higher AGP, so "it compiles" is not evidence the toolchain is right.
- **material3 1.5.0-alpha18** — the Expressive APIs are still `internal` in the 1.4.0 stable the BOM
  pins, and alpha20+ requires Compose 1.12 / AGP 9.1+. Raise material3 and AGP together, alongside a
  Studio upgrade.
- AGP 9 has built-in Kotlin support, so `:app` deliberately has no `kotlin-android` plugin and names
  `kotlin-test-junit5` explicitly instead of using `kotlin("test")`.

## SPEC drift

`SPEC.md` §2 still lists Hilt, Room, and EncryptedSharedPreferences; none are used (see above), and
`libs.versions.toml` retains unused `hilt`/`room` version entries. Treat SPEC as the source of truth
for *behaviour* and the code as the source of truth for *mechanism*, and update SPEC when behaviour
or constraints materially change.

## Testing

JUnit 5 + `kotlin.test`, plus `kotlinx-coroutines-test` and Turbine in core. Files are
`<Subject>Test.kt`; test names are descriptive backticked sentences
(`` `repeated key receives extra delay` ``). No coverage target — prioritise report bytes, timing,
failure cleanup, settings precedence, parsing, and agent guardrails. Tests mirror production packages.

## Conventions

Four-space indent, trailing commas in multiline declarations, package names under `dev.cwtf.hidandseek`
mirroring directories. Comments in this codebase explain *why* — particularly for HID timing, Bluetooth
lifecycle, and guardrail decisions — and new code is expected to match that. Commits use Conventional
Commit prefixes (`feat:`, `fix:`, `chore:`, `refactor:`).
