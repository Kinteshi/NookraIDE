import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** The editor engine; also in BuildConfig, for Copy Diagnostics. */
val aardinkVersion = "0.10.0"

android {
    namespace = "io.github.kinteshi.quirecode"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kinteshi.quirecode"
        minSdk = 34
        targetSdk = 35
        // A release's version comes from its tag (CI passes -Pavf.version=0.1.0); other builds are the next one's dev build.
        val version = providers.gradleProperty("avf.version").orNull ?: "0.1.0-dev"
        versionName = version
        versionCode = versionCodeOf(version)
        buildConfigField("String", "AARDINK_VERSION", "\"$aardinkVersion\"")
        manifestPlaceholders["appLabel"] = "QuireCode"
        buildConfigField("boolean", "DEV_BUILD", "false")
    }

    signingConfigs {
        // The release key comes from the environment (CI's secrets, or a local shell), never from the repository.
        val keystore = System.getenv("AVF_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("AVF_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AVF_KEY_ALIAS")
                keyPassword = System.getenv("AVF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // A debug build is its own app beside the release-signed one ("QuireCode Dev"), so trying a change on the
        // tablet doesn't need the release key or replace the installed app; it pairs with the guest like any client.
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "QuireCode Dev"
            // Told apart at a glance: an orange palette by default, a DEV badge, its own launcher icon (src/debug/res).
            buildConfigField("boolean", "DEV_BUILD", "true")
        }
        release {
            // Without the release key, the debug key: a local release build then installs over the debug app, data kept.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/** 0.1.0 → 100, 1.2.3 → 10203 (major, minor and patch in two digits each); a dev or pre-release build is the same number. */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = (version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
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
            source.copyTo(target.resolve(source.name), overwrite = true)
        }
    }
}

val guestBundle = tasks.register<GuestBundleTask>("guestBundle") {
    sources.from(
        rootProject.file("../../target/aarch64-unknown-linux-gnu/release/quirecode-daemon"),
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
    implementation("com.aardarch:aardink:$aardinkVersion")
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

// The libraries the release build ships, one `group:name:version` a line, for scripts/third-party-notices.py.
tasks.register("thirdPartyArtifacts") {
    val components = configurations.named("releaseRuntimeClasspath").flatMap { configuration ->
        configuration.incoming.resolutionResult.rootComponent.map { root ->
            val seen = linkedSetOf<String>()
            fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { dependency ->
                    when (val id = dependency.selected.id) {
                        is org.gradle.api.artifacts.component.ModuleComponentIdentifier ->
                            if (seen.add("${id.group}:${id.module}:${id.version}")) walk(dependency.selected)
                        // The app's own modules (tm4e): what they bring in ships too.
                        is org.gradle.api.artifacts.component.ProjectComponentIdentifier ->
                            if (seen.add("project ${id.projectPath}")) walk(dependency.selected)
                    }
                }
            }
            walk(root)
            seen.filterNot { it.startsWith("project ") }.sorted()
        }
    }
    val dependencyHandler = dependencies
    doLast {
        val listed = components.get()
        // The script reads each library's POM, and its parents', from Gradle's cache. Gradle skips POMs it doesn't need
        // (a library with Gradle module metadata), so a fresh cache (CI's) may lack them: fetch them, parents included.
        val fetched = mutableSetOf<String>()
        var wanted = listed.toSet()
        repeat(5) {
            wanted = wanted - fetched
            if (wanted.isEmpty()) return@repeat
            fetched += wanted
            val query = dependencyHandler.createArtifactResolutionQuery()
            wanted.forEach { coordinates ->
                val (group, name, version) = coordinates.split(":")
                query.forModule(group, name, version)
            }
            val parents = mutableSetOf<String>()
            query.withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute().resolvedComponents.forEach { component ->
                component.getArtifacts(MavenPomArtifact::class.java)
                    .filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>()
                    .forEach { pom ->
                        val parent = Regex("(?s)<parent>(.*?)</parent>").find(pom.file.readText())?.groupValues?.get(1) ?: return@forEach
                        fun tag(name: String) = Regex("<$name>\\s*([^<]+?)\\s*</$name>").find(parent)?.groupValues?.get(1)
                        val group = tag("groupId") ?: return@forEach
                        val artifact = tag("artifactId") ?: return@forEach
                        val version = tag("version") ?: return@forEach
                        parents += "$group:$artifact:$version"
                    }
            }
            wanted = parents
        }
        listed.forEach { println(it) }
    }
}
