plugins {
    id("com.android.application")
}

// Subsonic stream-provider plug-in — the first shipping provider.
//
// Speaks the Subsonic API, so one plug-in covers every Subsonic-compatible
// server: Navidrome, Airsonic(-Advanced), Gonic, Ampache's compatibility
// endpoint, and Jellyfin's. Credentials are the user's own server account;
// there is nothing platform-gated here, which is why this can ship and be
// open-sourced as-is.
//
// Intended to be published as its own repository. Unlike the platform
// plug-ins it needs no build-time credentials at all — the user enters
// their server URL and account in the plug-in's login screen.

android {
    namespace = "dev.lydex.plugins.subsonic"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lydex.plugins.subsonic"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    base {
        archivesName.set("lydex-subsonic-plugin")
    }
}

dependencies {
    implementation(project(":stream_plugin_api"))
    testImplementation("junit:junit:4.13.2")
}
