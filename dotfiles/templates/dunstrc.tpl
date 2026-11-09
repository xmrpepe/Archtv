# ArchDock: dunst styled like Omarchy's mako notifications. Rendered by archdock-theme-set.
[global]
    monitor = 0
    follow = mouse
    origin = top-right
    offset = (20, 20)
    width = (300, 420)
    height = (0, 220)
    notification_limit = 5
    gap_size = 10
    padding = 14
    horizontal_padding = 18
    text_icon_padding = 14
    frame_width = 2
    frame_color = "{{ accent }}"
    separator_color = frame
    corner_radius = 0
    font = JetBrainsMono Nerd Font 11
    markup = full
    format = "<b>%s</b>\n%b"
    alignment = left
    vertical_alignment = center
    icon_position = left
    min_icon_size = 32
    max_icon_size = 48
    icon_theme = "{{ icon_theme }}, Papirus-Dark, Adwaita"
    enable_recursive_icon_lookup = true
    show_age_threshold = 60
    ellipsize = end
    stack_duplicates = true
    hide_duplicate_count = false
    show_indicators = no
    sticky_history = yes
    history_length = 30
    browser = /usr/bin/xdg-open
    always_run_script = true
    mouse_left_click = do_action, close_current
    mouse_middle_click = close_all
    mouse_right_click = close_current

[urgency_low]
    background = "{{ background }}"
    foreground = "{{ dark_foreground }}"
    frame_color = "{{ muted }}"
    timeout = 4

[urgency_normal]
    background = "{{ background }}"
    foreground = "{{ foreground }}"
    frame_color = "{{ accent }}"
    timeout = 6

[urgency_critical]
    background = "{{ background }}"
    foreground = "{{ bright_foreground }}"
    frame_color = "{{ red }}"
    timeout = 0
