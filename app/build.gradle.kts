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

android {
    namespace = "dev.koggles"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.koggles"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
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
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
