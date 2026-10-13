buildscript {
    val skipApp = project.providers.gradleProperty("zep.skipApp").isPresent
    repositories {
        if (!skipApp) google()
        maven("https://repo1.maven.org/maven2")
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.0.21")
        if (!skipApp) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
        }
    }
}
