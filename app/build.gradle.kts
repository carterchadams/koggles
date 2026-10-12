import java.util.Properties
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Cardboard SDK: built from the pinned submodule with its own Gradle wrapper.
val cardboardDir = rootProject.file("third_party/cardboard")
val cardboardAar = rootProject.file("third_party/cardboard/sdk/build/outputs/aar/sdk-release.aar")

val buildCardboardSdk by tasks.registering(Exec::class) {
    description = "Builds the Cardboard SDK AAR from third_party/cardboard."
    workingDir = cardboardDir
    commandLine("./gradlew", ":sdk:assembleRelease")
    inputs.files(
        fileTree(cardboardDir) {
            include("build.gradle", "settings.gradle", "gradle.properties", "gradle/**", "proto/**", "java_api/**", "sdk/**")
            exclude("**/build/**", "**/.cxx/**", "**/.gradle/**")
        },
    ).withPropertyName("cardboardSources")
    outputs.file(cardboardAar).withPropertyName("cardboardAar")
}

val extractCardboardNative by tasks.registering(Copy::class) {
    description = "Extracts libGfxPluginCardboard.so from the Cardboard AAR for CMake to link."
    dependsOn(buildCardboardSdk)
    from(zipTree(cardboardAar)) {
        include("jni/arm64-v8a/libGfxPluginCardboard.so")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("cardboard/jni/arm64-v8a"))
}

tasks.named("preBuild") { dependsOn(buildCardboardSdk) }
tasks.matching {
    it.name.startsWith("configureCMake") ||
        it.name.startsWith("buildCMake") ||
        it.name.startsWith("externalNativeBuild")
}.configureEach { dependsOn(extractCardboardNative) }

// Bundled content (Task 12, spec §5.1): pinned projectM preset and texture repos, laid out as
// APK assets under assets/presets/, assets/textures/ and assets/licenses/.
abstract class FetchBundledContent : DefaultTask() {
    /** Repo name (the directory under [outputDir]) to clone URL. */
    @get:Input abstract val urls: MapProperty<String, String>

    /** Repo name to the commit SHA to check out. */
    @get:Input abstract val shas: MapProperty<String, String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun fetch() {
        val shaByRepo = shas.get()
        for ((name, url) in urls.get()) {
            val sha = shaByRepo.getValue(name)
            val dir = outputDir.get().asFile.resolve(name)
            val haveSha = dir.resolve(".git").isDirectory &&
                git(dir, "cat-file", "-e", "$sha^{commit}", ignoreExit = true) == 0
            if (!haveSha) {
                dir.deleteRecursively()
                git(outputDir.get().asFile, "clone", "--quiet", "--filter=blob:none", "--no-checkout", url, name)
            }
            git(dir, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--force", sha)
            git(dir, "clean", "-dffxq")
        }
    }

    private fun git(workDir: File, vararg args: String, ignoreExit: Boolean = false): Int =
        exec.exec {
            workingDir = workDir
            commandLine(listOf("git") + args)
            isIgnoreExitValue = ignoreExit
            if (ignoreExit) errorOutput = OutputStream.nullOutputStream()
        }.exitValue
}

/**
 * Stages the Koggles Starter Pack (spec §5.1): exactly the upstream files the allowlist names, each
 * checked against its pinned SHA-256 and flattened into one folder. Fails naming every missing path,
 * hash mismatch and duplicate file name, so the bundle can't silently drift from the reviewed list.
 */
abstract class StageStarterPack : DefaultTask() {
    @get:InputFile abstract val allowlist: RegularFileProperty
    @get:InputDirectory abstract val repoDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val repo = repoDir.get().asFile
        val names = mutableSetOf<String>()
        val problems = mutableListOf<String>()
        allowlist.get().asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val want = line.substringBefore("  ")
            val path = line.substringAfter("  ")
            val src = repo.resolve(path)
            when {
                !src.isFile -> problems += "missing upstream: $path"
                !names.add(src.name) -> problems += "duplicate file name: $path"
                else -> {
                    val got = MessageDigest.getInstance("SHA-256").digest(src.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    if (got != want) problems += "sha256 mismatch: $path (allowlist $want, upstream $got)"
                    else src.copyTo(out.resolve(src.name))
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Koggles Starter Pack allowlist ${allowlist.get().asFile}:\n" + problems.joinToString("\n"))
        }
    }
}

val bundledContentDir = layout.buildDirectory.dir("content")
val bundledAssetsDir = layout.buildDirectory.dir("generated/bundled")

val fetchBundledContent by tasks.registering(FetchBundledContent::class) {
    description = "Clones the pinned bundled preset and texture repos into build/content/."
    urls.put("presets-projectm-classic", "https://github.com/projectM-visualizer/presets-projectm-classic")
    urls.put("presets-milkdrop-texture-pack", "https://github.com/projectM-visualizer/presets-milkdrop-texture-pack")
    shas.put("presets-projectm-classic", libs.versions.classicSha.get())
    shas.put("presets-milkdrop-texture-pack", libs.versions.texturePackSha.get())
    outputDir.set(bundledContentDir)
}

val stageStarterPack by tasks.registering(StageStarterPack::class) {
    description = "Stages the hash-verified Koggles Starter Pack from the fetched classic presets."
    allowlist.set(providers.gradleProperty("kogglesPresetAllowlist").map { layout.projectDirectory.file(it) }
        .orElse(layout.projectDirectory.file("bundled-presets.txt")))
    repoDir.set(fetchBundledContent.flatMap { it.outputDir.dir("presets-projectm-classic") })
    outputDir.set(layout.buildDirectory.dir("generated/starter-pack"))
}

val syncBundledContent by tasks.registering(Sync::class) {
    description = "Lays out the fetched content as assets in build/generated/bundled/."
    val textureExt = setOf("jpg", "jpeg", "png", "tga", "bmp", "dds") // = presets.TEXTURE_EXT
    val classic = fetchBundledContent.flatMap { it.outputDir.dir("presets-projectm-classic") }
    val pack = fetchBundledContent.flatMap { it.outputDir.dir("presets-milkdrop-texture-pack") }
    from(stageStarterPack) { into("presets/Koggles Starter Pack") }
    from(pack.map { it.dir("textures") }) {
        include { it.isDirectory || it.name.substringAfterLast('.', "").lowercase() in textureExt }
        eachFile { path = "textures/$name" }
    }
    from(classic) {
        include("LICENSE.md", "README.md")
        rename { "projectm-classic-presets-$it" }
        into("licenses")
    }
    from(pack) {
        include("LICENSE.md", "README.md")
        rename { "milkdrop-texture-pack-$it" }
        into("licenses")
    }
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into(bundledAssetsDir)
}

tasks.named("preBuild") { dependsOn(syncBundledContent) }

// androidTest asset: the allowlist BundledPackTest checks the APK's pack against. Mutation runs pass
// a deliberately wrong copy with -PkogglesTestAllowlist=<file>; the shipped pack is unaffected.
val androidTestAllowlist by tasks.registering(Sync::class) {
    from(providers.gradleProperty("kogglesTestAllowlist").map { layout.projectDirectory.file(it) }
        .orElse(layout.projectDirectory.file("bundled-presets.txt"))) { rename { "bundled-presets.txt" } }
    into(layout.buildDirectory.dir("generated/androidTestAllowlist"))
}
tasks.named("preBuild") { dependsOn(androidTestAllowlist) }

android {
    namespace = "dev.koggles"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.koggles"
        minSdk = 29
        targetSdk = 36
        // Release CI passes both from the tag (tools/ci/version-from-tag.sh); local builds keep the defaults.
        val tagVersionName = providers.gradleProperty("kogglesVersionName").orNull
        val tagVersionCode = providers.gradleProperty("kogglesVersionCode").orNull
        if ((tagVersionName == null) != (tagVersionCode == null)) {
            throw GradleException("kogglesVersionName and kogglesVersionCode must be set together")
        }
        versionCode = tagVersionCode?.toInt() ?: 1
        versionName = tagVersionName ?: "0.1.0"
        testInstrumentationRunner = "dev.koggles.KogglesTestRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                // Test builds only: capture projectM preset/shader diagnostics (see jni/diagnostics.h).
                if (providers.gradleProperty("kogglesShaderDiagnostics").isPresent) {
                    arguments += "-DKOGGLES_SHADER_DIAGNOSTICS=ON"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release signing (Blueprint Task 21). CI passes the credentials as environment variables
    // (KOGGLES_KEYSTORE_FILE, KOGGLES_STORE_PASSWORD, KOGGLES_KEY_ALIAS, KOGGLES_KEY_PASSWORD), so they
    // reach the signer unchanged; locally, keystore.properties at the repo root (never committed).
    // With neither, the release APK is left unsigned (CI's release workflow refuses to run that way).
    val envKeystore = providers.environmentVariable("KOGGLES_KEYSTORE_FILE").orNull
    val keystoreProps = rootProject.file("keystore.properties")
    val releaseSigning = when {
        envKeystore != null -> signingConfigs.create("release") {
            storeFile = file(envKeystore)
            storePassword = providers.environmentVariable("KOGGLES_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("KOGGLES_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("KOGGLES_KEY_PASSWORD").orNull
        }
        keystoreProps.isFile -> {
            val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
            signingConfigs.create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
        else -> {
            logger.warn("keystore.properties not found: release APK is unsigned")
            null
        }
    }

    buildTypes {
        debug {
            // Optional side-by-side install for device experiments (e.g. the preset sweep), so a
            // debug build never has to replace an installed release build: -PkogglesAppIdSuffix=.sweep
            providers.gradleProperty("kogglesAppIdSuffix").orNull?.let { applicationIdSuffix = it }
        }
        release {
            signingConfig = releaseSigning
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("build/generated/bundled") // = bundledAssetsDir
        }
        getByName("androidTest") {
            assets.srcDir("build/generated/androidTestAllowlist") // = androidTestAllowlist
        }
    }

    packaging {
        jniLibs.pickFirsts += "**/libGfxPluginCardboard.so"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    implementation(files(cardboardAar).builtBy(buildCardboardSdk))
    implementation(libs.androidx.appcompat)
    implementation(libs.play.services.vision)
    implementation(libs.material)
    implementation(libs.protobuf.javalite)
    implementation(libs.jspecify)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
