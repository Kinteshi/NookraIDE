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

    // Measure this one: a debuggable build penalises a Compose editor far more than sora's view.
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The app's TextMate grammar and theme, for the sora comparison screen.
    sourceSets["main"].assets.srcDirs("src/main/assets", "../app/src/main/assets")
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("com.aardarch:aardink:0.8.0")
    implementation("com.aardarch:aardink-languages:0.8.0")
    // sora-editor as the app uses it, measured side by side with aardink (SoraLabActivity).
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")
}
