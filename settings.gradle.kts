pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // Termux terminal-view / terminal-emulator (Apache-2.0) are published via JitPack
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app") } }
    }
}
rootProject.name = "pisandbox"
include(":app")
