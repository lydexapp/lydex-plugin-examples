plugins {
    id("com.android.library")
}

// Shared contract between the Lydex host and stream-provider plug-in APKs.
// Both sides compile this exact module so the AIDL descriptor and the JSON
// payload schema stay byte-identical. Keep this module dependency-free
// beyond the Android SDK — plug-ins should not inherit host libraries.

android {
    namespace = "dev.lydex.plugin.stream"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
