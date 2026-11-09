#!/usr/bin/env bash
# Installs the QuireCode native daemon as a systemd user service.
#
#   AVF guest (Linux Terminal on the tablet):
#     bash install-native-daemon.sh [/path/to/quirecode-daemon] [workspace]
#   Linux computer or WSL distro on the network (see docs/HOSTING-THE-DAEMON.md):
#     bash install-native-daemon.sh --remote [--port 19870] [--address HOST] [/path/to/quirecode-daemon] [workspace]
#
# Without a daemon path it uses the quirecode-daemon next to this script, as QuireCode's "Set up
# guest" places it. It ends by printing a one-time pairing code (guest) or a connection string
# (remote) for the app. The session token lives in ~/.config/quirecode/env (mode 0600). Safe to
# rerun, including on a guest reset from Terminal → Settings → Recovery. It replaces a daemon
# installed before 0.1.0 (avf-native-daemon and its avf-native service), keeping its pairing.
set -euo pipefail

# uv runs ty, ruff and the Jupyter bridge. Pinned and checksum-verified, like the tool catalog.
UV_VERSION=0.13.0
declare -A UV_SHA256=(
  [aarch64]=3ccfb6af6e242433eb552f7d9676abd5412c6595c497e990d9c8cb7b5bd4d2c3
  [x86_64]=1468ebd5a5541121837c5a2817b9972ba6090fa6caa3d142620850a47fb75154
)

usage() {
  echo "usage: $0 [--remote [--port N] [--address HOST]] [/path/to/quirecode-daemon] [workspace]" >&2
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
default_daemon="$here/quirecode-daemon"
daemon_source=${positional[0]:-$default_daemon}
[[ -f "$daemon_source" ]] || usage
workspace=${positional[1]:-$HOME/Projects}
config="$HOME/.config/quirecode"
unit="$HOME/.config/systemd/user/quirecode.service"
# Before 0.1.0 the daemon was avf-native-daemon, run by avf-native.service, with its token,
# pairing code and TLS certificate in ~/.config/avf-native. Moving that folder keeps the pairing.
former_config="$HOME/.config/avf-native"
former_unit="$HOME/.config/systemd/user/avf-native.service"

# The app opens ~/Projects first; on the tablet's own guest the rest of home can be opened too
# (File > Open Folder). Its terminal reaches all of it anyway. A LAN host serves only the workspace.
roots=$workspace
if ! $remote && [[ "$workspace" != "$HOME" ]]; then
  roots="$workspace:$HOME"
fi

if $remote; then
  # Beyond loopback the daemon requires TLS; it creates a self-signed certificate on first start
  # and the connection string carries its fingerprint for the app to pin.
  bind="0.0.0.0:$port"
  tls_line="Environment=AVF_TLS=1"
else
  bind="127.0.0.1:19870"
  tls_line=""
fi

install -D -m 755 "$daemon_source" "$HOME/.local/bin/quirecode-daemon.new"
mv "$HOME/.local/bin/quirecode-daemon.new" "$HOME/.local/bin/quirecode-daemon"
if [[ ! -e "$config" && -d "$former_config" ]]; then
  mv "$former_config" "$config"
fi
mkdir -p "$config" "$workspace" "$(dirname "$unit")"
chmod 700 "$config"
# /tmp in the AVF guest is a RAM-backed tmpfs of half the guest's memory (2 GB on a 4 GB guest);
# pip and conda unpack large packages there and fail with "No space left on device". When /tmp
# is a tmpfs, the daemon (which also checks this itself) and, in the guest, login shells such as
# Linux Terminal's use a temp dir on disk, which systemd-tmpfiles clears of week-old files.
tmp_line=""
if [[ "$(findmnt -no FSTYPE /tmp 2>/dev/null)" == tmpfs ]]; then
  mkdir -p "$HOME/.cache/tmp" "$HOME/.config/user-tmpfiles.d"
  chmod 700 "$HOME/.cache/tmp"
  printf 'd %%h/.cache/tmp 0700 - - 7d\n' >"$HOME/.config/user-tmpfiles.d/avf-tmp.conf"
  tmp_line="Environment=TMPDIR=%h/.cache/tmp"
  profile_script='# QuireCode: /tmp is a tmpfs in memory; large installs (pip, conda) need a temp dir on disk.
if [ -z "${TMPDIR:-}" ] && [ -n "${HOME:-}" ] && [ "$HOME" != / ] && mkdir -p -m 700 "$HOME/.cache/tmp" 2>/dev/null; then
  export TMPDIR="$HOME/.cache/tmp"
fi'
  # Only in the AVF guest: on a remote host the daemon's own check is enough, and system files
  # there are the owner's business.
  if ! $remote && ! printf '%s\n' "$profile_script" | sudo -n tee /etc/profile.d/avf-tmpdir.sh >/dev/null 2>&1; then
    echo "note: /tmp is in memory; for shells outside QuireCode, add 'export TMPDIR=\$HOME/.cache/tmp' to ~/.profile" >&2
  fi
fi
if [[ ! -f "$config/env" ]]; then
  (umask 077 && printf 'AVF_TOKEN=%s\n' "$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')" >"$config/env")
fi

cat >"$unit" <<UNIT
[Unit]
Description=QuireCode daemon

[Service]
Type=simple
ExecStart=%h/.local/bin/quirecode-daemon
WorkingDirectory=$workspace
EnvironmentFile=%h/.config/quirecode/env
Environment=AVF_WORKSPACE_ROOTS=$roots
Environment=AVF_BIND_ADDR=$bind
$tls_line
Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin
$tmp_line
Restart=on-failure
RestartSec=2
UMask=0077

[Install]
WantedBy=default.target
UNIT

# The daemon from before 0.1.0 holds the same port: stop it and remove it for good.
if [[ -f "$former_unit" ]]; then
  systemctl --user disable --now avf-native.service 2>/dev/null || true
  rm -f "$former_unit" "$HOME/.local/bin/avf-native-daemon"
  rm -rf "$HOME/.cache/avf-native/shell-integration"
  rmdir "$HOME/.cache/avf-native" 2>/dev/null || true
fi
systemctl --user daemon-reload
systemctl --user enable quirecode.service
# stop/sleep/start, never restart: the Terminal app's port forwarder can lose the port otherwise.
systemctl --user stop quirecode.service || true
sleep 3
systemctl --user start quirecode.service
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

# Linux Terminal's port forwarder exits when a process or thread name has a space (Python 3.14
# thread names), and the app can't reach the daemon until the guest restarts. The daemon carries a
# wrapper for the tool the forwarder reads; it takes effect at the next guest start (Settings ->
# Guest tools -> Port forwarding fix shows it and can undo it).
if ! $remote && [ -x /usr/bin/forwarder_guest_launcher ] && sudo -n true 2>/dev/null; then
  sudo -n "$HOME/.local/bin/quirecode-daemon" guest fix-port-forwarding ||
    echo "warning: the port forwarding fix wasn't installed; apply it from the app's Settings" >&2
fi

# The AVF Debian image ships /etc/resolv.conf from its build machine (a cloud resolver that doesn't
# answer here). glibc goes through systemd-resolved and works, but programs that read the file
# directly (Go tools such as the GitHub CLI) time out on every lookup. Point the file at
# resolved's stub, as Debian does with systemd-resolved; the old file stays as a backup.
if ! $remote && [ -f /etc/resolv.conf ] && [ ! -L /etc/resolv.conf ] && [ -f /run/systemd/resolve/stub-resolv.conf ] &&
  systemctl is-active --quiet systemd-resolved && sudo -n true 2>/dev/null; then
  [ -e /etc/resolv.conf.avf-backup ] || sudo -n cp -p /etc/resolv.conf /etc/resolv.conf.avf-backup
  sudo -n ln -sf /run/systemd/resolve/stub-resolv.conf /etc/resolv.conf
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
for tool in ty ruff ripgrep jupyter debugpy typescript copilot-language-server copilot; do
  quirecode-daemon tools install "$tool" || echo "warning: could not install $tool" >&2
done
quirecode-daemon tools list
echo
if $remote; then
  if [[ -z "$address" ]]; then
    # The address other machines reach this one on (the source address of the default route).
    address=$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1)
    address=${address:-$(hostname -I 2>/dev/null | awk '{print $1}')}
  fi
  echo "The daemon listens on port $port over TLS. Allow it through the firewall if one is active"
  echo "(for example: sudo ufw allow $port/tcp). Run 'quirecode-daemon pair --remote $address:$port'"
  echo "again whenever you need a new code."
  quirecode-daemon pair --remote "${address:?could not detect the address of this machine, pass --address}:$port"
else
  echo "If Android asks to open port 19870, accept it."
  quirecode-daemon pair
fi
