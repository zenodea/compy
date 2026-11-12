#!/bin/sh
# Compy: sets a shell up to mark its prompts and each command's output (OSC 133), which is what
# "jump to prompt" and "copy command output" go by. The Compy app copies a command that runs this.
# It writes one small file for your shell and adds one line to its startup file, unless the line
# is already there. Running it again only replaces the small file.
set -e
dir="$HOME/.config/compy"
mkdir -p "$dir"
shell=$(basename "${SHELL:-sh}")

case "$shell" in
zsh)
  cat > "$dir/prompt-marks.zsh" <<'EOF'
# Compy prompt marks: tells the terminal where each prompt and each command's output starts.
[[ -o interactive ]] || return 0
(( ${+_compy_marks} )) && return 0
typeset -g _compy_marks=1 _compy_ran=
_compy_precmd() {
  # The end of the last command's output.
  [[ -n $_compy_ran ]] && builtin print -n '\e]133;D\a'
  _compy_ran=
  # The start of the prompt and of what is typed. Themes may build the prompt again each time,
  # so the marks go back in whenever they are missing.
  [[ $PS1 == *$'\e]133;A'* ]] || PS1=$'%{\e]133;A;cl=line\a%}'$PS1$'%{\e]133;B\a%}'
}
_compy_preexec() {
  # The start of a command's output.
  builtin print -n '\e]133;C\a'
  _compy_ran=1
}
autoload -Uz add-zsh-hook
add-zsh-hook precmd _compy_precmd
add-zsh-hook preexec _compy_preexec
EOF
  rc="${ZDOTDIR:-$HOME}/.zshrc"
  line='[ -r ~/.config/compy/prompt-marks.zsh ] && . ~/.config/compy/prompt-marks.zsh'
  ;;
bash)
  cat > "$dir/prompt-marks.bash" <<'EOF'
# Compy prompt marks: tells the terminal where each prompt and each command's output starts.
[[ $- == *i* ]] || return 0
[[ -n $_compy_marks ]] && return 0
_compy_marks=1
_compy_prompt() {
  # The end of the last command's output.
  [[ -n $_compy_seen ]] && printf '\e]133;D\a'
  _compy_seen=1
  # The start of the prompt and of what is typed, put back whenever something rebuilt the prompt.
  [[ $PS1 == *'133;A'* ]] || PS1='\[\e]133;A;cl=line\a\]'$PS1'\[\e]133;B\a\]'
}
PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND
}_compy_prompt"
# The start of a command's output. Bash before 4.4 has no PS0, so there only prompts are marked.
if [[ ${BASH_VERSINFO[0]} -gt 4 || ( ${BASH_VERSINFO[0]} -eq 4 && ${BASH_VERSINFO[1]} -ge 4 ) ]]; then
  PS0='\[\e]133;C\a\]'$PS0
fi
EOF
  rc="$HOME/.bashrc"
  line='[ -r ~/.config/compy/prompt-marks.bash ] && . ~/.config/compy/prompt-marks.bash'
  ;;
fish)
  # Fish loads every file in conf.d by itself, so no startup file needs a line.
  mkdir -p "$HOME/.config/fish/conf.d"
  cat > "$HOME/.config/fish/conf.d/compy-prompt-marks.fish" <<'EOF'
# Compy prompt marks: tells the terminal where each prompt and each command's output starts.
if status is-interactive; and not set -q _compy_marks
    set -g _compy_marks 1
    function _compy_prompt --on-event fish_prompt
        set -q _compy_ran; and printf '\e]133;D\a'
        set -e _compy_ran
        printf '\e]133;A\a'
    end
    function _compy_preexec --on-event fish_preexec
        printf '\e]133;C\a'
        set -g _compy_ran 1
    end
end
EOF
  echo "Prompt marks are set up for fish. Open a new shell to use them."
  exit 0
  ;;
*)
  echo "Compy has prompt marks for zsh, bash and fish. This shell is $shell, so nothing was changed."
  exit 1
  ;;
esac

# The blank line first keeps the new line off a last line that has no newline of its own.
grep -q 'compy/prompt-marks' "$rc" 2>/dev/null || printf '\n%s\n' "$line" >> "$rc"
echo "Prompt marks are set up for $shell. Open a new shell to use them."
