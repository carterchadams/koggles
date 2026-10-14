pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Aptabase's Kotlin SDK is published only through JitPack; nothing else may resolve from there, and the
        // artifact is checksum-pinned in gradle/verification-metadata.xml (JitPack builds from a tag).
        maven("https://jitpack.io") { content { includeGroup("com.github.aptabase") } }
    }
}
rootProject.name = "koggles"
include(":app")
