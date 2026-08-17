pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lydex-plugin-examples"

// The shared contract every stream provider compiles against.
include(":stream_plugin_api")
// The reference provider — a complete, shipping Subsonic plugin.
include(":subsonic")
project(":subsonic").projectDir = file("subsonic")
// A minimal DSP effect: a WebAssembly module + a thin APK wrapper.
include(":dsp-example")
