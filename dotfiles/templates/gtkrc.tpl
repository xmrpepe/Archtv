# ArchDock {{ theme_name }} – GTK 2 (colour-only rc, no engine needed). Rendered by archdock-theme-set.
gtk-color-scheme = "bg_color:{{ background }}\nfg_color:{{ foreground }}\nbase_color:{{ dark_background }}\ntext_color:{{ foreground }}\nselected_bg_color:{{ accent }}\nselected_fg_color:{{ background }}\ntooltip_bg_color:{{ darker_background }}\ntooltip_fg_color:{{ bright_foreground }}"
gtk-font-name = "Inter 10"

style "archdock-default" {
    xthickness = 1
    ythickness = 1
    bg[NORMAL]        = @bg_color
    bg[PRELIGHT]      = "{{ selection }}"
    bg[SELECTED]      = @selected_bg_color
    bg[ACTIVE]        = "{{ lighter_background }}"
    bg[INSENSITIVE]   = @bg_color
    fg[NORMAL]        = @fg_color
    fg[PRELIGHT]      = "{{ bright_foreground }}"
    fg[SELECTED]      = @selected_fg_color
    fg[ACTIVE]        = @fg_color
    fg[INSENSITIVE]   = "{{ muted }}"
    base[NORMAL]      = @base_color
    base[SELECTED]    = "{{ selection }}"
    base[ACTIVE]      = "{{ selection }}"
    base[INSENSITIVE] = @bg_color
    text[NORMAL]      = @text_color
    text[SELECTED]    = "{{ bright_foreground }}"
    text[ACTIVE]      = "{{ bright_foreground }}"
    text[INSENSITIVE] = "{{ muted }}"
}
style "archdock-tooltip" { bg[NORMAL] = @tooltip_bg_color fg[NORMAL] = @tooltip_fg_color }
class "GtkWidget" style "archdock-default"
widget "gtk-tooltip*" style "archdock-tooltip"
