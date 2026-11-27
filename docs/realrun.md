# Real run against a node

An opt-in run of the beta flows on a real phone (or, for part of the flows, an emulator): the `realrun` build
of the app with the real SDK against a Ramflux node that you run, with two peers, alice and bob, driven through
the `rf` CLI on the machine that runs the script. No CI task runs it, and nothing in it is needed to build
or test the app.

## Prerequisites

- The Android toolchain of [bridge.md](bridge.md) (JDK 17, SDK 36, NDK, cargo-ndk) and the native libraries
  built, so that `./gradlew :app:assembleRealrun` works.
- A node under test, with two peer daemons (alice and bob) and an `rf` binary built from the core repository.
  The script only reads the node's facts; it never reconfigures the node.
- A phone with USB debugging enabled, attached to this machine or to another machine you can reach over SSH
  with key authentication. `adb` must work on the machine the phone is attached to. The phone must be
  unlocked at the start. An emulator can run only the flows that do not create an account (see below).
- `python3`, `curl`, `ssh` and `scp` (the last two only for a phone on another machine).

## The node directory

`RAMFLUX_REALRUN_NODE_DIR` names a directory that describes the node and the peers:

| File | Content |
|---|---|
| `node-facts.json` | `discovery_origin`, `gateway_tcp_addr`, `relay_quic_addr`, `node_id`, `node_public_key`, `node_key_fingerprint`; optional `username_directory` (`url`, `id`, `public_key`, `generation`), the independently provisioned trust pin of the username directory, applied at build time |
| `peers.json` | `peers`: a list of objects with `name` (`alice`, `bob`), `account_id`, `principal_id`, `principal_commitment` and `daemon_socket` |
| `status.sh` | exits 0 when the node is healthy |
| `peers.sh status` | prints `alice: running` and `bob: running` when both daemons are up |
| `lib/rfbeta_util.py` | `dm-conversation <principal-a> <principal-b>` prints the direct conversation id |

Only public values leave this directory: the origin, the operator key, the key fingerprint, the node id and the
peers' principal identifiers are passed to the suite as instrumentation arguments. `daemon_socket` is never sent
to the phone.

## Settings

Every environment-specific input is a setting without a default. A setting is an environment variable, or a
line `KEY=value` in `scripts/realrun.local.env`. That file is untracked (it is in `.gitignore`); start from the
committed `scripts/realrun.local.env.example`. An environment variable wins over the file, and
`RAMFLUX_REALRUN_CONFIG` names a different file. A missing required setting stops the script with a list of
what is missing.

| Setting | Needed for | Meaning |
|---|---|---|
| `RAMFLUX_REALRUN_NODE_DIR` | always | the node directory above |
| `RAMFLUX_REALRUN_RF` | always | the `rf` CLI binary that drives alice and bob |
| `RAMFLUX_REALRUN_HOST` | `--target phone` | the machine whose adb reaches the phone: an SSH host name, or `local` for this machine |
| `RAMFLUX_REALRUN_ADB` | `--target phone` | path of `adb` on that machine |
| `RAMFLUX_REALRUN_SERIAL` | `--target phone` | the device serial (`adb devices`) |
| `RAMFLUX_REALRUN_REMOTE_DIR` | `--target phone` | a scratch directory on that machine; the APKs are copied there and removed at the end |
| `RAMFLUX_REALRUN_ENV` | optional | a shell file to source first, for example your build environment (keep it untracked as `scripts/realrun.build.env`, which git ignores) |
| `RAMFLUX_REALRUN_PYTHON` | optional | the Python 3 interpreter (default `python3`) |
| `RAMFLUX_REALRUN_GATEWAY_CONTAINER`, `RAMFLUX_REALRUN_CONTAINER_ENGINE` | optional | a gateway container (engine default `podman`) used to warn about clock skew between the node and this machine |
| `RAMFLUX_REALRUN_TAP_DELAY` | optional | seconds to wait before tapping an install confirmation (default 2.5) |

## Running

```sh
cp scripts/realrun.local.env.example scripts/realrun.local.env   # then edit the placeholders
scripts/realrun.sh                                  # phone, node over the network (--path lan)
scripts/realrun.sh --path tunnel                    # node on loopback, reached through adb reverse (and ssh -R)
scripts/realrun.sh --target emulator --path tunnel  # a local emulator or device on this machine's adb
```

A command may be limited to ten minutes on a shared machine, so a complete run is several invocations against
one output folder; results accumulate in `results.json` and `flow-table.md` is rewritten each time. Every
invocation stops the app first and starts a new process, so the first flow of a later invocation is a terminate and
relaunch; it must open the account without asking for anything (the SDK reopens it through the device-bound key).
Flow 17 runs before flow 09: bob must not yet be a contact of the phone for his group to arrive as an invitation.

```sh
O=build/realrun/final
scripts/realrun.sh --tests "f01 f02" --out $O                           # build, fresh install, onboarding, lock
scripts/realrun.sh --skip-build --no-install --tests "f03 f04 f05" --out $O   # relaunch without a prompt, contacts, list
scripts/realrun.sh --skip-build --no-install --tests "f06 f07 f16" --out $O   # direct chat, background, read receipt
scripts/realrun.sh --skip-build --no-install --tests "f08" --out $O           # media
scripts/realrun.sh --skip-build --no-install --tests "f17" --out $O           # invitation from a non-contact, leave
scripts/realrun.sh --skip-build --no-install --tests "f09" --out $O           # groups
scripts/realrun.sh --skip-build --no-install --tests "f10 f11 f12" --out $O   # settings, backup, honest states
scripts/realrun.sh --skip-build --no-install --tests "f13 f14 f18" --out $O   # invitation list, sign out and back in, usernames
scripts/realrun.sh --skip-build --no-install --tests "f15" --out $O           # remove the account from this phone: last
scripts/realrun.sh --skip-build --no-install --tests "f20" --out $O           # app lock: steps 1-6, then 7 and 8 in the next two invocations (each runs f20 alone, the script stops the app first)
```

`--test-apk-only` replaces only the suite APK (the app and its data stay); `--keep-app` reinstalls both
without clearing data. On MIUI the "USB install" prompt is confirmed by tapping its button a moment after it
appears (with retries); replacing an installed package shows no prompt.

## Pieces

- **Build type `realrun`** (`AndroidAppPlugin`): debug under `org.ramflux.android.realrun` with the
  label "Ramflux Real Run", so it installs beside any other build on a shared phone. The script installs
  and uninstalls only this package; the debug build type is `org.ramflux.android.dev` and release is
  `org.ramflux.android`, so all three coexist. Nothing in the app hard-codes the package name (the file provider uses
  `${applicationId}`; Keystore keys and notification channels are per application).
- **Suite** `app/src/androidTest/.../realrun/RealRunFlowTest` (UiAutomator), skipped unless the
  instrumentation argument `rf_realrun=1` is given; the native smoke run selects its own classes only.
  Debuggable builds expose the Compose test tags as view ids.
- **Secrets.** The six-digit account code is generated by flow 01 and kept on the phone only, encrypted under
  a Keystore key of the app's uid in its no-backup storage, so later invocations can unlock (`RunCode`). The
  recovery words reach the suite only through `RealRunProbe`, which exists in every build but is enabled only in
  the realrun build type: the Kit's secret-word canvas hands each drawn word to it, it keeps erasable copies in
  the app process (the instrumentation shares that process) and the suite erases them after the word check.
  Neither goes to the control server, an argument, a file or a log. Words and the code go straight into the
  app's erasable fields through their input connection (no shell command line, no key injection), and no
  screenshot is taken while recovery words are on screen or another app is in front.
- **Pickers and share.** The system photo picker is answered with a test image from the app's own cache
  (the shared phone's photos are never offered) and the share sheet is intercepted to read the contact card;
  what the app does with the result is the real path.
- **Orientation.** The suite asks for portrait on the app's own activity; the phone's rotation settings are
  never changed.
- **Debug trace.** Debug and realrun builds write their trace (codes and names, never content) to logcat under
  `ramflux.abi` and to `no_backup/debug-trace.log` in the app's storage. Some phones keep none of an app's own log
  lines, so after each invocation the script copies that file with `run-as` to `app-trace.txt`; `app-logcat.txt`
  keeps the app's logcat lines and the suite's flow markers.
- **SDK log.** The SDK writes a bounded, rotated NDJSON log (`diag/`) below its data root, `no_backup/ramflux` in the app's
  storage; the realrun build asks for level `debug` through the open config. After each invocation the script copies
  `diag/` with `run-as tar` into `sdk-diag/<time>-<tests>/diag/` of the output folder (one copy per invocation, because
  a fresh install starts a new log) and prints the file count and size, or a warning when nothing could be pulled. The SDK
  lowers `debug` and free text to `info` unless the core library carries its real-run feature, so the level actually
  written is in each file's header. Scan the copy for secret canaries before sharing it.
- **Peer control** `scripts/realrun/peer_control.py` on a loopback port of the machine running the script: allow-listed `rf`
  commands for alice and bob, results, screenshots. `scripts/realrun/flow_table.py` writes
  `flow-table.md` (PASS / FAIL / BLOCKED / NOT RUN).

## Reaching the node

The origin, operator key, fingerprint and node id are read from `node-facts.json` in the node directory at run time.
`--path lan` (default) lets the device talk to the advertised origin directly (TCP and QUIC); only the
peer-control port is forwarded. Debug builds accept a private-range `http://` origin such as
`http://<LAN IPv4>:8482` as typed (`DiscoveryOriginParser`, `OnboardingDataTest`). `--path tunnel` is for a
node that advertises and binds the loopback:

The node advertises `127.0.0.1` and binds the loopback: discovery HTTP 8482/TCP, gateway 8443 over TCP
and QUIC (UDP), relay 8447 QUIC only, TURN 8478. The SDK uses the signed record's addresses as they are,
so on the device `127.0.0.1:<port>` must reach this machine's `127.0.0.1:<port>`:

- emulator: `adb reverse tcp:<port> tcp:<port>`;
- phone on another machine: `adb reverse tcp:<port> tcp:<rport>` there, and `ssh -N -R 127.0.0.1:<rport>:127.0.0.1:<port>`
  from this machine (binding only that machine's loopback).

Both carry TCP only. Verified on the emulator: discovery, the operator key and fingerprint review and saving
the node work over the tunnel; the QUIC-only relay is not carried, so media is expected BLOCKED there.

An emulator cannot create an account: the SDK refuses with `CustodyVaultUnavailable`
(`vault_primary_unavailable`) because the emulator has no hardware-backed keystore and the app has no software
vault by design. Flows from account creation on need a real phone. 

## What the suite does and does not do

It runs twenty-three flows (`f01` to `f23`, with `f19` the group system lines): onboarding and account creation, lock and unlock, terminate and relaunch
without a prompt, contacts, the conversation list, direct chat, background and return without a prompt (screen
and draft kept), media (including a group image), groups, settings, backup, the honest states of the app when the
node or a peer is unavailable, the group invitation list, signing out and back in, removing the account from the
phone (`f15` erases the account: run it last), a read receipt seen by the peer (`f16`), an invitation from a
non-contact accepted and the group left (`f17`), and usernames (`f18`: BLOCKED with the directory's refusal when the build has no username directory pin, that is when
`node-facts.json` has no `username_directory`).
The card gallery (`f21`) opens the test-only card gallery (`CardGalleryActivity`, realrun source set; not in the debug or release build) and runs Run conformance: checks A (opened tree equals the core's expected tree; refused rows refuse), B (no placeholder node, every host slot drawn) and C (each interactive fixture's event is answered and the held tree equals a fresh runtime read) over every host fixture. The flow fails on any FAIL and keeps `artifacts/card-conformance.json` (one object per check) plus screenshots `21-gallery-*`. The realrun build needs the card fixture library first: `RAMFLUX_CARD_FIXTURE=1 scripts/build-native.sh core/sdk/build/generated/native build`, which adds `libramflux_card_fixture.so` for the realrun build type only (the release library never carries it). The fixtures come from `card-fixtures/` (synced from the pinned core with `scripts/sync-card-fixtures.py`).

The exhausted-lock recovery (`f22`, G10b) creates the run's only account itself, on a fresh install with no account (it never creates a second account; f23 covers two accounts). It follows the onboarding path of f01 (node review and confirm, create, the recovery phrase read in this process, the code; phrase and code held in memory only, never logged, saved or shown on a screenshot), turns the app lock on, types ten wrong codes through the waits of 30, 60, 120, 240 and 480 s to the exhausted page (also after a relaunch), recovers with the phrase and a wrong code (refused, still locked) and with the phrase and the right code (unlocked, lock still on), exhausts the lock again and removes the account through the remove dialog (Cancel keeps it, confirm needs "I understand"); the phone ends with no account. If an account already exists at start the flow records BLOCKED ("run f22 alone on a fresh install"). It runs about 50 minutes in one process, as a background command, never in the ten-minute foreground budget. Fresh install, f22 alone (without `--no-install` and without `--keep-app` the script uninstalls the app and the suite first, which wipes the app data): `scripts/realrun.sh --tests "f22" --out $O` (add `--skip-build` when the APKs are current). The onboarding `forget-account` button is not reachable from this flow and is recorded as a note.

The two-account app lock (`f23`, G05e-A) runs on one fresh install in two phases and keeps both account codes in memory only (never logged, saved or shown on a screenshot). Phase 1, app lock off for both accounts: create A, reach the account chooser through Lock account and create B, switch B to A and A to B (each opens its target cleanly: no stuck retry, no leftover cover), three background and return cycles on B, one on A. This exercises the multi-account survey and the per-account app-lock calls through the unattended lease. Phase 2: open A, turn A's app lock on (Immediately); background and return on A raises the cover and A's code opens A; a relaunch with A engaged shows the cover and A's code opens A; three further returns on A (R8). While A's lock is engaged there is no route to the account chooser (Lock account raises A's cover; node.list is refused app_lock by design), a product gap tracked as G05e-B, so "B, or a switch, while A's lock is engaged" is not exercised until G05e-B and the PASS detail says so. The flow cannot read the app's private trace, so the runner must grep `app-trace.txt` for `app_lock_unauthorized` and expect no match (the PASS detail says this too). Fresh install, f23 alone: `scripts/realrun.sh --tests "f23" --out $O`.

The app lock (`f20`) runs on the lock cover: off by default, on with timeout Immediately, the lock form with no account
text, a wrong code refused, BACK keeps the form, relocks, a message menu under the cover. Its steps 7 and 8 start from a
process the script killed (the suite cannot force-stop its own app process), so `f20` runs alone in two more invocations;
between them the lock stays on, and the flows that expect no lock run after `f20` has passed. When a phase fails, it
turns the lock off with the run's code before it reports (and starts the phases over), and a phase that finds a lock left on by an
earlier failed phase opens it with the run's code, turns it off, records a note in the flow detail and goes on. While the
cover is up the shell under it is not in the accessibility tree, so only the lock form is found.
Restore is not run: it needs the archive passphrase to reach another install. Each flow ends PASS, FAIL, BLOCKED
(a precondition such as the QUIC relay is missing) or NOT RUN in `flow-table.md`, which covers every invocation in
the output folder. The table and the instrumentation agree: a FAIL fails its JUnit test (BLOCKED and NOT RUN are
skipped tests), a flow left RUNNING by a dead process and a test the instrumentation failed are recorded as FAIL
(`scripts/realrun/reconcile.py`), and each invocation exits non-zero when any row of the table is FAIL (also one left by an
earlier invocation in the same folder, until that flow is run again), when the instrumentation failed or did not run to its
end, and the table ends with an `Overall:` line (FAIL, PASS or INCOMPLETE). A failing flow leaves a screenshot and a UI dump (`dumps/`). It
does not change the node, the phone's settings or its rotation, other apps, or the phone's photos, and it does
not run in CI.

## Safety rules

- The script installs and uninstalls only `org.ramflux.android.realrun` and its test package, never another
  Ramflux build on the same phone.
- Every `adb reverse` rule and SSH forward the script adds is removed at the end, also on failure, and the
  phone is left on its home screen. The phone's log is never cleared.
- Nothing secret is passed on a command line, written to the output folder or logged. The peer-control server
  listens on `127.0.0.1` only and accepts a fixed list of `rf` commands.
- No screenshot is taken while recovery words are on screen or another app is in front.
- Keep `scripts/realrun.local.env` and the output folders (`build/`) out of version control; they name your
  machines and devices.
