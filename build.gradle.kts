plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.yousef.farz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yousef.farz2"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // One fixed key, so a new build installs over the old one and keeps your sorting.
    signingConfigs {
        getByName("debug") {
            storeFile = file("farz.keystore")
            storePassword = "farz5678"
            keyAlias = "farz2"
            keyPassword = "farz5678"
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
}
