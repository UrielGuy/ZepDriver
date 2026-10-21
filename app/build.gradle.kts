import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.Inflater

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.zepdriver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zepdriver"
        minSdk = 26
        // Google Play requires API 36 for new apps and updates from Aug 31, 2026.
        targetSdk = 36
        // CI run number keeps every build installable as an update of the last one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        // Where the app looks for newer data bundles when it has internet.
        buildConfigField(
            "String", "DATA_BASE_URL",
            "\"https://raw.githubusercontent.com/UrielGuy/ZepDriver/HEAD/app/src/main/assets/playa/\"",
        )
        buildConfigField("int", "DATA_YEAR", "2026")
        // Phones only: dropping the x86 emulator builds of MapLibre's native
        // library roughly halves the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // -Pzep.emulator adds x86_64 for the store-screenshot emulator run.
            if (project.hasProperty("zep.emulator")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        // A shared debug key, committed on purpose: CI builds then install as
        // updates over each other instead of needing an uninstall each time.
        // Not for release builds.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Play upload key: CI decodes the ZEP_UPLOAD_KEYSTORE_B64 secret to a file
        // and passes its path and password in the environment. Google re-signs
        // what Play delivers with the app signing key it holds (Play App Signing).
        val uploadStore = System.getenv("ZEP_UPLOAD_KEYSTORE")?.takeIf { file(it).isFile }
        val uploadPassword = System.getenv("ZEP_UPLOAD_KEY_PASSWORD")
        if (uploadStore != null && uploadPassword != null) {
            create("upload") {
                storeFile = file(uploadStore)
                storeType = "PKCS12"
                storePassword = uploadPassword
                keyAlias = "upload"
                keyPassword = uploadPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Installs next to the Play Store version instead of clashing with it.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Unsigned when the upload key isn't available (local builds, forks).
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/previous-compilation-data.bin")
    }

    // The embargoed year's sealed camp and art data, built in CI from the
    // Burning Man API (tools/data/build_sealed.sh). Never committed.
    sourceSets.getByName("main").assets.srcDir(File(rootProject.layout.buildDirectory.get().asFile, "sealed-assets"))

    // Keep GeoJSON bundles uncompressed so MapLibre can read them quickly.
    androidResources {
        noCompress += listOf("geojson", "pbf", "ogg")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    testImplementation("junit:junit:4.13.2")
}

/**
 * Bundles the pinned Meshtastic firmware image (Heltec V3) into the APK so the
 * radio can be flashed offline. The pin (version, zip entry offset, MD5) lives
 * in core's FirmwareCatalog.kt; this reads it from there so there's one source
 * of truth. Only the image's zip entry (~1.35 MB) is fetched, with an HTTP
 * range request on the release archive. The firmware is GPL-3: a NOTICE with
 * the source location is bundled next to it.
 */
abstract class BundleFirmwareTask : DefaultTask() {
    @get:InputFile
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val src = catalog.get().asFile.readText()
        fun const(name: String) = Regex("""$name\s*=\s*"?([0-9A-Za-z._]+)"?""").find(src)
            ?.groupValues?.get(1)?.replace("_", "") ?: error("$name not found in FirmwareCatalog.kt")
        val version = const("VERSION")
        val offset = const("FACTORY_ENTRY_OFFSET").removeSuffix("L").toLong()
        val compressed = const("FACTORY_ENTRY_COMPRESSED_SIZE").removeSuffix("L").toLong()
        val md5 = const("FACTORY_IMAGE_MD5")
        val imageName = "firmware-heltec-v3-$version.factory.bin"
        val url = "https://github.com/meshtastic/firmware/releases/download/v$version/firmware-esp32s3-$version.zip"

        val dir = outputDir.get().asFile.resolve("firmware").apply { deleteRecursively(); mkdirs() }
        val image = try {
            inflateEntry(fetchRange(url, offset, offset + 30 + 256 + 1024 + compressed), imageName)
        } catch (e: Exception) {
            if (System.getenv("CI") != null) throw GradleException("Couldn't bundle Meshtastic firmware: ${e.message}", e)
            logger.warn("Meshtastic firmware not bundled (${e.message}); the app will download it when flashing.")
            return
        }
        val actual = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
        if (actual != md5) throw GradleException("Firmware MD5 $actual != pinned $md5")
        dir.resolve(imageName).writeBytes(image)
        dir.resolve("NOTICE.txt").writeText(
            """
            Meshtastic firmware $version for the Heltec WiFi LoRa 32 V3
            Copyright Meshtastic contributors. Licensed under the GNU General
            Public License v3.0: https://www.gnu.org/licenses/gpl-3.0.html
            Corresponding source: https://github.com/meshtastic/firmware/tree/v$version
            Binary: $url ($imageName)
            """.trimIndent() + "\n",
        )
        logger.lifecycle("Bundled $imageName (${image.size} bytes)")
    }

    private fun fetchRange(url: String, first: Long, last: Long): ByteArray {
        var target = url
        repeat(5) {
            val c = URI(target).toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.setRequestProperty("Range", "bytes=$first-$last")
            when (val code = c.responseCode) {
                206 -> return c.inputStream.use { it.readBytes() }
                in 300..399 -> target = c.getHeaderField("Location") ?: error("redirect without Location")
                else -> error("HTTP $code for $target")
            }
        }
        error("too many redirects")
    }

    /** Inflates the zip entry whose local header starts at [bytes][0]. */
    private fun inflateEntry(bytes: ByteArray, expectedName: String): ByteArray {
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        check(u16(0) == 0x4b50 && u16(2) == 0x0403) { "not a zip local header" }
        check(u16(8) == 8) { "entry is not deflated" }
        val nameLen = u16(26)
        val name = String(bytes, 30, nameLen)
        check(name == expectedName) { "range holds $name, expected $expectedName" }
        val inflater = Inflater(true)
        val start = 30 + nameLen + u16(28)
        inflater.setInput(bytes, start, bytes.size - start)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) error("truncated entry")
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }
}

val bundleFirmware = tasks.register<BundleFirmwareTask>("bundleFirmware") {
    catalog.set(rootProject.file("core/src/main/kotlin/com/zepdriver/core/flash/FirmwareCatalog.kt"))
    outputDir.set(layout.buildDirectory.dir("generated/firmware"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleFirmware, BundleFirmwareTask::outputDir)
    }
}
