#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Loopback control server for the opt-in real-run UI suite (scripts/realrun.sh).

The instrumented suite on the phone reaches this server over plain HTTP through `adb reverse` (and,
for a phone attached to another machine, an SSH remote forward that binds only that machine's loopback), and
asks it to act as the peer side at the right moments: run an allow-listed `rf`
command on the alice or bob daemon, derive a direct conversation id, create a test file, record
a flow result or store a screenshot or a UI dump. The server never receives the phone account's six-digit code
or recovery words; it only handles public identifiers and test message text.

Usage: peer_control.py --port N --out DIR --local-node DIR --rf PATH [--udid UDID --app PATH]

With --udid and --app it can also reinstall the app for the restore flow (/fresh-install): it copies the
newest backup archive out of the app's container into the simulator's "On My iPhone" storage (an encrypted
archive; its passphrase stays in the test process), uninstalls the app, resets the simulator Keychain and
installs the app again.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# First rf words the test may run on a peer daemon. Every command gets the peer's socket; account
# scoped commands get `--account` from peers.json. Nothing that exports keys or reads secrets.
ALLOWED = {
    ("contact", "card"), ("contact", "list"), ("contact", "accept"), ("contact", "add"), ("contact", "safety-number"),
    ("contact", "verification"), ("conversation", "send"), ("conversation", "reply"), ("conversation", "edit"),
    ("conversation", "delete"), ("conversation", "typing"), ("dm", "read"), ("dm", "list"), ("dm", "send"),
    ("dm", "receipt"), ("group", "list"), ("group", "read"), ("group", "send"), ("group", "accept"), ("group", "show"),
    ("group", "invitation"), ("group", "member"), ("attachment", "import"), ("attachment", "enable"), ("account", "status"),
}
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
STATUSES = {"PASS", "FAIL", "BLOCKED", "NOT RUN", "RUNNING"}


BUNDLE = "org.ramflux.ios"


class Harness:
    def __init__(self, out: Path, local_node: Path, rf: str, udid: str = "", app: str = ""):
        self.out, self.local_node, self.rf, self.udid, self.app = out, local_node, rf, udid, app
        self.facts = json.loads((local_node / "node-facts.json").read_text())
        self.peers = {p["name"]: p for p in json.loads((local_node / "peers.json").read_text())["peers"]}
        self.lock = threading.Lock()
        # Results accumulate across invocations of scripts/realrun.sh that share one output folder.
        previous = out / "results.json"
        self.results = json.loads(previous.read_text()) if previous.exists() else {}
        (out / "screenshots").mkdir(parents=True, exist_ok=True)
        (out / "files").mkdir(parents=True, exist_ok=True)
        self.log_path = out / "peer-commands.log"

    def log(self, line: str):
        with self.lock, self.log_path.open("a") as handle:
            handle.write(time.strftime("%H:%M:%S ") + line + "\n")

    def config(self):
        return {"node": {k: self.facts[k] for k in ("discovery_origin", "node_id", "node_public_key", "node_key_fingerprint")},
                "peers": {name: {k: v for k, v in p.items() if k != "daemon_socket"} for name, p in self.peers.items()}}

    def run_rf(self, peer: str, args: list, timeout: float = 60):
        if peer not in self.peers:
            raise ValueError("unknown peer")
        if len(args) < 2 or not all(isinstance(a, str) for a in args) or (args[0], args[1]) not in ALLOWED:
            raise ValueError("command not allowed")
        command = [self.rf, "--socket", self.peers[peer]["daemon_socket"], *args]
        sub = args[2] if len(args) > 2 and not args[2].startswith("-") else None
        if "--account" not in args and not (args[0] == "conversation" and args[1] == "typing" and sub is None):
            command += ["--account", self.peers[peer]["account_id"]]
        # Conversation mutations require an idempotency token; each request is a new logical operation.
        if args[0] == "conversation" and "--operation-id" not in args and \
                (args[1] in ("send", "reply", "edit", "delete") or (args[1] == "typing" and sub in ("start", "stop"))):
            command += ["--operation-id", "realrun-" + os.urandom(8).hex()]
        started = time.time()
        try:
            done = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
            code, stdout, stderr = done.returncode, done.stdout, done.stderr
        except subprocess.TimeoutExpired as error:
            code, stdout, stderr = 124, error.stdout or "", "timeout"
            stdout = stdout.decode() if isinstance(stdout, bytes) else stdout
        self.log(f"{peer}: rf {' '.join(args[:2])} ... exit {code} in {time.time() - started:.1f}s"
                 + ("" if code == 0 else f" stderr={stderr.strip()[-400:]}"))
        value = None
        start = stdout.find("{")
        if start >= 0:
            try:
                value = json.JSONDecoder().raw_decode(stdout[start:])[0]
            except ValueError:
                value = None
        return {"exit": code, "json": value, "stdout": stdout[-20000:], "stderr": stderr[-4000:]}

    def dm_conversation(self, first: str, second: str) -> str:
        helper = self.local_node / "lib" / "rfbeta_util.py"
        done = subprocess.run([sys.executable, "-I", str(helper), "dm-conversation", first, second],
                              capture_output=True, text=True, timeout=30, check=True)
        return done.stdout.strip()

    def make_file(self, name: str, kind: str, size: int) -> dict:
        if not SAFE_NAME.match(name) or not 1 <= size <= 4 * 1024 * 1024:
            raise ValueError("bad file request")
        path = self.out / "files" / name
        if kind == "png":
            path.write_bytes(png(size))
        else:
            seed = hashlib.sha256(name.encode()).digest()
            data = bytearray()
            while len(data) < size:
                seed = hashlib.sha256(seed).digest()
                data += base64.b64encode(seed)[:43] + b"\n"
            path.write_bytes(bytes(data[:size]))
        return {"path": str(path), "sha256": sha256(path), "size": path.stat().st_size}

    def file_info(self, path: str) -> dict:
        target = Path(path).resolve()
        if not str(target).startswith(str((self.out / "files").resolve())):
            raise ValueError("path outside the run folder")
        return {"exists": target.exists(), "sha256": sha256(target) if target.exists() else None,
                "size": target.stat().st_size if target.exists() else 0}

    def simctl(self, *args, timeout: float = 120) -> str:
        return subprocess.run(["xcrun", "simctl", *args], capture_output=True, text=True, timeout=timeout, check=True).stdout.strip()

    def fresh_install(self) -> dict:
        """Moves the newest backup archive to "On My iPhone", then reinstalls the app with an empty Keychain."""
        if not self.udid or not self.app:
            raise ValueError("fresh install needs --udid and --app")
        container = Path(self.simctl("get_app_container", self.udid, BUNDLE, "data"))
        archives = sorted((p for p in (container / "Library" / "Application Support" / "ramflux" / "backups").rglob("*") if p.is_file()),
                          key=lambda p: p.stat().st_mtime)
        if not archives:
            raise ValueError("no backup archive in the app container")
        groups = container.parents[2] / "Shared" / "AppGroup"
        local = None
        for group in groups.iterdir():
            meta = group / ".com.apple.mobile_container_manager.metadata.plist"
            if meta.exists() and "group.com.apple.FileProvider.LocalStorage" in subprocess.run(
                    ["plutil", "-extract", "MCMMetadataIdentifier", "raw", str(meta)], capture_output=True, text=True).stdout:
                local = group / "File Provider Storage"
        if local is None:
            raise ValueError("the simulator has no On My iPhone storage")
        local.mkdir(parents=True, exist_ok=True)
        name = "ramflux-realrun-" + time.strftime("%H%M%S") + (archives[-1].suffix or ".backup")
        (local / name).write_bytes(archives[-1].read_bytes())
        subprocess.run(["xcrun", "simctl", "terminate", self.udid, BUNDLE], capture_output=True, timeout=60)
        self.simctl("uninstall", self.udid, BUNDLE)
        self.simctl("keychain", self.udid, "reset")
        self.simctl("install", self.udid, self.app, timeout=300)
        self.log(f"fresh install: archive {name} ({archives[-1].stat().st_size} bytes) placed in On My iPhone; app reinstalled; keychain reset")
        return {"archive": name}

    def record(self, flow: str, status: str, detail: str):
        if status not in STATUSES:
            raise ValueError("bad status")
        with self.lock:
            self.results[flow] = {"status": status, "detail": detail[:2000], "at": time.strftime("%H:%M:%S")}
            (self.out / "results.json").write_text(json.dumps(self.results, indent=2) + "\n")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def png(size_hint: int) -> bytes:
    """A small RGB PNG with a deterministic gradient (about `size_hint` bytes of raw pixels)."""
    import struct
    import zlib
    side = max(8, min(512, int((size_hint / 3) ** 0.5)))
    rows = b"".join(b"\0" + bytes(((x * 7 + y * 3) % 256, (x * 2) % 256, (y * 5) % 256) [i] for x in range(side) for i in range(3))
                    for y in range(side))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", side, side, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def handler_for(harness: Harness):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # keep the console quiet; commands are logged by the harness
            pass

        def reply(self, status, value):
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/config":
                return self.reply(200, harness.config())
            if self.path == "/health":
                return self.reply(200, {"ok": True})
            return self.reply(404, {"error": "not found"})

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            try:
                if self.path.startswith("/screenshot/"):
                    name = self.path.split("/", 2)[2]
                    if not SAFE_NAME.match(name):
                        raise ValueError("bad name")
                    (harness.out / "screenshots" / (name + ".png")).write_bytes(raw)
                    return self.reply(200, {"ok": True})
                if self.path.startswith("/dump/"):
                    name = self.path.split("/", 2)[2]
                    if not SAFE_NAME.match(name):
                        raise ValueError("bad name")
                    (harness.out / "dumps").mkdir(exist_ok=True)
                    (harness.out / "dumps" / (time.strftime("%H%M%S-") + name + ".xml")).write_bytes(raw)
                    return self.reply(200, {"ok": True})
                body = json.loads(raw or b"{}")
                if self.path == "/rf":
                    return self.reply(200, harness.run_rf(body["peer"], body["args"], float(body.get("timeout", 60))))
                if self.path == "/dm-conversation":
                    return self.reply(200, {"conversation": harness.dm_conversation(body["first"], body["second"])})
                if self.path == "/make-file":
                    return self.reply(200, harness.make_file(body["name"], body.get("kind", "text"), int(body.get("size", 1024))))
                if self.path == "/fresh-path":
                    name = body.get("name", "import.bin")
                    if not SAFE_NAME.match(name):
                        raise ValueError("bad name")
                    target = harness.out / "files" / (time.strftime("%H%M%S-") + os.urandom(3).hex() + "-" + name)
                    return self.reply(200, {"path": str(target)})
                if self.path == "/file-info":
                    return self.reply(200, harness.file_info(body["path"]))
                if self.path == "/fresh-install":
                    return self.reply(200, harness.fresh_install())
                if self.path == "/result":
                    harness.record(body["flow"], body["status"], body.get("detail", ""))
                    return self.reply(200, {"ok": True})
                if self.path == "/log":
                    harness.log("test: " + str(body.get("line", ""))[:60000])
                    return self.reply(200, {"ok": True})
                return self.reply(404, {"error": "not found"})
            except (KeyError, ValueError, TypeError, OSError, subprocess.SubprocessError) as error:
                harness.log(f"request {self.path} refused: {error!r}")
                return self.reply(400, {"error": str(error)})
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--local-node", type=Path, required=True)
    parser.add_argument("--rf", default=os.environ.get("RAMFLUX_REALRUN_RF", ""),
                        help="path of the rf CLI binary (default: $RAMFLUX_REALRUN_RF)")
    parser.add_argument("--udid", default="")
    parser.add_argument("--app", default="")
    options = parser.parse_args()
    if not options.rf:
        parser.error("--rf (or RAMFLUX_REALRUN_RF) is required: the path of the rf CLI binary")
    options.out.mkdir(parents=True, exist_ok=True)
    harness = Harness(options.out, options.local_node, options.rf, options.udid, options.app)
    server = ThreadingHTTPServer(("127.0.0.1", options.port), handler_for(harness))
    print(f"peer control listening on 127.0.0.1:{options.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    os.umask(0o077)
    main()
