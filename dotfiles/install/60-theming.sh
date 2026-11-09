# Step: Omarchy theming – wallpapers from the official repo, Papirus folder
# recolouring, per-user configs, and the default theme.
progress 5 "Fetching Omarchy wallpapers (github.com/basecamp/omarchy @ ${OMARCHY_COMMIT:0:7})"
src=/var/cache/archdock/omarchy
if [ ! -f "$src/.done-$OMARCHY_COMMIT" ]; then
  rm -rf "$src"; mkdir -p "$src"; cd "$src"
  git init -q && git remote add origin https://github.com/basecamp/omarchy
  git sparse-checkout init --no-cone >/dev/null
  : >.git/info/sparse-checkout
  echo /LICENSE >>.git/info/sparse-checkout
  for t in /opt/archdock/themes/*/; do echo "/themes/$(basename "$t")/backgrounds/" >>.git/info/sparse-checkout; done
  if git fetch -q --depth 1 --filter=blob:none origin "$OMARCHY_COMMIT" && git checkout -q FETCH_HEAD; then
    touch ".done-$OMARCHY_COMMIT"
  else
    warn "could not download Omarchy wallpapers – themes will use a solid background"
  fi
  cd /
fi
for t in /opt/archdock/themes/*/; do
  id=$(basename "$t")
  dest=/home/user/.local/share/archdock/wallpapers/$id
  mkdir -p "$dest"
  [ -d "$src/themes/$id/backgrounds" ] && cp -n "$src/themes/$id/backgrounds/"* "$dest/" 2>/dev/null
  rm -f "$dest/omarchy.webp"
done
[ -f "$src/LICENSE" ] && install -Dm644 "$src/LICENSE" /usr/share/licenses/archdock/omarchy-LICENSE
# MATE's background renderer handles JPG/PNG best; convert WebP once.
find /home/user/.local/share/archdock/wallpapers -name '*.webp' | while read -r w; do
  magick "$w" "${w%.webp}.jpg" 2>/dev/null && rm -f "$w"
done

progress 35 "Installing papirus-folders"
if [ ! -x /usr/local/bin/papirus-folders ]; then
  curl -fsSL "$PAPIRUS_FOLDERS_URL" -o /usr/local/bin/papirus-folders && chmod 755 /usr/local/bin/papirus-folders \
    || warn "papirus-folders download failed (folder recolouring disabled)"
fi

progress 50 "Installing per-user configuration"
H=/home/user
c=/opt/archdock/config
mkdir -p $H/.config/{alacritty,picom,sxhkd,rofi,zsh,btop,autostart,dunst,archdock}
cp $c/alacritty/alacritty.toml $H/.config/alacritty/alacritty.toml
cp $c/sxhkd/sxhkdrc $H/.config/sxhkd/sxhkdrc
cp $c/rofi/config.rasi $H/.config/rofi/config.rasi
cp $c/zsh/zshrc $H/.zshrc
cp $c/zsh/aliases.zsh $H/.config/zsh/aliases.zsh
cp $c/btop/btop.conf $H/.config/btop/btop.conf
cp $c/autostart/*.desktop $H/.config/autostart/
mkdir -p $H/.config/fontconfig && cp $c/fontconfig/fonts.conf $H/.config/fontconfig/fonts.conf
echo "${ARCHDOCK_THEME:-tokyo-night}" >$H/.config/archdock/current-theme
cat >$H/.config/mimeapps.list <<MIME
[Default Applications]
x-scheme-handler/http=firefox.desktop
x-scheme-handler/https=firefox.desktop
text/html=firefox.desktop
inode/directory=caja-folder-handler.desktop
text/plain=pluma.desktop
MIME
chown -R user:user $H

progress 70 "Rendering the ${ARCHDOCK_THEME:-tokyo-night} theme"
as_user /opt/archdock/bin/archdock-theme-set "${ARCHDOCK_THEME:-tokyo-night}"
papirus-folders -C "$(. /opt/archdock/themes/${ARCHDOCK_THEME:-tokyo-night}/theme.conf; echo $PAPIRUS_FOLDER)" --theme Papirus-Dark >/dev/null 2>&1 || true
fc-cache -f >/dev/null 2>&1
progress 100 "Theming done"
