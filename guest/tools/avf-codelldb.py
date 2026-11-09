#!/usr/bin/env python3
"""AVF Studio's C, C++ and Rust debug adapter: CodeLLDB's DAP adapter on stdin/stdout.

    avf-codelldb setup     downloads the pinned CodeLLDB release (checksum-verified) and writes
                           the `codelldb` command the app checks for
    avf-codelldb remove    removes them again
    avf-codelldb [--settings JSON]
                           the adapter, on stdio (the app starts it as the `codelldb` tool);
                           JSON is CodeLLDB's adapter settings (`sourceLanguages`, …)

CodeLLDB's adapter speaks DAP over TCP only. As VS Code's extension does, this listens on a free
local port and has the adapter connect to it (`--connect`), then joins stdio to that connection.
Everything lives under ~/.local/share/materialcode/debuggers; it brings its own LLDB, so nothing
is installed system-wide.
"""
import hashlib
import os
import platform
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import zipfile
from pathlib import Path

VERSION = "1.12.3"
SHA256 = {
    "aarch64": "0887f67d440554617894266f80706b700907c36b95e6e49d23b95a0e05318101",
    "x86_64": "1cd7f386598022b51a5b93b9ffa23e812b23f519cfe1833384ec4bef4bfd1be1",
}
VSIX = {"aarch64": "codelldb-linux-arm64.vsix", "x86_64": "codelldb-linux-x64.vsix"}
# The app's former name: guests already have their installs here, so the folder keeps it.
ROOT = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share") / "materialcode" / "debuggers"
DIRECTORY = ROOT / f"codelldb-{VERSION}"
ADAPTER = DIRECTORY / "adapter" / "codelldb"
LIBLLDB = DIRECTORY / "lldb" / "lib" / "liblldb.so"
WRAPPER = Path.home() / ".local/bin/codelldb"
# The app's former name: the marker finds files written by earlier versions, so it stays.
MARKER = "# Written by MaterialCode (avf-codelldb)."


def fail(message: str) -> None:
    sys.stderr.write(f"avf-codelldb: {message}\n")
    sys.exit(1)


def setup() -> None:
    arch = platform.machine()
    if arch not in VSIX:
        fail(f"no CodeLLDB build for {arch}")
    if not ADAPTER.is_file():
        ROOT.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix=".codelldb.", dir=ROOT))
        try:
            archive = staging / VSIX[arch]
            print(f"Downloading CodeLLDB {VERSION} (about 55 MB)…", flush=True)
            url = f"https://github.com/vadimcn/codelldb/releases/download/v{VERSION}/{VSIX[arch]}"
            subprocess.run(
                ["curl", "-fL", "--proto", "=https", "--proto-redir", "=https", "-o", str(archive), url],
                check=True,
            )
            digest = hashlib.sha256()
            with archive.open("rb") as file:
                while chunk := file.read(1 << 20):
                    digest.update(chunk)
            if digest.hexdigest() != SHA256[arch]:
                fail("CodeLLDB checksum mismatch")
            print("Unpacking…", flush=True)
            unpacked = staging / "out"
            with zipfile.ZipFile(archive) as vsix:
                for entry in vsix.infolist():
                    name = entry.filename
                    if not name.startswith("extension/") or name.endswith("/"):
                        continue
                    relative = Path(name[len("extension/"):])
                    if relative.is_absolute() or ".." in relative.parts:
                        fail(f"unsafe path in the archive: {name}")
                    target = unpacked / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with vsix.open(entry) as source, target.open("wb") as destination:
                        shutil.copyfileobj(source, destination)
                    # zipfile drops the permission bits; the adapter and LLDB's tools need theirs.
                    if (entry.external_attr >> 16) & 0o111:
                        target.chmod(0o755)
            if not (unpacked / "adapter" / "codelldb").is_file():
                fail("CodeLLDB's archive has no adapter")
            shutil.rmtree(DIRECTORY, ignore_errors=True)
            (unpacked).rename(DIRECTORY)
        finally:
            shutil.rmtree(staging, ignore_errors=True)
    # The app checks for this command; in a terminal it runs the adapter on a port of your choice.
    if WRAPPER.exists() and MARKER not in WRAPPER.read_text(errors="replace"):
        print(f"Keeping your own {WRAPPER}.")
    else:
        WRAPPER.parent.mkdir(parents=True, exist_ok=True)
        staged = WRAPPER.with_name(".codelldb.tmp")
        staged.write_text(f'#!/bin/sh\n{MARKER}\nexec "{ADAPTER}" --liblldb "{LIBLLDB}" --port "${{1:-13000}}"\n')
        staged.chmod(0o755)
        staged.rename(WRAPPER)
    print("CodeLLDB is ready.")


def remove() -> None:
    if WRAPPER.exists() and MARKER in WRAPPER.read_text(errors="replace"):
        WRAPPER.unlink()
    for directory in ROOT.glob("codelldb-*"):
        shutil.rmtree(directory, ignore_errors=True)
    print("CodeLLDB is removed.")


def serve(extra: list) -> None:
    if not ADAPTER.is_file():
        fail("CodeLLDB is not set up; run: avf-native-daemon tools install codelldb")
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    listener.settimeout(20)
    port = listener.getsockname()[1]
    adapter = subprocess.Popen(
        [str(ADAPTER), "--liblldb", str(LIBLLDB), "--connect", str(port), *extra],
        stdin=subprocess.DEVNULL,
        stdout=sys.stderr,
    )
    try:
        connection, _ = listener.accept()
    except socket.timeout:
        adapter.kill()
        fail("CodeLLDB's adapter did not connect")
    listener.close()
    connection.settimeout(None)

    def upstream() -> None:
        while chunk := sys.stdin.buffer.read1(65536):
            connection.sendall(chunk)
        # The app went away: the adapter ends its session (and the program it started).
        connection.shutdown(socket.SHUT_WR)

    threading.Thread(target=upstream, daemon=True).start()
    while chunk := connection.recv(65536):
        sys.stdout.buffer.write(chunk)
        sys.stdout.buffer.flush()
    try:
        adapter.wait(timeout=5)
    except subprocess.TimeoutExpired:
        adapter.kill()


if __name__ == "__main__":
    command = sys.argv[1:]
    if command == ["setup"]:
        setup()
    elif command == ["remove"]:
        remove()
    elif not command or (len(command) == 2 and command[0] == "--settings"):
        serve(command)
    else:
        fail("usage: avf-codelldb [setup|remove|--settings JSON]")
