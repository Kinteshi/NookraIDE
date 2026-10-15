#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s /path/to/avf-daemon /absolute/workspace-root\n' "$0" >&2
}

if [[ $# != 2 ]]; then
  usage
  exit 2
fi

for tool in install realpath openssl systemctl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'Required command not found: %s\n' "$tool" >&2
    exit 1
  fi
done

daemon_source=$(realpath -e -- "$1")
workspace_root=$(realpath -e -- "$2")
if [[ ! -f "$daemon_source" || ! -x "$daemon_source" ]]; then
  printf 'Daemon must be an executable regular file: %s\n' "$daemon_source" >&2
  exit 1
fi
if [[ ! -d "$workspace_root" || "$workspace_root" != /* ]]; then
  printf 'Workspace root must be an existing absolute directory.\n' >&2
  exit 1
fi
if [[ "$workspace_root" == *:* || "$workspace_root" == *$'\n'* ]]; then
  printf 'Workspace root cannot contain a colon or newline.\n' >&2
  exit 1
fi
if [[ -z "${HOME:-}" || "$HOME" != /* ]]; then
  printf 'HOME must be set to an absolute directory.\n' >&2
  exit 1
fi

config_dir="$HOME/.config/avf-studio"
bin_dir="$HOME/.local/bin"
unit_dir="$HOME/.config/systemd/user"
token_path="$config_dir/token"
daemon_path="$bin_dir/avf-daemon"
unit_path="$unit_dir/avf-studio.service"
mkdir -p -- "$config_dir" "$bin_dir" "$unit_dir"
for directory in "$config_dir" "$bin_dir" "$unit_dir"; do
  if [[ -L "$directory" ]]; then
    printf 'Refusing to use a symlink for configuration directory: %s\n' "$directory" >&2
    exit 1
  fi
done
chmod 700 "$config_dir"

if [[ ! -e "$token_path" ]]; then
  token_tmp=$(mktemp "$config_dir/.token.XXXXXX")
  trap 'rm -f -- "$token_tmp"' EXIT
  openssl rand -hex 32 >"$token_tmp"
  chmod 600 "$token_tmp"
  mv -- "$token_tmp" "$token_path"
  trap - EXIT
fi
if [[ ! -f "$token_path" || -L "$token_path" ]]; then
  printf 'Token path must be a regular, non-symlink file: %s\n' "$token_path" >&2
  exit 1
fi
chmod 600 "$token_path"
token=$(<"$token_path")
if [[ ! "$token" =~ ^[[:xdigit:]]{64}$ ]]; then
  printf 'Existing token must contain exactly 64 hexadecimal characters: %s\n' "$token_path" >&2
  exit 1
fi
unset token

daemon_tmp=$(mktemp "$bin_dir/.avf-daemon.XXXXXX")
trap 'rm -f -- "$daemon_tmp"' EXIT
install -m 755 -- "$daemon_source" "$daemon_tmp"
mv -- "$daemon_tmp" "$daemon_path"
trap - EXIT

systemd_quote() {
  local escaped=$1
  escaped=${escaped//\\/\\\\}
  escaped=${escaped//\"/\\\"}
  escaped=${escaped//%/%%}
  printf '"%s"' "$escaped"
}

unit_tmp=$(mktemp "$unit_dir/.avf-studio.service.XXXXXX")
trap 'rm -f -- "$unit_tmp"' EXIT
{
  printf '[Unit]\nDescription=AVF Studio guest daemon\nAfter=default.target\n\n'
  printf '[Service]\nType=simple\n'
  printf 'ExecStart=%s\n' "$(systemd_quote "$daemon_path")"
  printf 'WorkingDirectory=%s\n' "$(systemd_quote "$workspace_root")"
  printf 'Environment=AVF_WORKSPACE_ROOTS=%s\n' "$(systemd_quote "$workspace_root")"
  printf 'Environment=PATH=%%h/.local/bin:/usr/local/bin:/usr/bin:/bin\n'
  printf 'Restart=on-failure\nRestartSec=2\n'
  printf 'NoNewPrivileges=true\nPrivateTmp=true\nUMask=0077\n'
  printf '\n[Install]\nWantedBy=default.target\n'
} >"$unit_tmp"
chmod 600 "$unit_tmp"
mv -- "$unit_tmp" "$unit_path"
trap - EXIT

systemctl --user daemon-reload
systemctl --user enable --now avf-studio.service
printf 'AVF Studio daemon enabled for workspace %s\n' "$workspace_root"
printf 'Authentication token is stored at %s (mode 0600).\n' "$token_path"
