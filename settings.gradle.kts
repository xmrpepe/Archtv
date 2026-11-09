pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ArchDock"
include(":app")

// ---------------------------------------------------------------------------
// Optional: embedded Termux:X11 X server (GPL-3.0).
//
// scripts/fetch-termux-x11.sh checks out termux-x11 (with its native
// submodules) into third_party/termux-x11. When present, and unless
// -Parchdock.x11=false is passed, its `lorie` library module is built into the
// APK and becomes the primary display backend. Without it ArchDock still
// builds and uses the VNC backend.
// ---------------------------------------------------------------------------
val x11Root = file("third_party/termux-x11")
val x11Wanted = (providers.gradleProperty("archdock.x11").orNull ?: "auto") != "false"
val x11Present = file("third_party/termux-x11/lorie/build.gradle").exists() &&
    file("third_party/termux-x11/lorie/src/main/cpp/xserver/dix").exists()
gradle.extra["archdockX11"] = x11Wanted && x11Present
if (x11Wanted && x11Present) {
    include(":lorie")
    project(":lorie").projectDir = File(x11Root, "lorie")
    include(":shell-loader:stub")
    // Only the stub is needed; the real :shell-loader build script expects Termux:X11's own app module.
    project(":shell-loader").projectDir = file("gradle/shell-loader-parent")
    project(":shell-loader:stub").projectDir = File(x11Root, "shell-loader/stub")
}
