#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
#
# Opt-in real run of the beta flows: the realrun build of the app (debug under the application id
# org.ramflux.android.realrun, so it installs beside any other build) with the real SDK against a node you
# run, with the alice and bob peers driven through the rf CLI on this machine.
#
#   scripts/realrun.sh [--out DIR] [--target phone|emulator] [--path lan|tunnel] [--keep-app] [--skip-build]
#                      [--no-install | --test-apk-only] [--tests "f01 f02 ..."]
#
# Every environment-specific input is a setting; there are no defaults for them. A setting is an environment
# variable, or a line KEY=value in scripts/realrun.local.env (untracked; see realrun.local.env.example; the
# file named by RAMFLUX_REALRUN_CONFIG replaces it). An environment variable wins over the file.
#
#   RAMFLUX_REALRUN_NODE_DIR     always: directory of the node under test (node-facts.json, peers.json, status.sh,
#                                peers.sh, lib/rfbeta_util.py; see docs/realrun.md)
#   RAMFLUX_REALRUN_RF           always: path of the rf CLI binary that drives the peers
#   RAMFLUX_REALRUN_HOST         target phone: the machine whose adb reaches the phone, an SSH host name, or
#                                "local" for this machine
#   RAMFLUX_REALRUN_ADB          target phone: path of adb on that machine
#   RAMFLUX_REALRUN_SERIAL       target phone: the device serial
#   RAMFLUX_REALRUN_REMOTE_DIR   target phone: scratch directory on that machine (APKs are copied there)
#   RAMFLUX_REALRUN_ENV          optional: a shell file to source first (for example the build environment)
#   RAMFLUX_REALRUN_PYTHON       optional: Python 3 interpreter (default python3)
#   RAMFLUX_REALRUN_GATEWAY_CONTAINER, RAMFLUX_REALRUN_CONTAINER_ENGINE
#                                optional: container name and engine (default podman) for the clock-skew check
#   RAMFLUX_REALRUN_TAP_DELAY    optional: seconds before tapping an install confirmation (default 2.5)
#
# One invocation must finish inside a ten-minute command budget on a shared machine, so the flows can be run in
# several invocations against the same --out directory: the first with a fresh install (the default), later
# ones with --skip-build --no-install and --tests naming the flow methods by prefix (f03, f04, ...). Results
# accumulate in DIR/results.json and the flow table is rewritten after each invocation.
#
# Node paths (the origin, operator key and fingerprint are read from node-facts.json at run time):
#   lan       (default) the device talks to the origin in node-facts.json directly, TCP and QUIC; only the
#             peer-control port is forwarded. Needs a node that advertises an address the device can reach.
#   tunnel    the device's 127.0.0.1:<port> reaches this machine's loopback for discovery and the gateway (and
#             the control port), as below. TCP only: the QUIC relay is not carried, so media is expected BLOCKED.
#
# Targets:
#   phone     a phone attached to the machine named by RAMFLUX_REALRUN_HOST. For an SSH host, the node, gateway
#             and control traffic reach this machine's loopback through `adb reverse` on that host and an SSH
#             remote forward that binds only that host's loopback; TCP only. For "local", `adb reverse` goes
#             straight to the loopback.
#   emulator  a local emulator or device on this machine's adb (`adb reverse` straight to the loopback).
#
# The account code is generated and the recovery words are read inside the instrumentation process only;
# nothing secret is passed as an argument, written here or logged. Every forward and reverse rule this
# script adds is removed at the end, also on failure; the phone is left on its home screen.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)

# Settings from the untracked local file; an environment variable that is already set wins.
CONFIG=${RAMFLUX_REALRUN_CONFIG:-$ROOT/scripts/realrun.local.env}
if [ -f "$CONFIG" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    key=${line%%=*}; value=${line#*=}
    [[ "$key" =~ ^RAMFLUX_REALRUN_[A-Z_]+$ ]] || continue
    value=${value%\"}; value=${value#\"}; value=${value%\'}; value=${value#\'}
    case "$value" in '~/'*) value=$HOME/${value#'~/'} ;; esac
    [ -n "${!key:-}" ] || export "$key=$value"
  done < "$CONFIG"
fi
NODE_DIR=${RAMFLUX_REALRUN_NODE_DIR:-}
RF_BIN=${RAMFLUX_REALRUN_RF:-}
HOST=${RAMFLUX_REALRUN_HOST:-}
REMOTE_ADB=${RAMFLUX_REALRUN_ADB:-}
SERIAL=${RAMFLUX_REALRUN_SERIAL:-}
REMOTE_DIR=${RAMFLUX_REALRUN_REMOTE_DIR:-}
APP_ID=org.ramflux.android.realrun
TEST_ID=$APP_ID.test
RUNNER=$TEST_ID/androidx.test.runner.AndroidJUnitRunner
SUITE=org.ramflux.android.realrun.RealRunFlowTest
OUT=$ROOT/build/realrun/$(date -u '+%Y%m%dT%H%M%SZ')
TARGET=phone
NODE_PATH=lan
KEEP_APP=0
SKIP_BUILD=0
NO_INSTALL=0
TEST_APK_ONLY=0
TESTS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT=$2; shift 2 ;;
    --target) TARGET=$2; shift 2 ;;
    --path) NODE_PATH=$2; shift 2 ;;
    --keep-app) KEEP_APP=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --no-install) NO_INSTALL=1; shift ;;
    --test-apk-only) TEST_APK_ONLY=1; shift ;;
    --tests) TESTS=$2; shift 2 ;;
    *) echo "usage: scripts/realrun.sh [--out DIR] [--target phone|emulator] [--path lan|tunnel] [--keep-app] [--skip-build] [--no-install | --test-apk-only] [--tests \"f01 f02\"]" >&2
       echo "settings: see the header of this script, scripts/realrun.local.env.example and docs/realrun.md" >&2; exit 2 ;;
  esac
done
case "$TARGET" in phone|emulator) ;; *) echo "realrun: --target is phone or emulator" >&2; exit 2 ;; esac
case "$NODE_PATH" in lan|tunnel) ;; *) echo "realrun: --path is lan or tunnel" >&2; exit 2 ;; esac

# Required settings: a missing one stops the run with the full list of what is missing.
missing=""
need() { [ -n "${!1:-}" ] || missing="$missing $1"; }
need RAMFLUX_REALRUN_NODE_DIR; need RAMFLUX_REALRUN_RF
if [ "$TARGET" = phone ]; then
  need RAMFLUX_REALRUN_HOST; need RAMFLUX_REALRUN_ADB; need RAMFLUX_REALRUN_SERIAL; need RAMFLUX_REALRUN_REMOTE_DIR
fi
if [ -n "$missing" ]; then
  printf 'realrun: missing settings:%s\n' "$missing" >&2
  echo "realrun: set them as environment variables or in $CONFIG (copy scripts/realrun.local.env.example); see docs/realrun.md" >&2
  exit 2
fi
LOCAL_HOST=0
if [ "$TARGET" = emulator ] || [ "$HOST" = local ]; then LOCAL_HOST=1; fi
LOCAL_NODE=$NODE_DIR

say() { printf 'realrun: %s\n' "$*"; }
die() { printf 'realrun: error: %s\n' "$*" >&2; exit 1; }

# shellcheck disable=SC1090
[ -n "${RAMFLUX_REALRUN_ENV:-}" ] && . "$RAMFLUX_REALRUN_ENV"
PYTHON=${RAMFLUX_REALRUN_PYTHON:-python3}
cd "$ROOT"
mkdir -p "$OUT"
OUT=$(CDPATH='' cd -- "$OUT" && pwd)
START=$(date +%s)

# Commands on the device: through the remote host's adb for the phone, the local adb otherwise. Only adb
# against the one serial and file operations inside $REMOTE_DIR run on the remote host.
remote() { if [ "$HOST" = local ]; then bash -c "$*"; else ssh -o BatchMode=yes -o ConnectTimeout=15 "$HOST" "$@"; fi; }
# Copies files to $REMOTE_DIR on the adb host.
to_host() { if [ "$HOST" = local ]; then cp "$@" "$REMOTE_DIR/"; else scp -q -o BatchMode=yes "$@" "$HOST:$REMOTE_DIR/"; fi; }
dev() {
  if [ "$TARGET" = phone ]; then
    local quoted; quoted=$(printf '%q ' "$@")
    remote "$REMOTE_ADB -s $SERIAL $quoted"
  else
    adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"
  fi
}

# Installs one APK already copied to $REMOTE_DIR on the phone. MIUI answers `adb install` with a "USB install"
# confirmation that has a countdown; its confirm button only takes a tap a little after the dialog appears. Play
# Protect may first ask to send the app for a check, which is declined. The install runs in the background of this
# function while the screen is dumped; each dialog is tapped about INSTALL_TAP_DELAY seconds after it was seen.
# One retry on refusal.
INSTALL_TAP_DELAY=${RAMFLUX_REALRUN_TAP_DELAY:-2.5}
install_on_phone() {
  local apk=$1 log=$2 attempt status pid seen bounds now
  for attempt in 1 2 3; do
    remote "$REMOTE_ADB -s $SERIAL install -r -t $REMOTE_DIR/$apk" >> "$log" 2>&1 &
    pid=$!
    seen=""
    while kill -0 "$pid" 2> /dev/null; do
      bounds=$(dev exec-out uiautomator dump /dev/tty 2> /dev/null | "$PYTHON" -I -c '
import re, sys
xml = sys.stdin.read()
# MIUI asks to confirm a USB install; Play Protect may ask whether to send the app for a check (the app is not sent)
# or warn before installing it. Only those dialogs, raised by the install of our own package, are answered.
WANTED = [("com.miui.securitycenter", r"继续安装|Continue installation|Install"),
          ("com.android.vending", r"不发送|Don.t send|仍然安装|仍要安装|Install anyway")]
for package, label in WANTED:
    for node in re.finditer(r"<node [^>]*>", xml):
        n = node.group(0)
        if package in n and re.search(r"text=\"(" + label + r")\"", n):
            m = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
            if m:
                a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2); sys.exit(0)
' || true)
      # Up to three dialogs per install (Play Protect, then MIUI); each is tapped once it has settled.
      if [ -n "$bounds" ] && [ "${#seen}" -lt 3 ]; then
        sleep "$INSTALL_TAP_DELAY"
        # shellcheck disable=SC2086
        dev shell input tap $bounds > /dev/null 2>&1 || true
        echo "realrun: tapped the install confirmation at $bounds" >> "$log"
        seen="${seen}x"
        sleep 1
      fi
      sleep 0.3
    done
    status=0; wait "$pid" || status=$?
    if [ "$status" = 0 ] && ! grep -q 'Failure \[' <(tail -3 "$log"); then return 0; fi
    echo "realrun: install attempt $attempt of $apk failed (exit $status)" >> "$log"
  done
  return 1
}

facts() { "$PYTHON" -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$LOCAL_NODE/node-facts.json" "$1"; }
peer() { "$PYTHON" -I -c 'import json,sys; print(next(p for p in json.load(open(sys.argv[1]))["peers"] if p["name"]==sys.argv[2])[sys.argv[3]])' "$LOCAL_NODE/peers.json" "$1" "$2"; }
port_of() { "$PYTHON" -I -c 'import sys; print(sys.argv[1].rsplit(":",1)[1].rstrip("/"))' "$1"; }

# Cleanup state: what this run added, removed in reverse order.
SSH_PID=""; CONTROL_PID=""; REVERSED=""
cleanup() {
  set +e
  for port in $REVERSED; do dev reverse --remove "tcp:$port" > /dev/null 2>&1; done
  [ -n "$SSH_PID" ] && kill "$SSH_PID" 2> /dev/null
  [ -n "$CONTROL_PID" ] && kill "$CONTROL_PID" 2> /dev/null
  dev shell input keyevent KEYCODE_HOME > /dev/null 2>&1
  [ "$TARGET" = phone ] && remote "rm -f $REMOTE_DIR/app-realrun.apk $REMOTE_DIR/app-realrun-androidTest.apk" > /dev/null 2>&1
  wait 2> /dev/null
}
trap cleanup EXIT INT TERM

# 1. Node and peers (read only: this script never reconfigures the node).
say "checking the local node"
"$LOCAL_NODE/status.sh" > "$OUT/node-status.log" 2>&1 || die "node is not healthy (see $OUT/node-status.log)"
if [ -n "${RAMFLUX_REALRUN_GATEWAY_CONTAINER:-}" ]; then
  SKEW=$( now=$("${RAMFLUX_REALRUN_CONTAINER_ENGINE:-podman}" exec "$RAMFLUX_REALRUN_GATEWAY_CONTAINER" date -u +%s 2>/dev/null || echo 0); s=$(( $(date -u +%s) - now )); echo "${s#-}" )
  [ "$SKEW" -le 120 ] || say "warning: node clock differs from this machine by ${SKEW} s; sessions may fail with expired branch proofs"
fi
"$LOCAL_NODE/peers.sh" status > "$OUT/peers-status.log" 2>&1 || true
grep -q 'alice: running' "$OUT/peers-status.log" && grep -q 'bob: running' "$OUT/peers-status.log" \
  || say "warning: the peer daemons are not both running (flows that need them report it)"
ORIGIN=$(facts discovery_origin)
DISCOVERY_PORT=$(port_of "$ORIGIN")
GATEWAY_PORT=$(port_of "$(facts gateway_tcp_addr)")
RELAY=$(facts relay_quic_addr)
ORIGIN_HOST=$("$PYTHON" -I -c 'import sys,urllib.parse as u; print(u.urlsplit(sys.argv[1]).hostname)' "$ORIGIN")
case "$NODE_PATH:$ORIGIN_HOST" in
  lan:127.*|lan:localhost) say "warning: the node advertises $ORIGIN_HOST; on --path lan the device cannot reach it (use --path tunnel)" ;;
  tunnel:127.*|tunnel:localhost) ;;
  tunnel:*) die "--path tunnel needs a node that advertises the loopback; it advertises $ORIGIN_HOST (use --path lan)" ;;
esac
say "path $NODE_PATH; node: discovery $ORIGIN, gateway TCP/QUIC $(facts gateway_tcp_addr), relay QUIC $RELAY $( [ "$NODE_PATH" = tunnel ] && echo "(QUIC is not forwarded)")"

# 2. Device.
if [ "$TARGET" = phone ]; then
  [ "$(dev get-state 2>/dev/null | tr -d '\r')" = device ] || die "device $SERIAL is not attached on the adb host ($HOST)"
  if dev shell 'ps -A' | grep -i -E 'instrument|uiautomator' > /dev/null; then die "another instrumentation is running on the phone; try later"; fi
  dev shell dumpsys window | grep -q 'isKeyguardShowing=true' && die "the phone is locked; unlock it and run again"
  remote "mkdir -p $REMOTE_DIR"
else
  [ "$(adb get-state 2>/dev/null)" = device ] || die "no emulator or device on the local adb (an AVD named rf-realrun is the documented choice)"
fi
dev shell getprop ro.product.model > "$OUT/device.txt" 2>&1 || true

# 3. Build the realrun app and the instrumented suite.
APK=app/build/outputs/apk/realrun/app-realrun.apk
TEST_APK=app/build/outputs/apk/androidTest/realrun/app-realrun-androidTest.apk
if [ "$SKIP_BUILD" = 0 ]; then
  say "building the realrun app and suite (log: $OUT/build.log)"
  [ -d core/sdk/build/generated/native/jniLibs ] && export RAMFLUX_NATIVE_PREBUILT=1
  ./gradlew -Pramflux.realrun=true :app:assembleRealrun :app:assembleRealrunAndroidTest --console=plain > "$OUT/build.log" 2>&1 \
    || { grep -E '^e:|What went wrong' -A3 "$OUT/build.log" | head -20 >&2; die "build failed (see $OUT/build.log)"; }
fi
# 4. Install only our package (never the other org.ramflux.android install on a shared phone).
if [ "$NO_INSTALL" = 1 ] || [ "$TEST_APK_ONLY" = 1 ]; then
  dev shell pm list packages "$APP_ID" | tr -d '\r' | grep -qx "package:$APP_ID" || die "$APP_ID is not installed"
  if [ "$TEST_APK_ONLY" = 1 ]; then
    # A new suite against the installed app and its data (the app package is not touched).
    [ -f "$TEST_APK" ] || die "the realrun suite APK is missing"
    if [ "$TARGET" = phone ]; then
      to_host "$TEST_APK"
      install_on_phone app-realrun-androidTest.apk "$OUT/install.log" || die "test install failed (see $OUT/install.log)"
    else
      adb install -r -t "$TEST_APK" >> "$OUT/install.log" 2>&1 || die "test install failed (see $OUT/install.log)"
    fi
  fi
elif [ "$TARGET" = phone ]; then
  [ -f "$APK" ] && [ -f "$TEST_APK" ] || die "realrun APKs are missing"
  if [ "$KEEP_APP" = 0 ]; then
    dev uninstall "$TEST_ID" > /dev/null 2>&1 || true
    dev uninstall "$APP_ID" > /dev/null 2>&1 || true
  fi
  to_host "$APK" "$TEST_APK"
  : > "$OUT/install.log"
  install_on_phone app-realrun.apk "$OUT/install.log" || die "install failed (see $OUT/install.log)"
  install_on_phone app-realrun-androidTest.apk "$OUT/install.log" || die "test install failed (see $OUT/install.log)"
else
  [ -f "$APK" ] && [ -f "$TEST_APK" ] || die "realrun APKs are missing"
  if [ "$KEEP_APP" = 0 ]; then adb uninstall "$APP_ID" > /dev/null 2>&1 || true; fi
  adb install -r -t "$APK" > "$OUT/install.log" 2>&1 && adb install -r -t "$TEST_APK" >> "$OUT/install.log" 2>&1 \
    || die "install failed (see $OUT/install.log)"
fi

# 5. Peer control on a free loopback port of this machine.
CONTROL_PORT=$("$PYTHON" -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
"$PYTHON" -I "$ROOT/scripts/realrun/peer_control.py" --port "$CONTROL_PORT" --out "$OUT" --local-node "$LOCAL_NODE" --rf "$RF_BIN" > "$OUT/peer-control.log" 2>&1 &
CONTROL_PID=$!
i=0; until curl -sf "http://127.0.0.1:$CONTROL_PORT/health" > /dev/null; do i=$((i + 1)); [ $i -lt 50 ] || die "peer control did not start"; sleep 0.2; done

# 6. Port plumbing. On the phone 127.0.0.1:<port> reaches this machine's 127.0.0.1:<port>. For the phone target
# each port goes phone -> adb reverse -> remote loopback <rport> -> ssh -R -> this machine; a remote port that
# is taken is replaced by a free one. With a local adb the reverse rule goes straight to this loopback.
if [ "$NODE_PATH" = tunnel ]; then PORTS="$DISCOVERY_PORT $GATEWAY_PORT $CONTROL_PORT"; else PORTS="$CONTROL_PORT"; fi
if [ "$LOCAL_HOST" = 0 ]; then
  FORWARDS=()
  MAPPING=""
  for port in $PORTS; do
    rport=$port
    # Only lsof runs there for this check; a taken port moves up by 10000 until a free one is found.
    tries=0
    while remote "lsof -nP -iTCP:$rport -sTCP:LISTEN" > /dev/null 2>&1; do
      rport=$(( (rport + 10000) % 55000 + 10000 )); tries=$((tries + 1)); [ $tries -lt 5 ] || die "no free loopback port on $HOST for $port"
    done
    FORWARDS+=(-R "127.0.0.1:$rport:127.0.0.1:$port")
    MAPPING="$MAPPING $port:$rport"
  done
  ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 "${FORWARDS[@]}" "$HOST" > "$OUT/ssh-forward.log" 2>&1 &
  SSH_PID=$!
  sleep 2
  kill -0 "$SSH_PID" 2> /dev/null || die "the SSH remote forward did not start (see $OUT/ssh-forward.log)"
  for pair in $MAPPING; do
    dev reverse "tcp:${pair%%:*}" "tcp:${pair##*:}" > /dev/null || die "adb reverse for ${pair%%:*} failed"
    REVERSED="$REVERSED ${pair%%:*}"
  done
  say "forwarded (phone port:remote port):$MAPPING"
else
  for port in $PORTS; do dev reverse "tcp:$port" "tcp:$port" > /dev/null; REVERSED="$REVERSED $port"; done
fi
dev reverse --list > "$OUT/reverse.txt" 2>&1 || true

# 7. Run the suite (only public values as arguments).
ARGS=(-e rf_realrun 1 -e rf_control "http://127.0.0.1:$CONTROL_PORT" -e rf_node_origin "$ORIGIN"
  -e rf_operator_key "$(facts node_public_key)" -e rf_fingerprint "$(facts node_key_fingerprint)"
  -e rf_node_id "$(facts node_id)")
for name in alice bob; do
  ARGS+=(-e "rf_${name}_principal" "$(peer $name principal_id)" -e "rf_${name}_commitment" "$(peer $name principal_commitment)")
done
# --tests names flow methods by prefix; they are resolved against the suite source so typos fail early.
CLASS_ARG=$SUITE
if [ -n "$TESTS" ]; then
  SOURCE=app/src/androidTest/kotlin/org/ramflux/android/realrun/RealRunFlowTest.kt
  CLASS_ARG=""
  for prefix in $TESTS; do
    method=$(grep -o "fun ${prefix}[A-Za-z0-9_]*()" "$SOURCE" | head -1 | sed 's/^fun //; s/()$//')
    [ -n "$method" ] || die "no flow method starts with $prefix"
    CLASS_ARG="${CLASS_ARG:+$CLASS_ARG,}$SUITE#$method"
  done
fi
say "running ${TESTS:-the whole suite} (log: $OUT/instrument.log)"
# Every invocation starts from a terminated app: its first flow is a cold start (terminate and relaunch).
dev shell am force-stop "$APP_ID" > /dev/null 2>&1 || true
# The phone's log is shared with other apps: it is never cleared; only lines since this start are read.
LOG_SINCE=$(dev shell date "+%m-%d\ %H:%M:%S.000" 2> /dev/null | tr -d '\r' || true)
# The results of earlier invocations in this folder, so this invocation's exit code covers only its own flows.
BEFORE=$(cat "$OUT/results.json" 2> /dev/null || echo '{}')
INSTRUMENT_FROM=0; [ -f "$OUT/instrument.log" ] && INSTRUMENT_FROM=$(wc -l < "$OUT/instrument.log" | tr -d ' ')
set +e
{ echo "== $(date -u '+%H:%M:%S') ${TESTS:-all}"; dev shell am instrument -w -r "${ARGS[@]}" -e class "$CLASS_ARG" "$RUNNER"; } >> "$OUT/instrument.log" 2>&1
set -e
# Only the app's own lines (its uid; the process has ended when the suite returns), appended per invocation.
APP_UID=$(dev shell cmd package list packages -U "$APP_ID" 2> /dev/null | tr -d '\r' | grep -x "package:$APP_ID uid:[0-9]*" | sed 's/.*uid://' || true)
if [ -n "$APP_UID" ]; then
  { echo "== $(date -u '+%H:%M:%S') ${TESTS:-all} (uid $APP_UID)"; dev logcat -d ${LOG_SINCE:+-T "$LOG_SINCE"} --uid="$APP_UID"; } \
    | grep -v -E 'InsetsSource|UiAutomation|AccessibilityNodeInfo|QueryController|InteractionController' >> "$OUT/app-logcat.txt" 2> /dev/null || true
fi
# The app's debug trace file (codes and names only), read through run-as: some phones keep none of the app's own
# log lines. It holds every invocation since this install, so it is copied whole.
dev shell run-as "$APP_ID" cat no_backup/debug-trace.log > "$OUT/app-trace.txt" 2> /dev/null || true
# A fresh install starts a new trace file: each invocation's copy is also kept, so no earlier install's trace is lost.
mkdir -p "$OUT/traces"
cp "$OUT/app-trace.txt" "$OUT/traces/$(date -u '+%H%M%S')-$(printf '%s' "${TESTS:-all}" | tr -c 'a-z0-9' '-').txt" 2> /dev/null || true

# 8. The control server stops writing results; then the flow results and the instrumentation are made to agree (a
# flow left RUNNING by a dead process, or a test the instrumentation failed, becomes FAIL).
kill "$CONTROL_PID" 2> /dev/null; wait "$CONTROL_PID" 2> /dev/null || true; CONTROL_PID=""
RECONCILED=0
"$PYTHON" -I "$ROOT/scripts/realrun/reconcile.py" "$OUT" "$INSTRUMENT_FROM" || RECONCILED=$?

# 9. Flow table.
"$PYTHON" -I "$ROOT/scripts/realrun/flow_table.py" "$OUT" "$(( $(date +%s) - START ))" > "$OUT/flow-table.md"
cat "$OUT/flow-table.md"
say "output: $OUT"
# Non-zero when a flow recorded by this invocation failed, or the instrumentation itself crashed or failed.
FAILED_HERE=$(BEFORE="$BEFORE" "$PYTHON" -I -c '
import json, os, sys
before = json.loads(os.environ["BEFORE"] or "{}")
try:
    after = json.load(open(sys.argv[1]))
except (OSError, ValueError):
    after = {}
print(" ".join(sorted(k for k, v in after.items() if v.get("status") == "FAIL" and before.get(k) != v)))
' "$OUT/results.json")
if [ "$RECONCILED" != 0 ]; then say "the flow table and the instrumentation disagree (see above)"; exit 1; fi
if [ -n "$FAILED_HERE" ]; then say "failed in this invocation: $FAILED_HERE"; exit 1; fi
if tail -n +"$(( INSTRUMENT_FROM + 1 ))" "$OUT/instrument.log" | grep -q -E 'INSTRUMENTATION_FAILED|Process crashed|FAILURES!!!'; then
  say "the instrumentation failed in this invocation (see $OUT/instrument.log)"
  exit 1
fi
exit 0
