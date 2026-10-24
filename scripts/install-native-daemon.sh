#!/usr/bin/env bash
# Installs the AVF Studio native daemon as a systemd user service.
#
#   AVF guest (Linux Terminal on the tablet):
#     bash install-native-daemon.sh [/path/to/avf-daemon] [workspace]
#   Linux computer or WSL distro on the network (see docs/HOSTING-THE-DAEMON.md):
#     bash install-native-daemon.sh --remote [--port 19870] [--address HOST] [/path/to/avf-daemon] [workspace]
#
# Without a daemon path it uses the avf-native-daemon (or avf-daemon) next to this script, as
# AVF Studio's "Set up guest" places it. It ends by printing a one-time pairing code (guest) or a
# connection string (remote) for the app. The session token lives in ~/.config/avf-native/env
# (mode 0600). Safe to rerun, including on a guest reset from Terminal → Settings → Recovery.
set -euo pipefail

# uv runs ty, ruff and the Jupyter bridge. Pinned and checksum-verified, like the tool catalog.
UV_VERSION=0.13.0
declare -A UV_SHA256=(
  [aarch64]=3ccfb6af6e242433eb552f7d9676abd5412c6595c497e990d9c8cb7b5bd4d2c3
  [x86_64]=1468ebd5a5541121837c5a2817b9972ba6090fa6caa3d142620850a47fb75154
)

usage() {
  echo "usage: $0 [--remote [--port N] [--address HOST]] [/path/to/avf-daemon] [workspace]" >&2
  exit 2
}

remote=false
port=19870
address=""
positional=()
while (($#)); do
  case "$1" in
    --remote) remote=true ;;
    --port) port=${2:?}; shift ;;
    --address) address=${2:?}; shift ;;
    -h | --help) usage ;;
    *) positional+=("$1") ;;
  esac
  shift
done

here="$(dirname "$(readlink -f "$0")")"
default_daemon="$here/avf-native-daemon"
[[ -f "$default_daemon" ]] || default_daemon="$here/avf-daemon"
daemon_source=${positional[0]:-$default_daemon}
[[ -f "$daemon_source" ]] || usage
workspace=${positional[1]:-$HOME/Projects}
config="$HOME/.config/avf-native"
unit="$HOME/.config/systemd/user/avf-native.service"

if $remote; then
  # Beyond loopback the daemon requires TLS; it creates a self-signed certificate on first start
  # and the connection string carries its fingerprint for the app to pin.
  bind="0.0.0.0:$port"
  tls_line="Environment=AVF_TLS=1"
else
  bind="127.0.0.1:19870"
  tls_line=""
fi

install -D -m 755 "$daemon_source" "$HOME/.local/bin/avf-native-daemon.new"
mv "$HOME/.local/bin/avf-native-daemon.new" "$HOME/.local/bin/avf-native-daemon"
mkdir -p "$config" "$workspace" "$(dirname "$unit")"
chmod 700 "$config"
if [[ ! -f "$config/env" ]]; then
  (umask 077 && printf 'AVF_TOKEN=%s\n' "$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')" >"$config/env")
fi

cat >"$unit" <<UNIT
[Unit]
Description=AVF Studio native IDE daemon

[Service]
Type=simple
ExecStart=%h/.local/bin/avf-native-daemon
WorkingDirectory=$workspace
EnvironmentFile=%h/.config/avf-native/env
Environment=AVF_WORKSPACE_ROOTS=$workspace
Environment=AVF_BIND_ADDR=$bind
$tls_line
Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin
Restart=on-failure
RestartSec=2
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
if $remote; then
  # Keep the service running without an open login session (allowed for your own user on most distros).
  loginctl enable-linger "$USER" 2>/dev/null || echo "note: run 'sudo loginctl enable-linger $USER' to keep the daemon running after logout" >&2
fi

# The app's terminal runs under the unit above, so it must not set NoNewPrivileges: sudo refuses to
# run under it.

# An earlier installer limited free-page reporting (page_reporting_order=10) after the guest's
# memory got corrupted under host memory pressure. With less memory going back to Android the
# tablet rebooted twice within half an hour, so restore the kernel default. Don't unbind
# virtio_balloon either: crosvm resets the VM and Android stops it. The daemon reports disk errors
# to the app instead, so a damaged guest is noticed early.
if ! $remote && [ -e /etc/systemd/system/avf-page-reporting.service ] && sudo -n true 2>/dev/null; then
  sudo -n systemctl disable avf-page-reporting.service 2>/dev/null || true
  sudo -n rm -f /etc/systemd/system/avf-page-reporting.service
  sudo -n systemctl daemon-reload
  echo 9 | sudo -n tee /sys/module/page_reporting/parameters/page_reporting_order >/dev/null 2>&1 || true
fi

# Earlier installers added root units that dropped the guest's caches and compacted memory. On AVF
# that corrupted file pages (programs crashed, ext4 reported I/O errors), so remove them.
if ! $remote && [ -e /etc/systemd/system/avf-trim-memory.service ] && sudo -n true 2>/dev/null; then
  sudo -n systemctl disable --now avf-trim-memory.timer avf-trim-memory.path 2>/dev/null || true
  sudo -n rm -f /etc/systemd/system/avf-trim-memory.service /etc/systemd/system/avf-trim-memory.timer \
    /etc/systemd/system/avf-trim-memory.path /etc/tmpfiles.d/avf-native.conf
  sudo -n systemctl daemon-reload
fi

export PATH="$HOME/.local/bin:$PATH"
# Source Control needs git; install it from the distribution when sudo works without a password.
if ! command -v git >/dev/null; then
  sudo -n apt-get install -y git >/dev/null 2>&1 || echo "warning: git is missing; install it with your package manager" >&2
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
if $remote; then
  if [[ -z "$address" ]]; then
    # The address other machines reach this one on (the source address of the default route).
    address=$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1)
    address=${address:-$(hostname -I 2>/dev/null | awk '{print $1}')}
  fi
  echo "The daemon listens on port $port over TLS. Allow it through the firewall if one is active"
  echo "(for example: sudo ufw allow $port/tcp). Run 'avf-native-daemon pair --remote $address:$port'"
  echo "again whenever you need a new code."
  avf-native-daemon pair --remote "${address:?could not detect the address of this machine, pass --address}:$port"
else
  echo "If Android asks to open port 19870 (avf-native), accept it."
  avf-native-daemon pair
fi
