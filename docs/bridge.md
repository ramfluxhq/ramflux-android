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

Ordinary `execute` rejects all subscription methods. Only the internal `updates` creates and
owns the native subscription, and only the shared `EventHub` (`CoreClient.events`) calls it:
every repository collector shares one native subscription whose topics are the union of the
collectors' topics. Each collector has a bounded buffer of 64 events; a collector that falls
behind gets its own `resync_required` while the others continue, so a slow pane never stalls the
native pump. On native overflow or an in-band `stream.lagged` frame the hub resets the native
queue, sends `resync_required` to every collector (they reload their snapshots; their flows stay
open) and reopens. A collector whose topics are not covered reopens with the union, and the others
reload. The subscription follows one account at a time and is cancelled with its last collector.
`RepositoryTransport.resetEvents` is therefore a no-op on the SDK transport. Below the hub there
is one active native collector per core queue. Collection holds the subscription
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
error details. `check-secret-boundary.py` guards decoder entrypoints and all 214
SDK classifications of the pinned policy in CI.

### Reply size limits

The byte scanner (`ByteJson`) bounds work and memory by the reply itself, not by a
row budget:

- at most 1 MiB per reply, the SDK's frame ceiling (`MAX_FRAME_BYTES`,
  `c_abi/v2/state.rs`); a larger reply is a typed invalid-response failure;
- at most 32 levels of nesting, so recursion stays bounded on hostile input;
- at most 524,288 JSON values, derived from the byte ceiling (every value after the
  first needs its own byte plus a separator, so `[0,0,...]` filling 1 MiB is the
  densest frame and is still accepted). Scalars share one empty child list, so a
  frame costs one small offset node per value.

Unpaged list replies (`contact.list`, `contact.requests.list`, `group.list`,
`group.members`, `group.invitations.list`, `group.requests.list`) are whole snapshots
in one frame. Their readers decode through `ListReplyDecoder`: the parse and the row
projection run on a background dispatcher (never the caller's main thread), rows are
mapped in chunks of 256 with a cancellation check between chunks, and the reply bytes
are borrowed once and erased on success, failure and cancellation. Windowed pages are
then served from that snapshot (`SnapshotPager`).

With real-width identifiers (64-hex principal, root, link and device ids) one frame
carries about 3,070 `contact.list` rows (about 340 bytes each) and about 2,490
`group.members` rows (about 420 bytes each: a `members` entry, a `roles` entry and a
roster row per member), measured by the frame-filling data tests. A larger roster
needs a paged SDK method; until then the reply over 1 MiB is shown as a typed failure,
never truncated.

## Capabilities and integration boundary

Repository methods forward existing SDK bus request bodies; they do not invent
another message schema. `sdk-contract/ramflux_sdk_methods.json` is the exact pinned SDK policy;
`pin.json` records its immutable source revision/hash. `scripts/sync-sdk-policy.py`
reproduces classifications and repository signatures from that artifact. Account creation
uses the SDK's actual connection-local creation ceremony; recovery and pairing setup return
typed custody-provider unavailable until their host flows exist. Successful unknown message content stays successful.
SDK errors are never converted into fabricated rows or optimistic delivery success.

The pinned C ABI v2 refuses every `search.*` method before admission (`bridge_allowed`
false in the policy; `c_abi/v2/tests.rs` "local_search_is_refused_before_native_supervisor_admission"),
so local search stays typed unavailable and has no generated forwarders. The `agent.*`
methods (AGENT-IDENTITY-01) cross the ABI and are generated into `AgentsRepository`;
`agent.create` and `agent.creation.decide` are key-export class methods admitted
because the client opens with `allow_secret_exports`. Stranger reports and batch
decisions have no SDK method; their app-side interfaces return typed unavailable
results, as documented in [client capabilities](client-capabilities.md).

Joining an existing account from this phone (`device.pair.join`, request R32) is not
built. The new device's account code must reach the SDK through its credential
channel (action `setup`, `crates/ramflux-sdk/src/bus/credentials.rs`), which exists
only on local-bus socket connections and on `ramflux_pairing_call`. The in-process
C ABI v2 exports (`c_abi/v2/exports.rs`) have no credential channel, and
`ramflux_pairing_call` (`c_abi/pairing.rs`) talks to a configured same-user local
daemon socket, which an Android app does not have. The JNI secret transport carries
secret request bodies only, so it cannot supply that credential.
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
