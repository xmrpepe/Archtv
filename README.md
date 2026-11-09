# ArchDock

Arch Linux ARM with a MATE desktop in Omarchy's style, running in **proot** on
an Android phone and shown full screen on a TV over USB‑C → HDMI. Tuned for the
**Google Pixel 9** (Tensor G4: 1× Cortex‑X4, 3× A720, 4× A520; Mali‑G715).

* Kotlin + Jetpack Compose, `minSdk 31`, `targetSdk 35`, `arm64-v8a` only
* No Termux app needed: proot, the X server and the GPU server ship inside the APK
* Idle until a display is plugged in → auto‑starts the session on the TV;
  unplug and the session keeps running; plug back in and it resumes

> **Status.** The app, the installer, the theming engine and the native
> builds compile, and the unit tests pass on the build machine. Nothing here
> has run on a Pixel 9 yet. Read [Known limitations](#known-limitations)
> before relying on it — the GPU path and multi‑display behaviour are the parts
> most likely to need work on real hardware.

---

## How it works

```
 Android (bionic, app process + children)                    Arch Linux ARM (glibc, under proot)
 ─────────────────────────────────────────                   ───────────────────────────────────
 MainActivity  ── idle screen, installer, settings
 SessionService (FGS specialUse, wake + Wi‑Fi locks)
   ├─ DisplayWatcher (DisplayManager.DisplayListener)
   ├─ X server ── embedded Termux:X11 (app_process) ─┐       /tmp/.X11-unix/X0
   │             or Xvnc in the guest + RFB client   ├──────▶ start-desktop: dbus, PulseAudio,
   ├─ virgl_test_server --venus ── Mesa wrapper ─────┼──┐       archdock-theme-set, mate-session
   │      └─▶ /system/lib64/libvulkan.so (Mali)      │  │     Venus ICD ─▶ /tmp/.virgl_test
   ├─ AudioBridge (AAudio) ◀── TCP 127.0.0.1:4714 ───┼──┼──── PulseAudio null sink monitor
   ├─ InputBridge ── XTEST over the X socket ────────┘  │     Zink (GL on Vulkan) ─▶ picom, Firefox…
   └─ proot (libproot.so, link2symlink, fake root, sysvipc) ─┘
 TV: Termux:X11 MainActivity or ExternalDisplayActivity (VNC), launched on the HDMI display
 Phone: ControlActivity = trackpad + keyboard with Super key
```

### Modules (`app/src/main/java/dev/archdock`)

| Module | File(s) | What it does |
|---|---|---|
| InstallerManager | `installer/` | Checkpointed one‑time installer: preflight, resumable download + checksum, pure‑JVM tar extraction, rootfs prep, guest step scripts |
| ProotRunner | `proot/` | Builds proot command lines (binds, fake `/proc`, `--change-id`), runs scripts, spawns long‑running guest processes |
| SessionService | `session/SessionService.kt` | Foreground service, notification (Stop / Restart / Open Terminal), wake + Wi‑Fi locks, display auto‑start |
| SessionController | `session/SessionController.kt` | X server → GPU probe → desktop → audio → TV; supervises and restarts crashed parts with backoff; phantom‑process detection |
| DisplayWatcher | `display/DisplayWatcher.kt` | Detects HDMI/DP displays, picks resolution/refresh, derives DPI |
| X11Renderer | `x11/`, `display/` | `EmbeddedX11Server` (Termux:X11), `VncServer` + `VncClient` + `VncSurfaceView`, `ExternalWindowConfigurator` (immersive, keep‑screen‑on, display mode) |
| GpuManager | `gpu/GpuManager.kt` | Starts the Android‑side GPU server, probes Vulkan/GL in the guest, falls back Vulkan wrapper → VirGL → llvmpipe |
| ThemeManager | `theme/ThemeManager.kt` | Lists Omarchy themes, applies them live via `archdock-theme-set` |
| AudioBridge | `audio/`, `cpp/audio_sink.cpp` | Reads the PulseAudio stream and plays it on a low‑latency AAudio stream (HDMI) |
| InputBridge | `input/` | XTEST client, Android→evdev keymap (Super = Meta), trackpad gestures, gamepad mapping |
| SettingsRepo | `settings/SettingsRepo.kt` | DataStore‑backed settings |

Extras: `backup/` (`.tar.zst` backup/restore), `terminal/` (phone terminal via
`script` for a real pty), `perf/` (CPU/GPU/temp/FPS, thermal warnings, thread
pinning), `core/` (logs, crash handler).

---

## Building

Requirements: JDK 17+, Android SDK (platform 37, build‑tools 35+), **NDK
27.2.12479018**, CMake 3.22. The native scripts also need `git`, `curl`,
`gawk`, `python3`, `meson`, `ninja`, `pkg-config`, `patchelf`, `glslangValidator`
(`pip install --user meson ninja mako packaging pyyaml`; `apt install gawk
patchelf glslang-tools pkg-config`).

```bash
export ANDROID_HOME=~/Android/Sdk

# 1. proot + loader → app/src/main/jniLibs/arm64-v8a/libproot*.so   (~1 min)
./scripts/build-proot.sh

# 2. Termux:X11 sources (+ the NDK version it pins). Optional but recommended:
#    without it the APK only has the VNC display backend.
./scripts/fetch-termux-x11.sh

# 3. Android‑side GPU server: virglrenderer (Venus) + Mesa wrapper + Vulkan shim.
#    Optional: without it ArchDock uses llvmpipe.   (~15–30 min)
./scripts/build-gpu.sh            # ARCHDOCK_SKIP_WRAPPER=1 to skip the Mesa wrapper

# 4. The APK
./gradlew assembleRelease         # → app/build/outputs/apk/x11/release/app-x11-release.apk
                                  #   (…/vnc/… when Termux:X11 isn't fetched)
./gradlew test                    # unit tests (extraction, backup, X11/RFB protocol, parsing)
```

* `-Parchdock.x11=false` builds the VNC‑only flavor even if Termux:X11 is present.
* Release signing: create `keystore.properties` (`storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`) or set `ARCHDOCK_KEYSTORE*` env vars; otherwise the
  release APK is signed with the debug key so it installs directly.
* Native code is compiled with `-O3 -mcpu=cortex-x4` (all Tensor G4 cores are
  Armv9.2). Use `ARCHDOCK_CPU=generic` / `-DARCHDOCK_CPU=generic` for other phones.
* Built `.so` files are not committed (`.gitignore`); `third_party/` holds the
  fetched sources and build trees.

### What has been verified on the build machine

* `./gradlew assembleRelease` with Termux:X11 embedded (R8, 6 MB APK) and the
  VNC‑only flavor; one launcher icon in the merged manifest.
* `scripts/build-proot.sh`: static arm64 proot + loader; runs under
  `qemu-aarch64` and exposes every option ArchDock uses.
* `scripts/build-gpu.sh`: libepoxy, virglrenderer 1.3.0 with Venus and
  Termux's Android patches, libdrm, the SPIRV‑Tools fork and the Mesa wrapper
  ICD all cross‑compile and link; the resulting libraries only depend on
  Android system libraries or each other. Whether they run correctly on the
  Mali driver is untested.
* Unit tests (`./gradlew test`): tar extraction (modes, symlinks, hard links,
  device nodes, path traversal, PAX long names), `.tar.zst` backup round trip,
  XTEST and RFB wire formats against fake servers, `pacman.conf` patching,
  checksum parsing, theme palettes, display mode selection.
* `archdock-theme-set` rendered for all 7 themes on the host; every generated
  TOML/XML/JSON file parses.
* Every guest package name was checked against the current Arch Linux ARM
  `core`/`extra`/`alarm` databases.

---

## Using it

1. **First launch** → the installer: pre‑flight checks (arm64, Android 12+,
   10 GB free, proot present), then Start. It downloads
   `ArchLinuxARM-aarch64-latest.tar.gz` (mirror selectable in Settings),
   verifies the MD5 and later the Arch Linux ARM GPG signature, extracts it and
   runs the guest steps in `dotfiles/install/`. Each step is checkpointed: kill
   the app and it resumes where it stopped. The install runs inside the
   foreground service, so it survives switching apps.
2. **Plug in the TV** (USB‑C DisplayPort alt‑mode → HDMI). The desktop starts on
   the TV in landscape at the TV's native mode (capped at 4K) and the phone
   becomes a trackpad + keyboard.
3. **Unplug** → the session keeps running (notification: *running in the
   background*). **Replug** → same session, same windows.
4. **Stop** from the app, the notification, the trackpad screen, or the
   ArchDock menu → Power inside the desktop.

### Keybindings (Omarchy‑style)

| Keys | Action |
|---|---|
| Super + Enter | Alacritty |
| Super + Space | Launcher (Rofi, Walker style) |
| Super + Alt + Space | ArchDock menu (apps, themes, keybindings, update, screenshot, power) |
| Super + Ctrl + Shift + Space | Theme switcher |
| Super + W / Super + F | Close / maximise |
| Super + 1…9 (Shift: move window) | Workspaces |
| Super + B / E / N / T / G | Firefox / Caja / Neovim / btop / lazygit |
| Super + K | Cheatsheet |
| Print / Shift+Print / Alt+Print | Screenshot region / screen / window |

Window and workspace keys are Marco's (`config/dconf/archdock.dconf`);
launchers are sxhkd (`config/sxhkd/sxhkdrc`). On the phone, the **Super**,
Ctrl, Alt and Shift buttons are sticky modifiers for the next key; Launcher,
Menu, Term and Close send the chords directly. Bluetooth/USB keyboards' Meta
key arrives as Super. Game controllers are mapped to mouse/keys (left stick =
pointer, A/B = clicks, Start = menu), since the guest can't get `/dev/input`.

### Themes

Tokyo Night (default), Catppuccin, Gruvbox, Nord, Everforest, Kanagawa and
Rose Pine, using Omarchy's palettes and wallpapers. A theme recolours GTK 2/3/4
(adw‑gtk3 + generated CSS), Marco borders, the MATE panel, Papirus folders,
the wallpaper, Alacritty, Rofi, dunst, picom shadows, btop, Neovim (LazyVim),
starship, fastfetch, lazygit and fzf/bat, live without logging out. Switch from
Super+Ctrl+Shift+Space, the ArchDock menu, or Android Settings.

All theme material is plain files under `dotfiles/` (copied to `/opt/archdock`
in the rootfs):

```
dotfiles/
  themes/<id>/colors.toml   palette (Omarchy format)   theme.conf   neovim.lua
  templates/*.tpl           {{ key }}, {{ key_strip }}, {{ key_rgb }} placeholders
  bin/                      start-desktop, archdock-theme-set, menus, compositor, GPU probe
  config/                   alacritty, picom, sxhkd, rofi, zsh, dconf, panel layout, pulse
  install/                  guest installer steps
```

To edit or add a theme **without rebuilding**: put a folder with a
`colors.toml` in `~/.config/archdock/themes/<id>/` inside Arch (overrides
shipped ones; custom templates go in `~/.config/archdock/templates/`) and run
`archdock-theme-set <id>`. Edits to `dotfiles/` in the repo reach an existing
install with Settings → *Re‑sync dotfiles*.

### Settings

Resolution, refresh rate, DPI, UI scale, backend (auto / X11 / VNC), how the
desktop is shown on the TV, GPU mode (Vulkan wrapper / VirGL / llvmpipe),
theme, auto‑start on connect, keep running on disconnect, audio, clipboard
sync, the shared Downloads folder, compositor / blur / shadows (applied live),
big‑core pinning, performance overlay, mirror, Re‑sync dotfiles, **Repair**
(re‑runs configuration steps), **Reinstall**, **Backup / Restore**
(`.tar.zst` via the Android file picker), crash logs and **Export logs**.

---

## GPU on Mali‑G715

* **Turnip** is Adreno‑only. **Panfrost** needs `/dev/dri` and a kernel driver
  Android doesn't expose to apps. Neither applies.
* The guest is **glibc**, Android's `libvulkan.so` and the Mali driver are
  **bionic**, so no Android GPU library can be loaded inside proot. The
  Mesa "wrapper" driver (from the bionic‑vulkan‑wrapper fork used by
  Winlator/MiceWine) is itself a bionic library.
* So the passthrough is split at a socket: guest apps use Mesa's **Venus**
  Vulkan driver (Arch package `vulkan-virtio`, registered as
  `/usr/share/vulkan/icd.d/archdock_wrapper_icd.aarch64.json`, selected with
  `VK_ICD_FILENAMES`), which serialises Vulkan over `/tmp/.virgl_test` to
  `virgl_test_server --venus` running on the Android side. That server
  (virglrenderer + Termux's Android patches) loads Vulkan through
  `libvulkan_archdock.so`, a small single‑ICD shim that loads the **Mesa
  wrapper** (`libvulkan_wrapper.so`), which forwards to
  `/system/lib64/libvulkan.so` → Mali. If the wrapper is missing or fails,
  the shim uses the system driver directly.
* **OpenGL** comes from **Zink** on that Vulkan device
  (`MESA_LOADER_DRIVER_OVERRIDE=zink`, `GALLIUM_DRIVER=zink`); picom then uses
  its GLX backend.
* At every session start `archdock-gpu-probe` runs `vulkaninfo` and `glxinfo`
  against the live display. If the wrapper path fails, ArchDock tries **VirGL**
  (GL over the same server on Mali GLES), then **llvmpipe**, and shows the
  active renderer and the reason on the home screen. **GPU Test** runs
  `vulkaninfo --summary` and `glxinfo -B` on demand (in a private Xvfb if no
  session is running).

## Audio

PulseAudio in the guest uses a null sink as the default output, loads
`module-native-protocol-tcp` (127.0.0.1:4713, for clients) and streams the
sink's monitor as raw s16le/48 kHz/stereo with `module-simple-protocol-tcp`
on 127.0.0.1:4714. `AudioBridge` reads that and writes it to an AAudio
low‑latency stream (2 bursts of buffering, drops audio if the guest clock runs
ahead). Android routes media to HDMI while a TV is connected.

---

## Known limitations

* **Not yet tested on a Pixel 9.** Expect to iterate on the device.
* **Mirroring vs. extended display.** Pixel's USB‑C display output mirrors by
  default. ArchDock launches its TV activity on the external display with
  `ActivityOptions.setLaunchDisplayId`; on some builds third‑party apps may
  only get the display after enabling *Developer options → Enable desktop
  experience features / Force desktop mode* (names vary by Android version).
  If launching on the display is refused, the error is logged. The
  *Presentation window* mode is an alternative for the VNC backend.
* **Phone screen off.** The TV window holds `FLAG_KEEP_SCREEN_ON`, and the
  service holds a partial wake lock. Whether the external display stays lit
  when the phone's power button turns the internal screen off depends on the
  Android build.
* **Phantom process killer.** A MATE session easily exceeds Android's 32
  child‑process limit. Turn on *Developer options → Disable child process
  restrictions*, or run
  `adb shell "settings put global settings_enable_monitor_phantom_procs false"`.
  ArchDock checks the setting, warns on the home screen, and flags SIGKILLed
  components.
* **GPU path is experimental.** Venus needs extensions from the host driver
  that Termux's patches emulate via AHardwareBuffer; how well this works with
  the Mali‑G715 driver is unverified. llvmpipe always works.
* **No systemd, udev or logind** (proot). Services that need them are masked;
  power management, screen locking and hardware hot‑plug inside Linux don't
  exist. dbus and PulseAudio are started by `start-desktop`.
* **Game controllers** are mapped to mouse/keyboard; Linux games can't see
  them as joysticks.
* **Embedded X11 FPS** isn't measured (the overlay shows FPS for the VNC
  renderer only). CPU% covers ArchDock's own processes, because Android hides
  `/proc/stat`. Mali utilisation shows as n/a where sysfs is unreadable.
* **Thread pinning** to the big cores (`0xF0` on Tensor G4, read from
  cpufreq) is best‑effort; Android's cpusets still apply.
* **Rootfs integrity.** Arch Linux ARM serves the tarball over HTTP with an
  MD5 file; the installer additionally verifies the GPG signature against the
  pinned Arch Linux ARM build key once the keyring is available, and warns if
  that fails.

## Troubleshooting

| Symptom | What to do |
|---|---|
| Pre‑flight says *proot binary missing* | Run `scripts/build-proot.sh` before building the APK. |
| Install stops at *Download* | Pick another mirror in Settings, then *Resume install*; partial downloads resume. |
| A guest step fails | Logs → install shows the failing command. Fix connectivity/space and resume; steps are idempotent. Settings → Repair re‑runs configuration only. |
| Desktop dies after a minute, notification *Android killed the Linux desktop* | Phantom process killer – see above. |
| Black TV, no desktop | Logs → x11/session. Try Settings → Backend → VNC. Check that *Show on TV via* works on your build. |
| Slow graphics, renderer *llvmpipe (fallback)* | GPU Test shows why. Turn off blur/shadows; rebuild with `scripts/build-gpu.sh`. |
| No sound | Home screen *Audio* line; `pactl info` in the terminal; `/tmp/archdock/logs/pulseaudio.log`. |
| Super key opens Android's keyboard switcher | Some keyboards send Meta+Space to Android first; use the on‑screen Launcher button or remap. |
| Wrong DPI / tiny text on 4K | Settings → DPI 192 or UI scale 2×, then restart the session. |

Logs: in‑app *Logs* (per component + crashes, *Export logs* zips everything
including guest logs from `/tmp/archdock/logs`).

## Licenses

ArchDock is **GPL‑3.0** (`LICENSE`) because it embeds Termux:X11 (GPL‑3.0) and
proot (GPL‑2.0+). Bundled and downloaded components, including the
Omarchy themes (MIT, © David Heinemeier Hansson), are listed with their
licenses in [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).
