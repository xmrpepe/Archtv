// ArchDock fastfetch layout (Omarchy-style), coloured from the theme. Rendered by archdock-theme-set.
{
  "$schema": "https://github.com/fastfetch-cli/fastfetch/raw/dev/doc/json_schema.json",
  "logo": { "type": "builtin", "source": "arch", "color": { "1": "{{ accent }}", "2": "{{ magenta }}" }, "padding": { "top": 1, "right": 4 } },
  "display": { "separator": "  ", "color": { "keys": "{{ accent }}", "title": "{{ magenta }}" } },
  "modules": [
    { "type": "custom", "format": "┌──────────────────── ArchDock ────────────────────┐" },
    { "type": "os", "key": " OS" },
    { "type": "host", "key": "󰌢 Host", "format": "{?1}{1}{?}" },
    { "type": "kernel", "key": " Kernel" },
    { "type": "cpu", "key": " CPU" },
    { "type": "gpu", "key": "󰍛 GPU" },
    { "type": "memory", "key": " Memory" },
    { "type": "disk", "key": "󰋊 Disk", "folders": "/" },
    { "type": "custom", "format": "├──────────────────── Desktop ─────────────────────┤" },
    { "type": "de", "key": " DE" },
    { "type": "wm", "key": " WM" },
    { "type": "theme", "key": "󰉼 Theme" },
    { "type": "icons", "key": " Icons" },
    { "type": "font", "key": " Font" },
    { "type": "terminal", "key": " Terminal" },
    { "type": "shell", "key": " Shell" },
    { "type": "custom", "format": "└──────────────────────────────────────────────────┘" },
    { "type": "colors", "symbol": "circle" }
  ]
}
