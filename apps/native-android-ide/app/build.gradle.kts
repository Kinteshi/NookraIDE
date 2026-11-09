plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.avfstudio.nativeide"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.avfstudio.nativeide"
        minSdk = 34
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
    implementation("com.aardarch:aardink:0.9.0")
    implementation(project(":tm4e"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.msgpack:msgpack-core:0.9.8")
    // Markdown preview: CommonMark plus the GitHub extensions VS Code's preview renders.
    implementation("org.commonmark:commonmark:0.30.1")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.1")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.1")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.1")
    implementation("org.commonmark:commonmark-ext-autolink:0.30.1")
    // SVG files drawn in the image viewer (Apache-2.0; no scripts, nothing fetched).
    implementation("com.caverock:androidsvg-aar:1.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.code.gson:gson:2.13.1")
}

// Aardink's classes are Java 21 bytecode, so the JVM that runs the tests must be at least that.
val testJvm = extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<Test>().configureEach { javaLauncher.set(testJvm) }
