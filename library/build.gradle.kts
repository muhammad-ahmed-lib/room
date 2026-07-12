plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "com.roomx"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        // no targetSdk here — library modules don't have that property
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

// -----------------------------------------------------------------------
// Publishing config — required for JitPack (or any Maven repo) to build
// a consumable .aar from this module.
// -----------------------------------------------------------------------
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.muhammad-ahmed-lib" // JitPack requires this exact format
            artifactId = "roomx"
            version = "1.0.6" // bump this per release/tag

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}