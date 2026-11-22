# Nookra IDE shell integration for zsh: the user's .zshrc, then OSC 133 marks (prompt,
# command, output, exit status) and OSC 633;E with the command line, as VS Code's terminal reads them.
ZDOTDIR=${__AVF_USER_ZDOTDIR:-$HOME}
unset __AVF_USER_ZDOTDIR
if [ -f "$ZDOTDIR/.zshrc" ]; then . "$ZDOTDIR/.zshrc"; fi
__avf_first=1
__avf_precmd() {
    local status=$?
    if [ -z "$__avf_first" ]; then printf '\e]133;D;%s\a' "$status"; fi
    __avf_first=
    printf '\e]133;A\a'
}
__avf_preexec() {
    printf '\e]133;C\a\e]633;E;%s\a' "${1//[[:cntrl:]]/ }"
}
autoload -Uz add-zsh-hook
add-zsh-hook precmd __avf_precmd
add-zsh-hook preexec __avf_preexec
PS1="${PS1}%{$(printf '\e]133;B\a')%}"
