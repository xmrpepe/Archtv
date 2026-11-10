# ArchDock 0.1.0 – release APK

`ArchDock-0.1.0-arm64-v8a-release.apk` (x11 flavor: embedded Termux:X11, proot,
virglrenderer + Mesa wrapper GPU server). arm64-v8a only, Android 12+ (minSdk 31).

* SHA-256: `d3e191d1e1829d280289ac59e4ec86ea4311f5aed9cda9bea68d6c1b26183e56`
* Signed with the **Android debug key** (no release keystore was configured),
  so it installs directly but can't be updated in place by a build signed with
  a different key – uninstall first in that case.
* Built from the commit that adds this file, with `./gradlew assembleRelease`
  after `scripts/build-proot.sh`, `scripts/fetch-termux-x11.sh` and
  `scripts/build-gpu.sh`.
* Native code targets Cortex-X4 (Tensor G4); it may crash on older arm64 phones.
* Not yet tested on a Pixel 9 – see the main README's "Known limitations".

Install: `adb install -r ArchDock-0.1.0-arm64-v8a-release.apk`
