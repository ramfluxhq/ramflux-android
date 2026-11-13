# Android SDK bridge

The bridge uses Kotlin coroutines over JNI and the SDK's C ABI v2. All business
operations go through the shared supervisor. Native callbacks, sockets, a second
account runtime, UniFFI and UI components are absent.

The two Android libraries are `:core:sdk` (transport, cancellation, bounded event
Flow, lifecycle ownership, Keystore wrapping and storage policy) and `:core:data`
(repository interfaces and forwarding implementations). Convention plugins own
build configuration; dependencies and versions live in `gradle/libs.versions.toml`.

## Build

Use JDK 17, Android SDK 36, NDK 28.2.13676358, SDK CMake 3.22.1 and cargo-ndk.
The build fetches checksum-pinned OpenSSL 3.6.3 and builds its static Android
libcrypto for the existing SQLCipher feature. Perl, make, curl, tar and Python 3
are required. The dependency cache is under the persistent Cargo target.
Install `aarch64-linux-android` for the product repository's pinned Rust toolchain.
Set `ANDROID_HOME`, `ANDROID_NDK_HOME`, `CARGO_TARGET_DIR` and `RAMFLUX_CORE_DIR`.
The last variable must point to the product checkout containing the v2 exports.
The build consumes that build's generated cbindgen header, never a handwritten
ABI declaration. Only arm64-v8a is packaged; both ELF libraries are checked for
16 KB LOAD alignment. The persistent Cargo target belongs to the build operator.

```sh
./scripts/build-native.sh core/sdk/build/generated/native check
./gradlew :core:sdk:assembleDebug :core:data:compileDebugKotlin
./gradlew :core:sdk:testDebugUnitTest :core:data:testDebugUnitTest lint
```

Kotlin compile/unit tests do not need native artifacts. Assembling the SDK AAR
builds the real product library and JNI; a missing dependency fails the build.
No placeholder library, skipped native build or emulator software vault is used.

## Ownership and cancellation

`AndroidCoreHost.create(context, expectedDescriptorFingerprint)` derives a path below credential-protected
`noBackupFilesDir`. The fingerprint must come from the host binding built against the SDK descriptor,
not from a runtime negotiation echo. A mismatch fails before opening a data root.
The application must await `foreground()` and `background()`
on its lifecycle transitions; background completes only after native close has
returned. A failure to shut down is a failure, not permission to open a replacement.
The host application must keep the library's backup and device-transfer exclusions,
internal-only installation and strict network-security policy in its merged manifest.

Each execute allocates one native operation/cancel token. Cancelling a coroutine
calls `operation_cancel`, then `operation_release`; it never sends the mutation
again. Before admission cancellation can prove no effects; after admission it
cannot. Reconcile the original operation ID and frozen arguments. Native statuses
and complete error frames are retained. Message contents and secrets are redacted
from wrapper `toString()` output; the bridge performs no content logging.

There is one active event collector per core queue. Collection holds the subscription
operation token until `awaitClose` cancels the producer, then cancels/releases that
same token. Backpressure has zero additional Flow buffering. Native overflow emits
`resync_required`, never a continuous stream. Read snapshots, call
`resetEventsAfterSnapshot()`, then subscribe from their journal cursor. A terminal
completion has its own native credit and cannot be displaced by event overflow.

## Capabilities and integration boundary

Repository methods forward existing SDK bus request bodies; they do not invent
another message schema. `operation-inventory.json` records the product registry
used to create forwarding interfaces. Recovery and creation use the SDK's actual
connection-local ceremonies. Successful unknown message content stays successful.
SDK errors are never converted into fabricated rows or optimistic delivery success.

The product v2 checkpoint lacks dedicated full-text search, Agent
CRUD/configuration and stranger reports/batch decisions. Their app-side interfaces
return typed unavailable results, as documented in [client capabilities](client-capabilities.md).
Unified edit/reaction/reply operations follow the SDK message-unification contract. Deferred video, group calls, screen sharing, channel/thread/post
flows remain outside this module's acceptance.

The Keystore helper is reused from the earlier Android platform implementation.
It accepts only verified TEE/StrongBox AES-GCM keys; only
`StrongBoxUnavailableException` permits fallback to TEE. Background keys require
no user authentication; presence keys require a fresh CryptoObject authorization.
`KeyWrapping` implements typed errors and a background round trip, never rekeys
on unwrap, and erases temporary plaintext. These primitives do not install a vault
inside the product SDK. That requires the Android vault/provider seam from the
platform SDK module; the bridge does not claim custody creation is operational
until that seam is linked and verified. Physical hardware and real lifecycle
acceptance are distinct from compile and JVM unit checks.
