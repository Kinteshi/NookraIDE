# QuireCode shell integration for bash: OSC 133 marks (prompt, command, output, exit status)
# and OSC 633;E with the command line, as VS Code's terminal reads them. Started with
# --init-file, so ~/.bashrc is read here, as an interactive bash would.
if [ -f ~/.bashrc ]; then . ~/.bashrc; fi
if [ -z "${__avf_integrated-}" ]; then
    __avf_integrated=1
    __avf_first=1
    __avf_prompt() {
        local status=$?
        # A finish mark for the command before this prompt (none before the first one).
        if [ -z "$__avf_first" ]; then printf '\e]133;D;%s\a' "$status"; fi
        __avf_first=
        printf '\e]133;A\a'
        return $status
    }
    __avf_command() {
        local line
        line=$(HISTTIMEFORMAT= builtin fc -ln -0 2>/dev/null)
        line=${line#"${line%%[![:space:]]*}"}
        printf '\e]133;C\a\e]633;E;%s\a' "${line//[[:cntrl:]]/ }"
    }
    PROMPT_COMMAND="__avf_prompt${PROMPT_COMMAND:+; $PROMPT_COMMAND}"
    PS0='$(__avf_command)'"${PS0-}"
    PS1="${PS1-}"'\[\e]133;B\a\]'
fi
