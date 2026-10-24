#!/usr/bin/env -S uv run --quiet --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["debugpy==1.8.22"]
# ///
"""AVF Studio's Python debug adapter: debugpy's DAP adapter on stdin/stdout.

uv keeps debugpy in an isolated cached environment, so nothing is installed into the user's
interpreters. The adapter starts the program with the interpreter named in the launch request
(`"python"`), which can be any guest Python 3.10+ (system, conda, venv).
"""
import runpy
import sys

if __name__ == "__main__":
    sys.argv = ["debugpy.adapter", *sys.argv[1:]]
    runpy.run_module("debugpy.adapter", run_name="__main__", alter_sys=True)
