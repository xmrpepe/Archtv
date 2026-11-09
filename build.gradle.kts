plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The embedded Termux:X11 module (third_party/termux-x11/lorie) is a Groovy
// build script that expects `kotlin-stdlib` versions etc. to resolve from the
// same repositories as the rest of the build.
