# Third-party components

ArchDock as a whole is distributed under the **GNU GPL v3.0** (see `LICENSE`),
because it embeds GPL-licensed code (Termux:X11, proot). Every component below
is fetched from its upstream at a pinned revision by the scripts in `scripts/`
(or downloaded at install time), so the corresponding source is always
available from the listed location.

## Bundled in the APK

| Component | Used for | License | Source (pinned by) |
|---|---|---|---|
| **proot** (Termux fork) | `libproot.so`, `libproot-loader.so`: running the Arch rootfs without root | GPL-2.0-or-later | github.com/termux/proot `v5.1.107.102` (`scripts/build-proot.sh`) |
| **talloc** + libreplace | statically linked into proot | LGPL-3.0-or-later | samba.org talloc 2.5.0 (`scripts/build-proot.sh`) |
| **Termux:X11** (`lorie` module, incl. xserver, pixman, libxkbfile, xkbcomp, libXfont2, libfontenc, libxtrans, libxau, libxdmcp, libxshmfence, libepoxy, libxcvt, libtirpc, bzip2, xorgproto) | embedded X server (`libXlorie.so`) and its Android activity | GPL-3.0 (Termux:X11); MIT/X11 and similar for the X.org parts | github.com/termux/termux-x11 `a31042ea` (`scripts/fetch-termux-x11.sh`) |
| **virglrenderer** | `libvirgl_test_server.so`, `libvirglrenderer.so` – Venus/vtest GPU server | MIT | gitlab.freedesktop.org/virgl/virglrenderer 1.3.0 (`scripts/build-gpu.sh`) |
| Termux `virglrenderer-android` patches | Android support for virglrenderer (Venus on Android Vulkan, ashmem, EGL/GLES) | as the patched project (MIT) | github.com/termux/termux-packages `packages/virglrenderer-android` |
| **libepoxy** | `libepoxy.so` (GL dispatch for virglrenderer) | MIT | github.com/anholt/libepoxy 1.5.10 |
| **Mesa** "wrapper" Vulkan driver (bionic-vulkan-wrapper fork) | `libvulkan_wrapper.so` – forwards Vulkan to Android's `libvulkan.so` (Mali) | MIT | github.com/leegao/bionic-vulkan-wrapper `3abf4870` |
| **SPIRV-Tools** (fork with Mali passes) | statically linked into the wrapper | Apache-2.0 | github.com/leegao/SPIRV-Tools `20a5b430` |
| SPIRV-Headers, Vulkan-Headers | build-time headers | MIT / Apache-2.0 | github.com/KhronosGroup |
| **libdrm** | build dependency of the wrapper | MIT | gitlab.freedesktop.org/mesa/drm `libdrm-2.4.124` |
| Apache Commons Compress | rootfs extraction and backup (tar) | Apache-2.0 | Maven Central |
| zstd-jni | `.tar.zst` backups | BSD-2-Clause | Maven Central |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines | app framework | Apache-2.0 | Google Maven / Maven Central |

## Theme assets (Omarchy)

The seven palettes in `dotfiles/themes/*/colors.toml` and `neovim.lua`, and the
Alacritty/btop templates in `dotfiles/templates/`, come from
**Omarchy** – github.com/basecamp/omarchy (commit `f97d3e7d`),
© David Heinemeier Hansson, **MIT License**. The Omarchy wallpapers are *not*
stored in this repository: the installer downloads them from the same pinned
commit (sparse checkout of `themes/*/backgrounds/`) and installs Omarchy's
`LICENSE` to `/usr/share/licenses/archdock/omarchy-LICENSE` in the rootfs.
Individual wallpapers may carry their artists' own terms; see the Omarchy
repository.

The starship prompt and Walker/mako-style layouts are re-creations of
Omarchy's look for X11/MATE (Rofi, dunst, picom, Marco) written for ArchDock.

## Downloaded at install time (inside the Arch rootfs)

Arch Linux ARM and every package installed with `pacman` keep their own
licenses (see `/usr/share/licenses` in the rootfs). Also fetched:

* **papirus-folders** – github.com/PapirusDevelopmentTeam/papirus-folders, GPL-3.0
* **LazyVim starter** – github.com/LazyVim/starter, Apache-2.0
