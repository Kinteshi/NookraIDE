#!/usr/bin/env -S uv run --quiet --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["debugpy==1.8.22"]
# ///
"""QuireCode's Python debug adapter: debugpy's DAP adapter on stdin/stdout.

uv keeps debugpy in an isolated cached environment, so nothing is installed into the user's
interpreters. The adapter starts the program with the interpreter named in the launch request
(`"python"`), which can be any guest Python 3.10+ (system, conda, venv).
"""
import runpy
import socket
import sys
import threading


def bridge(host: str, port: int) -> None:
    """`--connect host port`: a program started with `debugpy --listen` runs its own adapter as a
    DAP server, so attaching talks to it directly (as VS Code does); this joins stdio to it."""
    try:
        connection = socket.create_connection((host, port), timeout=10)
    except OSError as error:
        sys.stderr.write(f"avf-debugpy: nothing is listening on {host}:{port} ({error.strerror or error})\n")
        sys.exit(1)
    connection.settimeout(None)

    def upstream() -> None:
        while chunk := sys.stdin.buffer.read1(65536):
            connection.sendall(chunk)
        connection.shutdown(socket.SHUT_WR)

    threading.Thread(target=upstream, daemon=True).start()
    while chunk := connection.recv(65536):
        sys.stdout.buffer.write(chunk)
        sys.stdout.buffer.flush()


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--connect":
        bridge(sys.argv[2], int(sys.argv[3]))
        sys.exit(0)
    sys.argv = ["debugpy.adapter", *sys.argv[1:]]
    runpy.run_module("debugpy.adapter", run_name="__main__", alter_sys=True)
