#!/usr/bin/env bash
# Installs the Track B native daemon in the AVF Debian guest, beside Track A's Supervisor.
# Usage (inside the guest): bash install-native-daemon.sh [/path/to/avf-daemon] [workspace]
# Without a path it uses the avf-native-daemon next to this script (as AVF Studio's
# "Set up guest" places it). It ends by printing a one-time pairing code for the app.
# The daemon listens on 127.0.0.1:19870 as the user unit avf-native.service; its session
# token is in ~/.config/avf-native/env (mode 0600). Paste it into the app's Connect dialog.
# Safe to rerun, including on a guest reset from Terminal → Settings → Recovery.
set -euo pipefail

# uv runs ty, ruff and the Jupyter bridge. Pinned and checksum-verified, like the tool catalog.
UV_VERSION=0.13.0
declare -A UV_SHA256=(
  [aarch64]=3ccfb6af6e242433eb552f7d9676abd5412c6595c497e990d9c8cb7b5bd4d2c3
  [x86_64]=1468ebd5a5541121837c5a2817b9972ba6090fa6caa3d142620850a47fb75154
)

daemon_source=${1:-"$(dirname "$(readlink -f "$0")")/avf-native-daemon"}
[[ -f "$daemon_source" ]] || { echo "usage: $0 [/path/to/avf-daemon] [workspace]" >&2; exit 2; }
workspace=${2:-$HOME/Projects}
config="$HOME/.config/avf-native"
unit="$HOME/.config/systemd/user/avf-native.service"

install -D -m 755 "$daemon_source" "$HOME/.local/bin/avf-native-daemon.new"
mv "$HOME/.local/bin/avf-native-daemon.new" "$HOME/.local/bin/avf-native-daemon"
mkdir -p "$config" "$workspace" "$(dirname "$unit")"
chmod 700 "$config"
if [[ ! -f "$config/env" ]]; then
  (umask 077 && printf 'AVF_TOKEN=%s\n' "$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')" >"$config/env")
fi

cat >"$unit" <<UNIT
[Unit]
Description=AVF Studio native IDE daemon (Track B)

[Service]
Type=simple
ExecStart=%h/.local/bin/avf-native-daemon
WorkingDirectory=$workspace
EnvironmentFile=%h/.config/avf-native/env
Environment=AVF_WORKSPACE_ROOTS=$workspace
Environment=AVF_BIND_ADDR=127.0.0.1:19870
Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin
Restart=on-failure
RestartSec=2
NoNewPrivileges=true
UMask=0077

[Install]
WantedBy=default.target
UNIT

systemctl --user daemon-reload
systemctl --user enable avf-native.service
# stop/sleep/start, never restart: the Terminal app's port forwarder can lose the port otherwise.
systemctl --user stop avf-native.service || true
sleep 3
systemctl --user start avf-native.service

export PATH="$HOME/.local/bin:$PATH"
# Source Control needs git; install it from Debian when sudo works without a password.
if ! command -v git >/dev/null; then
  sudo -n apt-get install -y git >/dev/null 2>&1 || echo "warning: git is missing; install it with: sudo apt install git" >&2
fi
if ! command -v uv >/dev/null; then
  arch=$(uname -m)
  archive=$(mktemp)
  curl -fsSL --proto '=https' -o "$archive" \
    "https://github.com/astral-sh/uv/releases/download/$UV_VERSION/uv-$arch-unknown-linux-gnu.tar.gz"
  echo "${UV_SHA256[$arch]:?no uv checksum for $arch}  $archive" | sha256sum -c --quiet
  tar -xzf "$archive" -C "$HOME/.local/bin" --strip-components=1 "uv-$arch-unknown-linux-gnu/uv" "uv-$arch-unknown-linux-gnu/uvx"
  rm -f "$archive"
fi
for tool in ty ruff ripgrep jupyter debugpy copilot-language-server copilot; do
  avf-native-daemon tools install "$tool" || echo "warning: could not install $tool" >&2
done
avf-native-daemon tools list
echo
echo "If Android asks to open port 19870 (avf-native), accept it."
avf-native-daemon pair
