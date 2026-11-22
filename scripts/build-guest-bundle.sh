#!/usr/bin/env bash
# Builds the arm64 guest daemon that the Android app bundles for "Set up guest".
# The app's build copies target/aarch64-unknown-linux-gnu/release/nookra-daemon and
# scripts/install-native-daemon.sh into its assets when they exist.
set -euo pipefail
cd "$(dirname "$0")/.."
PATH="$HOME/.cargo/bin:$PATH" cargo zigbuild --release -p avf-daemon --target aarch64-unknown-linux-gnu.2.28
ls -l target/aarch64-unknown-linux-gnu/release/nookra-daemon
