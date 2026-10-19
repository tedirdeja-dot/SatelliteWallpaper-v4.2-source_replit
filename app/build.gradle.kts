plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.satellite.wallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.satellite.wallpaper"
        minSdk = 23
        targetSdk = 35
        versionCode = 21
        versionName = "4.2"
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
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
