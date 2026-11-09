import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val withX11: Boolean = (gradle.extra.properties["archdockX11"] as? Boolean) ?: false

// Release signing: provide keystore.properties (storeFile, storePassword,
// keyAlias, keyPassword) or ARCHDOCK_KEYSTORE* env vars. Without them the
// release build is signed with the debug key so `assembleRelease` always
// yields an installable APK.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "dev.archdock"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.archdock"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_PLATFORM=android-31")
            }
        }
        buildConfigField("boolean", "X11_EMBEDDED", withX11.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "ARCHDOCK_KEYSTORE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "ARCHDOCK_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ARCHDOCK_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ARCHDOCK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingValue("storeFile", "ARCHDOCK_KEYSTORE") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // proot, its loader and the other helper executables ship as lib*.so
        // so Android extracts them to nativeLibraryDir, the only app location
        // that is executable on Android 10+ (W^X).
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    sourceSets {
        getByName("main") {
            val variantSrc = if (withX11) "src/x11/java" else "src/nox11/java"
            java.srcDir(variantSrc)
            kotlin.srcDir(variantSrc)
            // Theme files and rootfs scripts live at the repo root so they can
            // be edited without touching app code; they are packaged as assets.
            assets.srcDir(rootProject.file("dotfiles"))
        }
    }

    testOptions.unitTests.isReturnDefaultValues = true
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation("${libs.zstd.jni.get()}@aar")
    debugImplementation(libs.compose.ui.tooling)

    if (withX11) {
        implementation(project(":lorie"))
        implementation(project(":x11-embed"))
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.commons.compress)
    testImplementation(libs.zstd.jni)
}
