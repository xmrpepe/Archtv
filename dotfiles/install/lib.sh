# Shared helpers for ArchDock's guest install steps (sourced, runs as fake root).
# Every step must be idempotent: the installer re-runs a step from the top if
# the app was killed half-way through it.
set -o pipefail
[ -f /etc/archdock.env ] && . /etc/archdock.env
export LANG=C.UTF-8 LC_ALL=C.UTF-8
OMARCHY_COMMIT=f97d3e7dc80e1f3ff39b142bbd330d537fe7c674
LAZYVIM_STARTER=https://github.com/LazyVim/starter
PAPIRUS_FOLDERS_URL=https://raw.githubusercontent.com/PapirusDevelopmentTeam/papirus-folders/master/papirus-folders

# "@@progress <0-100> <message>" lines drive the app's step progress bar.
progress() { echo "@@progress $1 ${2:-}"; }
log() { echo ":: $*"; }
warn() { echo "!! $*" >&2; }

# pacman with retries (mirrors on phone networks are flaky).
pac() {
  local i
  for i in 1 2 3 4; do
    pacman --noconfirm --needed --noprogressbar "$@" && return 0
    warn "pacman failed (attempt $i), retrying in $((i * 5)) s"
    sleep $((i * 5))
    pacman -Syy --noconfirm >/dev/null 2>&1 || true
  done
  return 1
}

as_user() { runuser -u user -- env HOME=/home/user USER=user LOGNAME=user "$@"; }

# Disable pacman hooks that need systemd/udev/a kernel; proot has none.
disable_hooks() {
  mkdir -p /etc/pacman.d/hooks
  local h
  for h in 30-systemd-udev-reload.hook 30-systemd-hwdb.hook 30-systemd-daemon-reload-system.hook \
           30-systemd-daemon-reload-user.hook 30-systemd-restart-marked.hook 30-systemd-update.hook \
           30-systemd-catalog.hook 35-systemd-boot.hook 60-mkinitcpio-remove.hook 90-mkinitcpio-install.hook \
           60-depmod.hook 90-update-appstream-cache.hook; do
    ln -sf /dev/null "/etc/pacman.d/hooks/$h"
  done
}
