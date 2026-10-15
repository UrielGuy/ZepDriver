import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.test {
    useJUnitPlatform()
    // Tests read the bundled playa data straight from the app's assets.
    systemProperty("zep.assets", rootProject.file("app/src/main/assets").absolutePath)
}

// Dumps every fixed warning phrase for the clip generator (see tools/speech).
tasks.register<JavaExec>("speechCatalog") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.zepdriver.core.alerts.SpeechCatalogMainKt")
    args(
        rootProject.file("app/src/main/assets/playa/2026").absolutePath,
        layout.buildDirectory.file("speech/phrases.json").get().asFile.absolutePath,
    )
}

// Seals an embargoed year's camp and art data into APK assets (see SealMain and
// tools/data/build_sealed.sh). Needs ZEP_BM_API_KEY in the environment.
//   -PsealIn=<bundle dir> -PsealOut=<assets dir> -PsealCerts=<cert>[,<cert>...]
tasks.register<JavaExec>("sealData") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.zepdriver.core.embargo.SealMainKt")
    val certs = (findProperty("sealCerts") as String?)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
    args(listOfNotNull(findProperty("sealIn") as String?, findProperty("sealOut") as String?) + certs)
}
