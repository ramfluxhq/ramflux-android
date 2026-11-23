#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
#
# Opt-in real run of the beta flows: the realrun build of the app (debug under the application id
# org.ramflux.android.realrun, so it installs beside any other build) with the real SDK against the
# local node in ops/local-node, with the alice and bob peers driven through the rf CLI on this Mac.
#
#   scripts/realrun.sh [--out DIR] [--target phone|emulator] [--path lan|tunnel] [--keep-app] [--skip-build]
#                      [--no-install | --test-apk-only] [--tests "f01 f02 ..."]
#
# One invocation must finish inside a ten-minute command budget on a shared Mac, so the flows can be run in
# several invocations against the same --out directory: the first with a fresh install (the default), later
# ones with --skip-build --no-install and --tests naming the flow methods by prefix (f03, f04, ...). Results
# accumulate in DIR/results.json and the flow table is rewritten after each invocation.
#
# Node paths (the origin, operator key and fingerprint are read from node-facts.json at run time):
#   lan       (default) the device talks to the origin in node-facts.json directly, TCP and QUIC; only the
#             peer-control port is forwarded. Needs a node that advertises an address the device can reach.
#   tunnel    the device's 127.0.0.1:<port> reaches this Mac's loopback for discovery and the gateway (and the
#             control port), as below. TCP only: the QUIC relay is not carried, so media is expected BLOCKED.
#
# Targets:
#   phone     a phone attached by USB to another Mac reached over SSH (RAMFLUX_REALRUN_HOST, default xx.dev;
#             RAMFLUX_REALRUN_ADB and RAMFLUX_REALRUN_SERIAL name its adb and the device). Node, gateway and
#             control traffic reach this Mac's loopback through `adb reverse` on that host and an SSH remote
#             forward that binds only that host's loopback. TCP only: the QUIC relay is not carried.
#   emulator  a local emulator or device on this Mac's adb (`adb reverse` straight to the loopback).
#
# The account code is generated and the recovery words are read inside the instrumentation process only;
# nothing secret is passed as an argument, written here or logged. Every forward and reverse rule this
# script adds is removed at the end, also on failure; the phone is left on its home screen.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
LOCAL_NODE=${RAMFLUX_LOCAL_NODE:-/Users/nn/worker/ramfluxhq/ops/local-node}
MOBILE_ENV=${RAMFLUX_MOBILE_ENV:-/Users/nn/worker/ramfluxhq/ops/mobile-env.sh}
HOST=${RAMFLUX_REALRUN_HOST:-xx.dev}
REMOTE_ADB=${RAMFLUX_REALRUN_ADB:-/Users/xx/Library/Android/sdk/platform-tools/adb}
SERIAL=${RAMFLUX_REALRUN_SERIAL:-49cuqgtgjnof8lvo}
REMOTE_DIR=/tmp/nn-realrun
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
    *) echo "usage: scripts/realrun.sh [--out DIR] [--target phone|emulator] [--path lan|tunnel] [--keep-app] [--skip-build] [--no-install | --test-apk-only] [--tests \"f01 f02\"]" >&2; exit 2 ;;
  esac
done
case "$TARGET" in phone|emulator) ;; *) echo "realrun: --target is phone or emulator" >&2; exit 2 ;; esac
case "$NODE_PATH" in lan|tunnel) ;; *) echo "realrun: --path is lan or tunnel" >&2; exit 2 ;; esac

say() { printf 'realrun: %s\n' "$*"; }
die() { printf 'realrun: error: %s\n' "$*" >&2; exit 1; }

# shellcheck disable=SC1090
[ -f "$MOBILE_ENV" ] && . "$MOBILE_ENV"
PYTHON=/opt/homebrew/bin/python3; [ -x "$PYTHON" ] || PYTHON=python3
cd "$ROOT"
mkdir -p "$OUT"
OUT=$(CDPATH='' cd -- "$OUT" && pwd)
START=$(date +%s)

# Commands on the device: through the remote host's adb for the phone, the local adb otherwise. Only adb
# against the one serial and file operations inside $REMOTE_DIR run on the remote host.
remote() { ssh -o BatchMode=yes -o ConnectTimeout=15 "$HOST" "$@"; }
dev() {
  if [ "$TARGET" = phone ]; then
    local quoted; quoted=$(printf '%q ' "$@")
    remote "$REMOTE_ADB -s $SERIAL $quoted"
  else
    adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"
  fi
}

# Installs one APK already copied to $REMOTE_DIR on the phone. MIUI answers `adb install` with a "USB install"
# confirmation that has a countdown; its confirm button only takes a tap a little after the dialog appears.
# The install runs in the background of this function while the screen is dumped until the button shows,
# then the button is tapped about INSTALL_TAP_DELAY seconds after it was first seen. One retry on refusal.
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
for node in re.finditer(r"<node [^>]*>", xml):
    n = node.group(0)
    if "com.miui.securitycenter" in n and re.search(r"text=\"(继续安装|Continue installation|Install)\"", n):
        m = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
        if m:
            a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2); break
' || true)
      if [ -n "$bounds" ] && [ -z "$seen" ]; then
        sleep "$INSTALL_TAP_DELAY"
        # shellcheck disable=SC2086
        dev shell input tap $bounds > /dev/null 2>&1 || true
        echo "realrun: tapped the install confirmation at $bounds" >> "$log"
        seen=tapped
        break
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
"$LOCAL_NODE/status.sh" > "$OUT/node-status.log" 2>&1 || die "local node is not healthy (see $OUT/node-status.log)"
SKEW=$( now=$(podman exec rfbeta_ramflux-gateway_1 date -u +%s 2>/dev/null || echo 0); s=$(( $(date -u +%s) - now )); echo "${s#-}" )
[ "$SKEW" -le 120 ] || say "warning: node clock differs from the Mac by ${SKEW} s; sessions may fail with expired branch proofs"
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
  [ "$(dev get-state 2>/dev/null | tr -d '\r')" = device ] || die "device $SERIAL is not attached on $HOST"
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
      scp -q -o BatchMode=yes "$TEST_APK" "$HOST:$REMOTE_DIR/"
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
  scp -q -o BatchMode=yes "$APK" "$TEST_APK" "$HOST:$REMOTE_DIR/"
  : > "$OUT/install.log"
  install_on_phone app-realrun.apk "$OUT/install.log" || die "install failed (see $OUT/install.log)"
  install_on_phone app-realrun-androidTest.apk "$OUT/install.log" || die "test install failed (see $OUT/install.log)"
else
  [ -f "$APK" ] && [ -f "$TEST_APK" ] || die "realrun APKs are missing"
  if [ "$KEEP_APP" = 0 ]; then adb uninstall "$APP_ID" > /dev/null 2>&1 || true; fi
  adb install -r -t "$APK" > "$OUT/install.log" 2>&1 && adb install -r -t "$TEST_APK" >> "$OUT/install.log" 2>&1 \
    || die "install failed (see $OUT/install.log)"
fi

# 5. Peer control on a free loopback port of this Mac.
CONTROL_PORT=$("$PYTHON" -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
"$PYTHON" -I "$ROOT/scripts/realrun/peer_control.py" --port "$CONTROL_PORT" --out "$OUT" --local-node "$LOCAL_NODE" > "$OUT/peer-control.log" 2>&1 &
CONTROL_PID=$!
i=0; until curl -sf "http://127.0.0.1:$CONTROL_PORT/health" > /dev/null; do i=$((i + 1)); [ $i -lt 50 ] || die "peer control did not start"; sleep 0.2; done

# 6. Port plumbing. On the phone 127.0.0.1:<port> reaches this Mac's 127.0.0.1:<port>. For the phone target
# each port goes phone -> adb reverse -> remote loopback <rport> -> ssh -R -> this Mac; a remote port that is
# taken is replaced by a free one.
if [ "$NODE_PATH" = tunnel ]; then PORTS="$DISCOVERY_PORT $GATEWAY_PORT $CONTROL_PORT"; else PORTS="$CONTROL_PORT"; fi
if [ "$TARGET" = phone ]; then
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
  for port in $PORTS; do adb reverse "tcp:$port" "tcp:$port" > /dev/null; REVERSED="$REVERSED $port"; done
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
# The phone's log is shared with other apps: it is never cleared; only lines since this start are read.
LOG_SINCE=$(dev shell date "+%m-%d\ %H:%M:%S.000" 2> /dev/null | tr -d '\r' || true)
set +e
{ echo "== $(date -u '+%H:%M:%S') ${TESTS:-all}"; dev shell am instrument -w -r "${ARGS[@]}" -e class "$CLASS_ARG" "$RUNNER"; } >> "$OUT/instrument.log" 2>&1
set -e
# Only the app's own lines (its uid; the process has ended when the suite returns), appended per invocation.
APP_UID=$(dev shell cmd package list packages -U "$APP_ID" 2> /dev/null | tr -d '\r' | grep -x "package:$APP_ID uid:[0-9]*" | sed 's/.*uid://' || true)
if [ -n "$APP_UID" ]; then
  { echo "== $(date -u '+%H:%M:%S') ${TESTS:-all} (uid $APP_UID)"; dev logcat -d ${LOG_SINCE:+-T "$LOG_SINCE"} --uid="$APP_UID"; } \
    | grep -v -E 'InsetsSource|UiAutomation|AccessibilityNodeInfo|QueryController|InteractionController' >> "$OUT/app-logcat.txt" 2> /dev/null || true
fi

# 8. Flow table.
"$PYTHON" -I "$ROOT/scripts/realrun/flow_table.py" "$OUT" "$(( $(date +%s) - START ))" > "$OUT/flow-table.md"
cat "$OUT/flow-table.md"
say "output: $OUT"
grep -q '| FAIL |' "$OUT/flow-table.md" && exit 1
exit 0
