plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.avfstudio.nativeide"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.avfstudio.nativeide"
        minSdk = 24
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

// "Set up guest" ships the arm64 daemon and its installer; build the daemon first with
// scripts/build-guest-bundle.sh (without it the app explains that the bundle is missing).
abstract class GuestBundleTask : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().dir("guest").asFile
        target.deleteRecursively()
        target.mkdirs()
        sources.files.filter { it.isFile }.forEach { source ->
            val name = if (source.name == "avf-daemon") "avf-native-daemon" else source.name
            source.copyTo(target.resolve(name), overwrite = true)
        }
    }
}

val guestBundle = tasks.register<GuestBundleTask>("guestBundle") {
    sources.from(
        rootProject.file("../../target/aarch64-unknown-linux-gnu/release/avf-daemon"),
        rootProject.file("../../scripts/install-native-daemon.sh"),
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(guestBundle, GuestBundleTask::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.connectbot:termlib:0.3.14")
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.msgpack:msgpack-core:0.9.8")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.code.gson:gson:2.13.1")
}
