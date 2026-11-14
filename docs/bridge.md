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
The pinned SDK owns the mobile SQLCipher/OpenSSL build and locks its dependencies.
Perl, make and Python 3 are required. No host OpenSSL or software vault is substituted.
Install `aarch64-linux-android` for the product repository's pinned Rust toolchain.
Set `ANDROID_HOME`, `ANDROID_NDK_HOME`, `CARGO_TARGET_DIR` and `RAMFLUX_CORE_DIR`.
The last variable must point to the product checkout containing the v2 exports.
The build consumes that build's generated cbindgen header, never a handwritten
ABI declaration. Only arm64-v8a is packaged; both ELF libraries are checked for
16 KB LOAD alignment. The persistent Cargo target belongs to the build operator.

```sh
./scripts/build-native.sh core/sdk/build/generated/native check
./gradlew :core:sdk:assembleRelease :core:data:compileReleaseKotlin
./gradlew :core:sdk:testDebugUnitTest :core:data:testDebugUnitTest
./gradlew :fixtures:packaging:assembleRelease lint
python3 scripts/check-packaged-alignment.py core/sdk/build/outputs/aar/sdk-release.aar fixtures/packaging/build/outputs/apk/release/packaging-release-unsigned.apk
```

Kotlin compile/unit tests do not need native artifacts. Native assembly and lint
require the pinned SDK checkout and native toolchain. The native-packaging CI job
builds the real AAR and a build-only, non-UI APK consumer. It extracts and checks
every packaged ELF and runs `zipalign -c -P 16` on the APK; the fast Kotlin job
runs independently. A missing native dependency fails the native job.
Native packaging defaults to the SDK shipping profile `ffi-release` (release
optimisation, LTO and codegen, with panic=unwind). The packaged marker stays
`release`. Line tables and symbols are retained in separate `.dbg`
artifacts under `core/sdk/build/generated/native/symbols`, then packaging copies
are stripped with NDK tools and linked to their debug files. Cargo target outputs
and the original JNI build remain unstripped. CI uploads symbols separately.
The guard requires the packaged profile marker, rejects packaged DWARF sections,
and prints each library's measured bytes and the unchanged 256 MiB extraction
bound. The native CI gate assembles release AAR/APK artifacts; debug builds may
also consume the stripped release native binaries. For explicit development-only
native builds, set `RAMFLUX_NATIVE_PROFILE=dev` and check with `--profile dev`;
that is not shipping-profile acceptance.
No placeholder library, skipped native build or emulator software vault is used.

## Ownership and cancellation

`AndroidCoreHost.create(context, expectedDescriptorFingerprint)` derives a path below credential-protected
`noBackupFilesDir`. The fingerprint must come from the host binding built against the SDK descriptor,
not from a runtime negotiation echo. A mismatch fails before opening a data root.
Negotiation also compares the complete method policy (version, method names,
capabilities and all secret flags) with the pinned generated table. A missing or
changed policy returns typed `MethodPolicyMismatch` before opening a writer.
The application must await `foreground()` and `background()`
on its lifecycle transitions; background completes only after native close has
returned. A failed or pending shutdown retains its native handle. Foreground retries it:
initial timeout is 10, pending is 24, and only confirmed completion is 0. A terminal
failed shutdown is explicitly released after its error is observed; the data-root
lease still arbitrates any replacement open. Busy/storage refusals remain retryable.
Repositories resolve the current host generation for every call and collection.
The host application must keep the library's backup and device-transfer exclusions,
internal-only installation and strict network-security policy in its merged manifest.

Each execute allocates one native operation/cancel token. Cancelling a coroutine
calls `operation_cancel`, then `operation_release`; it never sends the mutation
again. Before admission cancellation can prove no effects; after admission it
cannot. Reconcile the original operation ID and frozen arguments. Submit-time refusals have no effects. Poll-time loss remains unknown.
Cancel/release backpressure is retried with bounded backoff; unresolved handles
remain owned and `cleanupFailure` exposes the fault until repair or close. Message contents and secrets are redacted
from wrapper `toString()` output; the bridge performs no content logging.
JNI submit pins a preallocated output slot before native admission and returns
only a primitive status. Kotlin retains the operation token even if allocating
its reply fails. Registration uses fixed 64-slot storage; cleanup makes at most
five attempts with backoff outside the native guard. Exhaustion retains the token
and exposes cleanup pending; it never closes the client to recover a submit OOM.

Ordinary `execute` rejects all subscription methods. Only `updates` creates and
owns the native subscription. There is one active event collector per core queue. Collection holds the subscription
operation token until `awaitClose` cancels the producer, then cancels/releases that
same token. Recollection and reset join preceding cleanup; a fast restart cannot
race a dying subscription. The SDK clears the previous queue on successful
reopen; the host does not reset an unobserved generation or drain queued
current-generation frames before the open completion. Account-scoped collectors
reject other accounts. Same-account
generation filtering awaits the SDK contract extension and is not an accepted
runtime boundary at this checkpoint. A host-visible subscription-close
acknowledgement also awaits that SDK extension; the pinned internal close exposes
no acknowledgement to the host. Idle polls back
off to 100 ms. Backpressure has zero additional Flow buffering. Native overflow emits
`resync_required`, never a continuous stream. Read snapshots, call
`resetEventsAfterSnapshot(terminal.subscriptionGeneration)`, then subscribe from their journal cursor. A terminal
completion has its own native credit and cannot be displaced by event overflow.

## Secret transport

The generated SDK policy marks secret input/output and one-time disclosure. An
ordinary `CoreRequest` is rejected for a confidential method before submission.
Use `SecretRequest` with a `SecretBody` built from owned UTF-8 bytes or `CharArray`
fields. Input allocations and temporary native buffers are erased on success,
refusal and cancellation. No secret body is serialized through `JsonObject`.

`executeSecret` extracts the body directly from completion bytes using a bounded
byte scanner. It never decodes the frame into a String or JSON tree. `SecretReply`
is Closeable and permits one borrowed access; consumption, exceptions and explicit
close erase its allocation. `consumeChars` decodes escapes/UTF-8 into a temporary
CharArray, passes it to the native host, then erases it. The host must not create
immutable Strings from those characters. Event payloads use the same owned type.
Errors from secret operations expose numeric status/effect, never parsed secret
error details. `check-secret-boundary.py` guards decoder entrypoints and all 191
SDK classifications in CI.

## Capabilities and integration boundary

Repository methods forward existing SDK bus request bodies; they do not invent
another message schema. `sdk-contract/ramflux_sdk_methods.json` is the exact pinned SDK policy;
`pin.json` records its immutable source revision/hash. `scripts/sync-sdk-policy.py`
reproduces classifications and repository signatures from that artifact. Recovery and creation use the SDK's actual
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
on unwrap, and erases temporary plaintext. The host custody provider seals vault
records with that non-exportable background key and opens the client through
`ramflux_v2_client_open_with_custody`. Ciphertext lives in no-backup storage.
A software keystore is refused; there is no software vault. Physical hardware
and real lifecycle acceptance are distinct from compile and JVM unit checks.
