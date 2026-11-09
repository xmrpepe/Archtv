# ArchDock {{ theme_name }} – colours for fzf, eza and bat. Rendered by archdock-theme-set.
export FZF_DEFAULT_OPTS="--height 40% --layout=reverse --border=sharp --info=inline \
  --color=bg+:{{ selection }},bg:{{ background }},spinner:{{ cyan }},hl:{{ accent }} \
  --color=fg:{{ foreground }},header:{{ accent }},info:{{ magenta }},pointer:{{ accent }} \
  --color=marker:{{ green }},fg+:{{ bright_foreground }},prompt:{{ accent }},hl+:{{ accent }},border:{{ muted }}"
export BAT_THEME="{{ bat_theme }}"
export EZA_COLORS="di=1;34:ln=36:ex=32:da=2"
