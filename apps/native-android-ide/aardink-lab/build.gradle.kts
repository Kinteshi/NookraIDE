plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.avfstudio.aardinklab"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.avfstudio.aardinklab"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("com.aardarch:aardink:0.6.2")
    implementation("com.aardarch:aardink-languages:0.6.2")
}
