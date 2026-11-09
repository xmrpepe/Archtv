# Step: drop kernel/firmware/boot bits proot never uses, then pacman -Syu.
disable_hooks
progress 5 "Removing kernel and firmware (not used under proot)"
for p in linux-aarch64 linux-firmware linux-firmware-whence uboot-tools mkinitcpio; do
  pacman -Q "$p" >/dev/null 2>&1 && pacman -Rdd --noconfirm "$p" || true
done
grep -q '^IgnorePkg.*linux-aarch64' /etc/pacman.conf || \
  sed -i '/^\[options\]/a IgnorePkg = linux-aarch64 linux-firmware mkinitcpio' /etc/pacman.conf
rm -f /var/lib/pacman/db.lck
progress 15 "Refreshing package databases"
pacman -Syy --noconfirm
progress 25 "Upgrading the base system"
pac -Syu
progress 100 "System up to date"
