# Step: clean caches and record the installed version.
progress 20 "Cleaning package cache"
paccache -rk1 >/dev/null 2>&1 || pacman -Scc --noconfirm >/dev/null 2>&1 || true
rm -rf /var/cache/archdock/omarchy/.git
progress 70 "Recording version"
mkdir -p /var/lib/archdock
{ echo "installed=$(date -Iseconds)"; echo "archdock=$(cat /opt/archdock/VERSION 2>/dev/null)"; } >/var/lib/archdock/install-info
progress 100 "Arch Linux ARM is ready"
