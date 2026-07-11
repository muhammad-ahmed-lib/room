// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Top-level build file where you can add configuration options common to all sub-projects/modules.


plugins {
    id("com.android.application") version "9.0.0-alpha06" apply false
    id("com.android.library") version "9.0.0-alpha06" apply false  // Add this line
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false  // Add Kotlin plugin here
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}