#!/usr/bin/env bash
# MaterialCode's launcher for what runs on Node.js: the chat agents Claude Code (through its ACP
# adapter, which carries the Claude Code CLI) and Gemini CLI, and the language servers published
# on npm (Bash, YAML, JSON, HTML, CSS, Dockerfile, SQL).
#
#   avf-node-agent setup <name>     installs a pinned Node.js and the pinned npm package
#   avf-node-agent login <agent>    signs in, interactively (run it in a terminal)
#   avf-node-agent <name> [args]    runs it (the app starts agents with ACP and servers with LSP on stdio)
#
# Everything lives under ~/.local/share/materialcode; nothing is installed globally, and the
# system's own Node.js (if any) is left alone. <agent> is claude-code or gemini; a server is one
# of the names in package_of.
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
        bash-language-server) echo "bash-language-server@5.8.1" ;;
        yaml-language-server) echo "yaml-language-server@2.0.0" ;;
        json-language-server | html-language-server | css-language-server) echo "vscode-langservers-extracted@4.10.0" ;;
        docker-langserver) echo "dockerfile-language-server-nodejs@0.15.0" ;;
        sql-language-server) echo "sql-language-server@1.7.1" ;;
        *) fail "unknown name '$1' (an agent: claude-code or gemini; or a language server)" ;;
    esac
}

# npm `overrides` for a package whose own ranges now resolve to something it can't load:
# sql-language-server 1.7.1 imports vscode-languageserver-protocol/lib/common/protocol, which
# the protocol's later releases no longer export (vscode-languageserver's own copy is left alone).
overrides_of() {
    case "$1" in
        sql-language-server) echo '{"vscode-languageserver-protocol@^3.15.3": "3.16.0"}' ;;
    esac
}

# Where a package goes: agents under agents/, servers under servers/ (one folder per package).
dir_of() {
    case "$1" in
        claude-code | gemini) echo "$ROOT/agents/$1" ;;
        json-language-server | html-language-server | css-language-server) echo "$ROOT/servers/vscode-langservers" ;;
        *) echo "$ROOT/servers/$1" ;;
    esac
}

entry_of() {
    local modules
    modules="$(dir_of "$1")/node_modules"
    case "$1" in
        claude-code) echo "$modules/@agentclientprotocol/claude-agent-acp/dist/index.js" ;;
        gemini) echo "$modules/@google/gemini-cli/bundle/gemini.js" ;;
        bash-language-server) echo "$modules/bash-language-server/out/cli.js" ;;
        yaml-language-server) echo "$modules/yaml-language-server/bin/yaml-language-server" ;;
        json-language-server) echo "$modules/vscode-langservers-extracted/bin/vscode-json-language-server" ;;
        html-language-server) echo "$modules/vscode-langservers-extracted/bin/vscode-html-language-server" ;;
        css-language-server) echo "$modules/vscode-langservers-extracted/bin/vscode-css-language-server" ;;
        docker-langserver) echo "$modules/dockerfile-language-server-nodejs/bin/docker-langserver" ;;
        sql-language-server) echo "$modules/sql-language-server/npm_bin/cli.js" ;;
        *) fail "unknown name '$1' (an agent: claude-code or gemini; or a language server)" ;;
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
    local agent=$1 package directory overrides
    package=$(package_of "$agent")
    directory=$(dir_of "$agent")
    install_node
    echo "Installing $package…"
    mkdir -p "$directory"
    overrides=$(overrides_of "$agent")
    if [ -n "$overrides" ] && ! grep -qF '"overrides"' "$directory/package.json" 2>/dev/null; then
        # A fresh tree, so what's installed already is resolved again with the overrides.
        rm -rf "$directory/node_modules" "$directory/package-lock.json"
        printf '{\n  "overrides": %s\n}\n' "$overrides" > "$directory/package.json"
    fi
    PATH="$NODE_DIR/bin:$PATH" "$NODE_DIR/bin/npm" install --prefix "$directory" \
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
        # A server's own name runs it too (the app checks for it, and it works in a terminal).
        *)
            write_wrapper "$agent" "exec \"\$HOME/.local/bin/avf-node-agent\" $agent \"\$@\""
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
    claude-code | gemini | bash-language-server | yaml-language-server | json-language-server | \
        html-language-server | css-language-server | docker-langserver | sql-language-server) run "$@" ;;
    *) fail "usage: avf-node-agent setup|login <agent> | <agent> [args]" ;;
esac
