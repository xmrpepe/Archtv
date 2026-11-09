# Step: install the desktop, browser, GPU stack, theming assets and shell tools.
disable_hooks
groups=(
  "Base tools|base-devel sudo git nano wget curl unzip less man-db which openssh pacman-contrib"
  "X11 tools|xorg-xrandr xorg-xauth xorg-xdpyinfo xorg-xprop xorg-xsetroot xorg-xrdb xorg-server-xvfb xkeyboard-config tigervnc xclip xdotool"
  "D-Bus and audio|dbus pulseaudio pulseaudio-alsa pavucontrol alsa-utils"
  "MATE desktop|mate mate-extra gvfs file-roller"
  "Firefox|firefox"
  "Mesa, Vulkan and Zink|mesa mesa-utils vulkan-icd-loader vulkan-tools vulkan-swrast vulkan-virtio"
  "Omarchy-style apps|alacritty rofi dunst picom sxhkd maim slop imagemagick jq libnotify xdg-user-dirs xdg-utils"
  "Themes, icons and fonts|papirus-icon-theme adw-gtk-theme adwaita-cursors ttf-jetbrains-mono-nerd inter-font noto-fonts noto-fonts-emoji"
  "Shell tools|zsh starship eza bat fzf zoxide btop lazygit neovim fastfetch ripgrep fd tree-sitter-cli npm"
)
n=${#groups[@]}; i=0
for g in "${groups[@]}"; do
  title=${g%%|*}; pkgs=${g#*|}
  progress $((i * 100 / n)) "Installing: $title"
  # shellcheck disable=SC2086
  pac -S $pkgs
  i=$((i + 1))
done
progress 100 "Packages installed"
