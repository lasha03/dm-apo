plugins {
    id("com.android.application")
}

android {
    namespace = "app.dms"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dms"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        // A fixed, committed debug key so APKs built on any machine (or CI)
        // can be installed over each other without losing your login.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")

    testImplementation("junit:junit:4.13.2")
}
