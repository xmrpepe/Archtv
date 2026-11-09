# Step: initialise and populate the pacman keyring, verify the tarball signature.
progress 5 "Initialising keyring (this can take a minute)"
if [ ! -f /etc/pacman.d/gnupg/trustdb.gpg ]; then
  rm -rf /etc/pacman.d/gnupg
  pacman-key --init
fi
progress 50 "Populating Arch Linux ARM keys"
pacman-key --populate archlinuxarm

# The rootfs came over HTTP with an MD5 checksum; now that we have the ALARM
# keyring, check the tarball's detached signature against the pinned key.
tar="$ARCHDOCK_DL/ArchLinuxARM-aarch64-latest.tar.gz"
if [ -f "$tar.sig" ] && [ -f "$tar" ]; then
  progress 80 "Verifying rootfs signature"
  fprs=$(gpg --homedir /etc/pacman.d/gnupg --status-fd 1 --verify "$tar.sig" "$tar" 2>/dev/null | awk '/VALIDSIG/ { print $3; print $NF }')
  if echo "$fprs" | grep -qi "${ARCHDOCK_SIGNING_KEY:-68B3537F39A313B3E574D06777193F152BDBE6A6}"; then
    log "rootfs signature OK (Arch Linux ARM Build System)"
  else
    warn "could not verify the rootfs signature against the Arch Linux ARM build key"
    warn "fingerprints seen: ${fprs:-none}. Continuing, but check your mirror."
  fi
fi
progress 100 "Keyring ready"
