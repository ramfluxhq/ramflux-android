# Ramflux Android

Ramflux Android is the native Android client for Ramflux, written in Kotlin with Jetpack Compose and built
with Gradle. It embeds the Ramflux core SDK through its C ABI (v2) and talks to Ramflux nodes only through
that SDK. This repository contains only the AGPL client; the core SDK, the protocol crates and the node
services live in `ramfluxhq/ramflux`.

## What the app does

Account creation and unlock with secure-store custody of the keys, contacts and safety-number verification,
direct and group conversations with an encrypted outbox, images, files and other attachments, backup and
restore, settings, and live updates from the SDK's event stream. The screens follow the Ramflux mobile design
(light and dark themes; English and Simplified Chinese).

## Modules

| Module | Role |
|---|---|
| `app` | the application: shell, navigation graph, build types (`debug`, `release`, and the opt-in `realrun`) |
| `core:sdk` | the SDK bridge: JNI transport, cancellation, event flow, Keystore wrapping, storage policy |
| `core:data` | repository interfaces and forwarding implementations over the SDK methods |
| `core:media` | attachment preparation, transfer queue and media handling |
| `core:designsystem` | the Compose kit, design tokens, themes and preview references |
| `core:navigation` | typed navigation between the feature modules |
| `feature:*` | one folder per feature (`onboarding`, `conversations`, `chat`, `contacts`, `groups`, `settings`, `app-shell`, `permissions`, and the cards, tasks, agents and calls surfaces), each with `api`, `impl` and `test` modules |
| `fixtures:packaging`, `test-support` | a packaging consumer for the native checks, and shared test code |

## Build and test

You need JDK 17, Android SDK 36 and, for the native library, NDK 28.2.13676358, cargo-ndk and a checkout of the
core repository (`RAMFLUX_CORE_DIR`). The revision of the core that this client builds against is pinned in
`sdk-contract/pin.json`.

```sh
./gradlew ktlintCheck :core:sdk:testDebugUnitTest :core:data:testDebugUnitTest   # no native artifacts needed
./gradlew :app:testDebugUnitTest                                                 # includes the screenshot tests
./scripts/build-native.sh core/sdk/build/generated/native check                  # native library, needs the core checkout
./gradlew :app:assembleDebug lint
python3 scripts/run-ci-steps.py kotlin --list                                    # the CI steps, runnable locally
```

### Application ids

The three build types install side by side on one phone, each with its own Keystore keys, notification channels
and data:

| Build type | Application id | Launcher label |
|---|---|---|
| `release` | `org.ramflux.android` | Ramflux |
| `debug` | `org.ramflux.android.dev` (instrumentation package `org.ramflux.android.dev.test`) | Ramflux Dev |
| `realrun` | `org.ramflux.android.realrun` | Ramflux Real Run |

The Kotlin namespace stays `org.ramflux.android` in every build type.

`scripts/run-ci-steps.py <job>` runs the commands of a job in `.github/workflows/ci.yml` as CI does. The guard
scripts under `scripts/` (secret boundary, architecture, permissions, tokens, board references) are part of CI.

## Manuals

- [Bridge architecture and build](docs/bridge.md): the SDK boundary, the native build, cancellation, event resync.
- [Client architecture](docs/client-architecture.md) and [client capabilities](docs/client-capabilities.md).
- [Design alignment](docs/design-alignment.md), [localization and themes](docs/localization-and-themes.md) and
  the [Compose kit](core/designsystem/KIT.md).
- [Media](docs/media.md).
- [Real run against a node](docs/realrun.md): an opt-in suite on a real phone; it is not part of CI.

Physical-device and server acceptance are separate from compilation and JVM tests.

## License

AGPL-3.0-or-later with the Ramflux App Store Exception. See `LICENSE`,
`LICENSING.md` and `NOTICE`.
