// Android Gradle Plugin 9.x has built-in Kotlin compilation, so the modules
// apply only the Android plugins. Keep versions here in one place.
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
}
