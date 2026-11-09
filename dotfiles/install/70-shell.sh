# Step: zsh + starship (configured in 60), Neovim with LazyVim.
progress 10 "Installing LazyVim starter"
if [ ! -f /home/user/.config/nvim/lazy-lock.json ] && [ ! -f /home/user/.config/nvim/init.lua ]; then
  theme_spec=/home/user/.config/nvim/lua/plugins/theme.lua
  saved=""; [ -f "$theme_spec" ] && saved=$(cat "$theme_spec")
  rm -rf /home/user/.config/nvim
  as_user git clone -q --depth 1 "$LAZYVIM_STARTER" /home/user/.config/nvim && rm -rf /home/user/.config/nvim/.git
  if [ -n "$saved" ]; then mkdir -p "$(dirname "$theme_spec")"; printf '%s\n' "$saved" >"$theme_spec"; fi
  cat >/home/user/.config/nvim/lua/plugins/archdock.lua <<'LUA'
-- ArchDock: Omarchy-like defaults on top of LazyVim.
return {
  { "folke/snacks.nvim", opts = { scroll = { enabled = false } } },
}
LUA
  chown -R user:user /home/user/.config/nvim
fi
progress 40 "Pre-installing Neovim plugins (best effort)"
as_user timeout 600 nvim --headless "+Lazy! sync" +qa >/dev/null 2>&1 || warn "LazyVim will finish installing plugins on first start"

progress 85 "Shell defaults"
as_user xdg-user-dirs-update >/dev/null 2>&1 || true
progress 100 "Shell ready"
