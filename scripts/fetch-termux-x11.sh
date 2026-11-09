#!/bin/bash
# Fetches Termux:X11 (GPL-3.0) with its native submodules (xserver, pixman,
# libxkbfile, …) into third_party/termux-x11. The next Gradle build then
# embeds its "lorie" X server module into ArchDock (see settings.gradle.kts).
# Builds against the NDK version Termux:X11 pins (installed via sdkmanager).
. "$(dirname "$0")/env.sh"
X11_REPO=https://github.com/termux/termux-x11
X11_REV=${X11_REV:-a31042ea443b7784d95dbceef086a21a55cf4445}
DEST=$ROOT/third_party/termux-x11

if [ ! -d "$DEST/.git" ]; then git clone -q "$X11_REPO" "$DEST"; fi
git -C "$DEST" fetch -q origin "$X11_REV" || git -C "$DEST" fetch -q origin
git -C "$DEST" checkout -q --force "$X11_REV"
git -C "$DEST" submodule sync -q
git -C "$DEST" submodule update --init --recursive --depth 1 -j 8

ndk=$(sed -n 's/.*termuxX11NdkVersion *= *"\(.*\)".*/\1/p' "$DEST/lorie/version.gradle")
if [ -n "$ndk" ] && [ ! -d "$ANDROID_HOME/ndk/$ndk" ]; then
  echo "Installing NDK $ndk required by Termux:X11…"
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$ndk" >/dev/null
fi
echo "Termux:X11 $(git -C "$DEST" rev-parse --short HEAD) ready – ./gradlew assembleRelease will embed it."
