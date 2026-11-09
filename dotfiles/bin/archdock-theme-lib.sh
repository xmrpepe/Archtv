#!/bin/bash
# ArchDock theme library: palette loading and template rendering.
# Sourced by archdock-theme-set; also usable on its own for testing:
#   ARCHDOCK_ROOT=dotfiles bash -c '. dotfiles/bin/archdock-theme-lib.sh; theme_render tokyo-night out/'

ARCHDOCK_ROOT=${ARCHDOCK_ROOT:-/opt/archdock}
ARCHDOCK_USER_DIR=${ARCHDOCK_USER_DIR:-$HOME/.config/archdock}

declare -gA C=()

# Prints the directory of theme $1 (user themes override shipped ones).
theme_dir() {
  local id=$1 d
  for d in "$ARCHDOCK_USER_DIR/themes/$id" "$ARCHDOCK_ROOT/themes/$id"; do
    [[ -f $d/colors.toml ]] && { echo "$d"; return 0; }
  done
  return 1
}

theme_list() {
  local d
  { for d in "$ARCHDOCK_USER_DIR"/themes/*/ "$ARCHDOCK_ROOT"/themes/*/; do
      [[ -f $d/colors.toml ]] && basename "$d"
    done; } | sort -u
}

theme_title() { local s=${1//-/ }; local out="" w; for w in $s; do out+="${w^} "; done; echo "${out% }"; }

# Loads colors.toml of theme $1 into C[] and derives the keys templates use,
# following Omarchy's fallback rules (omarchy-theme-color).
theme_load() {
  local id=$1 dir key value line
  dir=$(theme_dir "$id") || { echo "unknown theme: $id" >&2; return 1; }
  C=()
  while IFS= read -r line || [[ -n $line ]]; do
    [[ $line =~ ^[[:space:]]*([A-Za-z0-9_]+)[[:space:]]*=[[:space:]]*\"([^\"]*)\" ]] || continue
    C[${BASH_REMATCH[1]}]=${BASH_REMATCH[2]}
  done <"$dir/colors.toml"

  : "${C[mode]:=dark}"
  : "${C[dark_background]:=${C[background]}}"
  : "${C[darker_background]:=${C[dark_background]}}"
  : "${C[lighter_background]:=${C[background]}}"
  : "${C[light_foreground]:=${C[foreground]}}"
  : "${C[bright_foreground]:=${C[foreground]}}"
  : "${C[dark_foreground]:=${C[foreground]}}"
  : "${C[muted]:=${C[dark_foreground]}}"
  : "${C[selection]:=${C[lighter_background]}}"
  : "${C[selection_background]:=${C[selection]}}"
  : "${C[selection_foreground]:=${C[bright_foreground]}}"
  : "${C[accent]:=${C[blue]}}"
  for key in red green yellow blue magenta cyan; do
    : "${C[bright_$key]:=${C[$key]}}"
  done
  : "${C[orange]:=${C[bright_yellow]}}"

  # Non-colour values available to templates.
  local meta="$dir/theme.conf" PAPIRUS_FOLDER=blue MODE=${C[mode]}
  [[ -f $meta ]] && source "$meta"
  C[theme_id]=$id
  C[theme_name]=$(theme_title "$id")
  C[theme_dir]=$dir
  C[papirus_folder]=$PAPIRUS_FOLDER
  if [[ ${C[mode]} == light ]]; then
    C[gtk_base]=adw-gtk3; C[icon_theme]=Papirus-Light; C[bat_theme]=GitHub
  else
    C[gtk_base]=adw-gtk3-dark; C[icon_theme]=Papirus-Dark; C[bat_theme]=ansi
  fi
}

# Renders template $1 to file $2 with the loaded palette. Supports
# {{ key }}, {{ key_strip }} (hex without '#') and {{ key_rgb }} ("r,g,b").
theme_render_file() {
  local tpl=$1 out=$2 table key v
  table=$(mktemp)
  for key in "${!C[@]}"; do
    v=${C[$key]}
    printf '{{ %s }}\037%s\n' "$key" "$v" >>"$table"
    if [[ $v =~ ^#([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})$ ]]; then
      printf '{{ %s_strip }}\037%s\n' "$key" "${v#\#}" >>"$table"
      printf '{{ %s_rgb }}\037%d,%d,%d\n' "$key" "0x${BASH_REMATCH[1]}" "0x${BASH_REMATCH[2]}" "0x${BASH_REMATCH[3]}" >>"$table"
    fi
  done
  mkdir -p "$(dirname "$out")"
  awk -v table="$table" '
    BEGIN { while ((getline row < table) > 0) { s = index(row, "\037"); val[substr(row, 1, s - 1)] = substr(row, s + 1) } }
    {
      line = $0; out = ""
      while ((o = index(line, "{{")) > 0) {
        c = index(substr(line, o), "}}")
        if (c == 0) break
        tok = substr(line, o, c + 1)
        if (tok in val) out = out substr(line, 1, o - 1) val[tok]
        else { out = out substr(line, 1, o - 1) tok; unknown[tok] = 1 }
        line = substr(line, o + c + 1)
      }
      print out line
    }
    END { for (t in unknown) printf("archdock-theme: unknown token %s in %s\n", t, FILENAME) > "/dev/stderr" }
  ' "$tpl" >"$out.tmp" && mv "$out.tmp" "$out"
  rm -f "$table"
}

# Renders every template (user templates override shipped ones) into $2.
theme_render() {
  local id=$1 outdir=$2 tpl name
  theme_load "$id" || return 1
  mkdir -p "$outdir"
  declare -A seen=()
  for tpl in "$ARCHDOCK_USER_DIR"/templates/*.tpl "$ARCHDOCK_ROOT"/templates/*.tpl; do
    [[ -f $tpl ]] || continue
    name=$(basename "$tpl" .tpl)
    [[ -n ${seen[$name]:-} ]] && continue
    seen[$name]=1
    theme_render_file "$tpl" "$outdir/$name"
  done
}
