# Step: user, sudo, locales, timezone, machine-id, and proot-specific fixes.
progress 5 "Creating user 'user'"
# The ALARM image ships user "alarm" with uid 1000; ArchDock runs the desktop as uid 1000 "user".
if id alarm >/dev/null 2>&1; then userdel -r alarm 2>/dev/null || userdel alarm; fi
if ! id user >/dev/null 2>&1; then
  useradd -m -u 1000 -U -G wheel,audio,video,input -s /usr/bin/zsh user
fi
usermod -s /usr/bin/zsh user
passwd -l user >/dev/null 2>&1 || true
echo 'user ALL=(ALL:ALL) NOPASSWD: ALL' >/etc/sudoers.d/10-archdock
chmod 440 /etc/sudoers.d/10-archdock
mkdir -p /home/user/Android-Downloads
chown -R user:user /home/user

progress 25 "Generating locales"
loc=${ARCHDOCK_LOCALE:-en_US}
sed -i 's/^#\(en_US.UTF-8 UTF-8\)/\1/' /etc/locale.gen
if grep -q "^#\?${loc}.UTF-8 UTF-8" /etc/locale.gen; then
  sed -i "s/^#\(${loc}.UTF-8 UTF-8\)/\1/" /etc/locale.gen
  lang="${loc}.UTF-8"
else
  lang="en_US.UTF-8"
fi
locale-gen
echo "LANG=$lang" >/etc/locale.conf

progress 45 "Setting timezone from Android (${ARCHDOCK_TZ:-UTC})"
tz=${ARCHDOCK_TZ:-UTC}
[ -f "/usr/share/zoneinfo/$tz" ] || tz=UTC
ln -sf "/usr/share/zoneinfo/$tz" /etc/localtime
echo "$tz" >/etc/timezone

progress 60 "Disabling services that cannot run under proot"
# No systemd as PID 1, no udev, no logind: mask units so package scripts and
# desktop components don't try to start them.
mkdir -p /etc/systemd/system /etc/systemd/user
for u in systemd-udevd.service systemd-udevd-control.socket systemd-udevd-kernel.socket \
         systemd-logind.service systemd-networkd.service systemd-resolved.service \
         systemd-timesyncd.service systemd-journald.service getty@.service \
         serial-getty@.service sshd.service upower.service; do
  ln -sf /dev/null "/etc/systemd/system/$u"
done
ln -sf /dev/null /etc/systemd/user/pulseaudio.service
ln -sf /dev/null /etc/systemd/user/pulseaudio.socket
# D-Bus is started by start-desktop; it needs a machine id.
dbus-uuidgen --ensure=/etc/machine-id
mkdir -p /var/lib/dbus && dbus-uuidgen --ensure=/var/lib/dbus/machine-id
chmod 1777 /tmp

progress 80 "Installing start-desktop"
install -Dm755 /opt/archdock/bin/start-desktop /usr/local/bin/start-desktop
for f in /opt/archdock/bin/*; do
  [ "$(basename "$f")" = start-desktop ] && continue
  ln -sf "$f" "/usr/local/bin/$(basename "$f")"
done
install -Dm644 /opt/archdock/config/mate/archdock.layout /usr/share/mate-panel/layouts/archdock.layout
install -Dm644 /opt/archdock/config/fontconfig/fonts.conf /etc/fonts/local.conf
progress 100 "System configured"
