# Nookra IDE shell integration for zsh: the user's own .zshenv (ZDOTDIR points here).
if [ -f "${__AVF_USER_ZDOTDIR:-$HOME}/.zshenv" ]; then . "${__AVF_USER_ZDOTDIR:-$HOME}/.zshenv"; fi
