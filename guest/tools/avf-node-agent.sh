#!/usr/bin/env bash
# MaterialCode's launcher for the chat agents that run on Node.js: Claude Code (through its ACP
# adapter, which carries the Claude Code CLI) and Gemini CLI.
#
#   avf-node-agent setup <agent>    installs a pinned Node.js and the agent's pinned npm package
#   avf-node-agent login <agent>    signs in, interactively (run it in a terminal)
#   avf-node-agent <agent> [args]   runs the agent (the app starts it with ACP on stdio)
#
# Everything lives under ~/.local/share/materialcode; nothing is installed globally, and the
# system's own Node.js (if any) is left alone. <agent> is claude-code or gemini.
set -euo pipefail

NODE_VERSION=24.22.0
NODE_SHA256_ARM64=b7e7991d5407815adfa74a2a9cd4db683552c8ce32ff4a295c22e972849c3f5d
NODE_SHA256_X64=28ea0a438de9a13bd7891fd758eee6c7819ee8af5442dfd8d45bd65e56d4228c
ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/materialcode"
NODE_DIR="$ROOT/node-v$NODE_VERSION"
BIN="$HOME/.local/bin"
MARKER="# Written by MaterialCode (avf-node-agent)."

fail() {
    echo "avf-node-agent: $*" >&2
    exit 1
}

package_of() {
    case "$1" in
        claude-code) echo "@agentclientprotocol/claude-agent-acp@0.91.0" ;;
        gemini) echo "@google/gemini-cli@0.64.0" ;;
        *) fail "unknown agent '$1' (claude-code or gemini)" ;;
    esac
}

entry_of() {
    case "$1" in
        claude-code) echo "$ROOT/agents/claude-code/node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js" ;;
        gemini) echo "$ROOT/agents/gemini/node_modules/@google/gemini-cli/bundle/gemini.js" ;;
        *) fail "unknown agent '$1' (claude-code or gemini)" ;;
    esac
}

install_node() {
    [ -x "$NODE_DIR/bin/node" ] && return
    local arch sum
    case "$(uname -m)" in
        aarch64) arch=arm64 sum=$NODE_SHA256_ARM64 ;;
        x86_64) arch=x64 sum=$NODE_SHA256_X64 ;;
        *) fail "no Node.js build for $(uname -m)" ;;
    esac
    local name="node-v$NODE_VERSION-linux-$arch"
    local staging
    mkdir -p "$ROOT"
    staging=$(mktemp -d "$ROOT/.node.XXXXXX")
    echo "Downloading Node.js $NODE_VERSION…"
    curl -fL --proto '=https' --proto-redir '=https' -o "$staging/$name.tar.gz" "https://nodejs.org/dist/v$NODE_VERSION/$name.tar.gz"
    echo "$sum  $staging/$name.tar.gz" | sha256sum -c - >/dev/null || { rm -rf "$staging"; fail "Node.js checksum mismatch"; }
    tar -xzf "$staging/$name.tar.gz" -C "$staging"
    mv "$staging/$name" "$NODE_DIR"
    rm -rf "$staging"
}

# A small command in ~/.local/bin for the terminal, unless the user already has one of that name.
write_wrapper() {
    local name=$1 line=$2 target="$BIN/$1"
    if [ -e "$target" ] && ! grep -qF "$MARKER" "$target" 2>/dev/null; then
        echo "Keeping your own $target."
        return
    fi
    mkdir -p "$BIN"
    printf '#!/bin/sh\n%s\n%s\n' "$MARKER" "$line" > "$target.tmp"
    chmod 755 "$target.tmp"
    mv "$target.tmp" "$target"
}

setup() {
    local agent=$1 package
    package=$(package_of "$agent")
    install_node
    echo "Installing $package…"
    mkdir -p "$ROOT/agents/$agent"
    PATH="$NODE_DIR/bin:$PATH" "$NODE_DIR/bin/npm" install --prefix "$ROOT/agents/$agent" \
        --no-fund --no-audit --no-update-notifier --omit=dev --loglevel=error "$package"
    [ -f "$(entry_of "$agent")" ] || fail "$package did not install its entry point"
    # The wrappers expand $HOME and "$@" when they run, not now.
    # shellcheck disable=SC2016
    case "$agent" in
        claude-code)
            write_wrapper claude-agent-acp 'exec "$HOME/.local/bin/avf-node-agent" claude-code "$@"'
            write_wrapper claude 'exec "$HOME/.local/bin/avf-node-agent" claude-code --cli "$@"'
            ;;
        gemini)
            write_wrapper gemini 'exec "$HOME/.local/bin/avf-node-agent" gemini "$@"'
            ;;
    esac
    echo "$agent is ready."
}

run() {
    local agent=$1
    shift
    local entry
    entry=$(entry_of "$agent")
    [ -x "$NODE_DIR/bin/node" ] && [ -f "$entry" ] ||
        fail "$agent is not set up; run: avf-native-daemon tools install $agent"
    PATH="$NODE_DIR/bin:$PATH" exec "$NODE_DIR/bin/node" "$entry" "$@"
}

login() {
    local agent=$1
    case "$agent" in
        claude-code)
            # Prints a sign-in link; the code the page shows is pasted back here.
            run claude-code --cli auth login --claudeai
            ;;
        gemini)
            # Without a browser in the guest, Gemini prints a link and asks for the code. Choose
            # Google sign-in up front unless the user picked a method already, and take that
            # choice back if no sign-in came of it: with Google chosen but no credentials,
            # `gemini --acp` prompts for a code on the protocol's own stdout.
            local settings="$HOME/.gemini/settings.json" chose=""
            if [ ! -e "$settings" ]; then
                mkdir -p "$HOME/.gemini"
                echo '{"security":{"auth":{"selectedType":"oauth-personal"}}}' > "$settings"
                chose=1
            fi
            # shellcheck disable=SC2329
            undo_choice() {
                if [ -n "$chose" ] && [ ! -s "$HOME/.gemini/oauth_creds.json" ]; then rm -f "$settings"; fi
            }
            trap undo_choice EXIT
            trap 'exit 1' HUP INT TERM
            echo "Sign in with the link Gemini prints, paste the code, then type /quit."
            local entry
            entry=$(entry_of gemini)
            [ -x "$NODE_DIR/bin/node" ] && [ -f "$entry" ] || fail "gemini is not set up; run: avf-native-daemon tools install gemini"
            NO_BROWSER=true PATH="$NODE_DIR/bin:$PATH" "$NODE_DIR/bin/node" "$entry"
            [ -s "$HOME/.gemini/oauth_creds.json" ] || fail "no Google sign-in was saved"
            ;;
        *) fail "unknown agent '$agent' (claude-code or gemini)" ;;
    esac
}

case "${1:-}" in
    setup) [ $# -eq 2 ] || fail "usage: avf-node-agent setup <agent>"; setup "$2" ;;
    login) [ $# -eq 2 ] || fail "usage: avf-node-agent login <agent>"; login "$2" ;;
    claude-code | gemini) run "$@" ;;
    *) fail "usage: avf-node-agent setup|login <agent> | <agent> [args]" ;;
esac
