/* ArchDock: Rofi styled after Omarchy's Walker launcher. Rendered by archdock-theme-set. */
* {
    bg:        {{ background }};
    bg-alt:    {{ lighter_background }};
    fg:        {{ foreground }};
    fg-bright: {{ bright_foreground }};
    muted:     {{ dark_foreground }};
    accent:    {{ accent }};
    sel:       {{ selection }};
    urgent:    {{ red }};
    background-color: transparent;
    text-color: @fg;
    font: "JetBrainsMono Nerd Font 12";
}

window {
    location: center;
    anchor: center;
    width: 640px;
    background-color: @bg;
    border: 2px solid;
    border-color: @accent;
    border-radius: 0px;
    padding: 0px;
}

mainbox {
    spacing: 0px;
    children: [ inputbar, message, listview, mode-switcher ];
}

inputbar {
    padding: 14px 18px;
    spacing: 12px;
    background-color: @bg;
    border: 0px 0px 2px 0px;
    border-color: @sel;
    children: [ prompt, entry ];
}

prompt { text-color: @accent; }
entry {
    placeholder: "Search…";
    placeholder-color: @muted;
    text-color: @fg-bright;
    cursor: text;
}

message { padding: 8px 18px; }
textbox { text-color: @muted; }

listview {
    lines: 9;
    columns: 1;
    fixed-height: false;
    scrollbar: false;
    padding: 8px;
    spacing: 2px;
}

element {
    padding: 8px 12px;
    spacing: 12px;
    border-radius: 0px;
}
element normal.normal, element alternate.normal { background-color: transparent; text-color: @fg; }
element selected.normal { background-color: @sel; text-color: @accent; }
element normal.urgent, element alternate.urgent { text-color: @urgent; }
element selected.urgent { background-color: @urgent; text-color: @bg; }
element normal.active, element alternate.active { text-color: @accent; }
element-icon { size: 22px; background-color: transparent; }
element-text { background-color: transparent; text-color: inherit; vertical-align: 0.5; }

mode-switcher { padding: 6px 8px; spacing: 6px; background-color: @bg-alt; }
button { padding: 4px 10px; text-color: @muted; }
button selected { text-color: @accent; }
