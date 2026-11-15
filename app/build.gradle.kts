plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.foster.kitchencalendar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foster.kitchencalendar"
        minSdk = 31          // Android 12+: needed for portrait/landscape size-mapped widgets
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // A fixed debug key checked into the repo, so every build (local or GitHub Actions)
    // is signed the same way and installs as an update instead of needing an uninstall.
    signingConfigs {
        getByName("debug") {
            storeFile = file("kitchen-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // Signed with the debug key so you can sideload a release build without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
