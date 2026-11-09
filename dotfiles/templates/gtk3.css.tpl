/* ArchDock {{ theme_name }} – GTK 3. adw-gtk3 recoloured with the Omarchy palette.
   Rendered by archdock-theme-set; edit templates/gtk3.css.tpl, not this file. */
@import url("file:///usr/share/themes/{{ gtk_base }}/gtk-3.0/gtk.css");

@define-color accent_color {{ accent }};
@define-color accent_bg_color {{ accent }};
@define-color accent_fg_color {{ background }};
@define-color destructive_color {{ red }};
@define-color destructive_bg_color {{ red }};
@define-color success_color {{ green }};
@define-color warning_color {{ yellow }};
@define-color error_color {{ red }};

@define-color window_bg_color {{ background }};
@define-color window_fg_color {{ foreground }};
@define-color view_bg_color {{ dark_background }};
@define-color view_fg_color {{ foreground }};
@define-color headerbar_bg_color {{ background }};
@define-color headerbar_fg_color {{ bright_foreground }};
@define-color headerbar_border_color {{ selection }};
@define-color headerbar_backdrop_color {{ background }};
@define-color headerbar_shade_color rgba({{ darker_background_rgb }}, 0.36);
@define-color card_bg_color {{ lighter_background }};
@define-color card_fg_color {{ foreground }};
@define-color card_shade_color rgba({{ darker_background_rgb }}, 0.36);
@define-color dialog_bg_color {{ lighter_background }};
@define-color dialog_fg_color {{ foreground }};
@define-color popover_bg_color {{ lighter_background }};
@define-color popover_fg_color {{ foreground }};
@define-color sidebar_bg_color {{ dark_background }};
@define-color sidebar_fg_color {{ foreground }};
@define-color sidebar_backdrop_color {{ dark_background }};
@define-color shade_color rgba({{ darker_background_rgb }}, 0.36);
@define-color scrollbar_outline_color {{ selection }};

/* Legacy names used by many GTK3 apps and the MATE panel. */
@define-color theme_bg_color {{ background }};
@define-color theme_fg_color {{ foreground }};
@define-color theme_base_color {{ dark_background }};
@define-color theme_text_color {{ foreground }};
@define-color theme_selected_bg_color {{ accent }};
@define-color theme_selected_fg_color {{ background }};
@define-color insensitive_bg_color {{ background }};
@define-color insensitive_fg_color {{ muted }};
@define-color borders {{ selection }};

selection, *:selected, *:selected:focus { background-color: {{ selection }}; color: {{ bright_foreground }}; }

/* Slim Omarchy-style top bar. */
.mate-panel-menu-bar, PanelToplevel, PanelWidget, .mate-panel-menu-bar menubar {
    background-color: {{ background }};
    color: {{ foreground }};
}
.mate-panel-menu-bar button { padding: 0 6px; border-radius: 0; color: {{ foreground }}; background: transparent; border: none; box-shadow: none; }
.mate-panel-menu-bar button:hover { background-color: {{ selection }}; }
.mate-panel-menu-bar button:checked { color: {{ accent }}; background-color: transparent; box-shadow: inset 0 -2px {{ accent }}; }
#clock-applet-button, #clock-applet-button label { color: {{ bright_foreground }}; font-weight: bold; }
na-tray-applet, .wnck-pager { background-color: {{ background }}; }
.wnck-pager:selected { background-color: {{ accent }}; }
.wnck-pager { color: {{ dark_foreground }}; }

/* Caja and Pluma */
.caja-desktop.view, .caja-canvas-item { color: {{ bright_foreground }}; text-shadow: 0 1px 2px rgba(0,0,0,0.6); }
.caja-side-pane, .caja-side-pane .view { background-color: {{ dark_background }}; }

/* Terminals and menus */
menu, .menu, .context-menu { background-color: {{ lighter_background }}; border: 1px solid {{ selection }}; }
menu menuitem:hover, .menu menuitem:hover { background-color: {{ selection }}; color: {{ accent }}; }
tooltip, tooltip.background { background-color: {{ darker_background }}; color: {{ bright_foreground }}; border: 1px solid {{ accent }}; }
