# Omarchy-style aliases and helpers.
alias ls='eza -lh --group-directories-first --icons=auto'
alias lsa='ls -a'
alias lt='eza --tree --level=2 --long --icons --git'
alias lta='lt -a'
alias ff="fzf --preview 'bat --style=numbers --color=always {}'"
alias cat='bat --paging=never --style=plain'
alias ..='cd ..'
alias ...='cd ../..'
alias ....='cd ../../..'
alias g='git'
alias gcm='git commit -m'
alias gcam='git commit -a -m'
alias gcad='git commit -a --amend'
alias lg='lazygit'
alias top='btop'
alias update='sudo pacman -Syu'
alias decompress='tar -xzf'

# cd via zoxide, falling back to plain cd.
zd() {
  if [ $# -eq 0 ]; then builtin cd ~ && return
  elif [ -d "$1" ]; then builtin cd "$1"
  else z "$@" && printf "\U000F17A9 " && pwd || echo "Error: Directory not found"; fi
}
alias cd='zd'

n() { if [ "$#" -eq 0 ]; then nvim .; else nvim "$@"; fi; }
open() { xdg-open "$@" >/dev/null 2>&1 &; }
compress() { tar -czf "${1%/}.tar.gz" "${1%/}"; }
