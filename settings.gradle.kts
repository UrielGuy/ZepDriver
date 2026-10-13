// Pass -Pzep.skipApp=true to build only the pure-Kotlin :core module
// (useful where Google's Maven repository / Android SDK are unavailable).
val skipApp = providers.gradleProperty("zep.skipApp").isPresent

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo1.maven.org/maven2")
    }
}

dependencyResolutionManagement {
    repositories {
        if (!skipApp) google()
        maven("https://repo1.maven.org/maven2")
        if (!skipApp) maven("https://jitpack.io")
    }
}

rootProject.name = "ZepDriver"
include(":core")
if (!skipApp) include(":app")
