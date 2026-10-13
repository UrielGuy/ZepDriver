package com.zepdriver.core.flash

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Where to get Meshtastic firmware for the recommended board (Heltec V3).
 *
 * The firmware is GPL-3 and is downloaded at runtime, never bundled in the APK.
 *
 * Pinned: **v2.7.26.54e0d8d**. Evidence (gathered 2026-10-13):
 * - `git ls-remote --tags https://github.com/meshtastic/firmware` lists
 *   v2.7.26.54e0d8d; newer tags (v2.8.0.*, v2.8.1.8e6a88d) exist but
 *   `https://github.com/meshtastic/firmware/releases/latest/download/<x>`
 *   redirects to `releases/download/v2.7.26.54e0d8d/<x>`, i.e. GitHub's
 *   "latest" (non-prerelease) release is 2.7.26: the newer ones are still
 *   alpha pre-releases.
 * - The tag's `.github/workflows/main_matrix.yml` (job release-firmware) zips
 *   all ESP32-S3 boards into one asset per architecture:
 *   `zip -j ... ./firmware-${arch}-${version}.zip ./output` with arch
 *   `esp32s3`; `bin/build-esp32.sh` names images
 *   `firmware-<pio env>-<version>.factory.bin`; the Heltec V3 env is
 *   `heltec-v3` (variants/esp32s3/heltec_v3/platformio.ini).
 * - The release asset answers HTTP 302 → 200 (170,272,165 bytes). Its zip
 *   central directory (read with an HTTP range request) contains
 *   `firmware-heltec-v3-2.7.26.54e0d8d.factory.bin`: 2,174,784 bytes,
 *   CRC-32 1bf218cd, deflated to 1,353,196 bytes, local header at offset
 *   36,544,461. Inflating that entry gives MD5
 *   3c65d69fc52827590dcb7fb830ed55b3, matching the build's
 *   `firmware-heltec-v3-2.7.26.54e0d8d.mt.json` manifest (published in
 *   meshtastic/meshtastic.github.io), which also states partition scheme
 *   "8MB" and hwModel 43 (HELTEC_V3). The image starts with 0xE9 (ESP image
 *   magic) and is bootloader + partition table + app, so it goes at offset 0.
 */
object FirmwareCatalog {
    /** Pinned Meshtastic firmware version. */
    val version: String get() = VERSION
    /** URL of the release archive containing the Heltec V3 image. */
    val downloadUrl: String get() = "https://github.com/meshtastic/firmware/releases/download/v$VERSION/$archiveName"
    /** Name of the full ("factory") image inside the archive, flashed at offset 0. */
    val factoryImageName: String get() = "firmware-heltec-v3-$VERSION.factory.bin"

    private const val VERSION = "2.7.26.54e0d8d"

    val tag: String get() = "v$VERSION"
    val archiveName: String get() = "firmware-esp32s3-$VERSION.zip"
    /** Size of the whole release zip (it holds every ESP32-S3 board: download on Wi-Fi). */
    const val ARCHIVE_SIZE = 170_272_165L

    /** Flash offset of the factory image. */
    const val FLASH_OFFSET = 0
    /** Heltec V3 flash size. */
    const val FLASH_SIZE_BYTES = 8 * 1024 * 1024

    const val FACTORY_IMAGE_SIZE = 2_174_784
    const val FACTORY_IMAGE_MD5 = "3c65d69fc52827590dcb7fb830ed55b3"
    const val FACTORY_IMAGE_CRC32 = 0x1bf218cdL

    /**
     * Saving ~169 MB: the factory image's zip entry can be fetched alone with
     * an HTTP `Range: bytes=FIRST-LAST` request on [downloadUrl] (GitHub's
     * release CDN honours explicit ranges), then decoded with
     * [inflateLocalZipEntry]. The range covers the 30-byte local header, the
     * name, an extra field and the 1,353,196 compressed bytes, with slack.
     */
    const val FACTORY_ENTRY_OFFSET = 36_544_461L
    const val FACTORY_ENTRY_COMPRESSED_SIZE = 1_353_196L
    val factoryEntryRange: LongRange
        get() = FACTORY_ENTRY_OFFSET..(FACTORY_ENTRY_OFFSET + 30 + 256 + 1024 + FACTORY_ENTRY_COMPRESSED_SIZE)

    /** The images to pass to [EspFlasher.flash]. Verifies [factoryImage] first. */
    fun flashImages(factoryImage: ByteArray): List<FlashImage> {
        verifyFactoryImage(factoryImage)
        return listOf(FlashImage(FLASH_OFFSET, factoryImage))
    }

    /** @throws FlashException if [image] isn't the pinned Heltec V3 factory image. */
    fun verifyFactoryImage(image: ByteArray) {
        if (image.size != FACTORY_IMAGE_SIZE) throw FlashException("Firmware image has ${image.size} bytes, expected $FACTORY_IMAGE_SIZE")
        val md5 = EspFlasher.md5Hex(image)
        if (md5 != FACTORY_IMAGE_MD5) throw FlashException("Firmware image is corrupt (MD5 $md5)")
    }

    /** Streams the release zip and returns the verified factory image. */
    fun extractFactoryImage(zip: InputStream): ByteArray {
        ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.substringAfterLast('/') == factoryImageName) {
                    val bytes = z.readBytes()
                    verifyFactoryImage(bytes)
                    return bytes
                }
            }
        }
        throw FlashException("$factoryImageName not found in the firmware archive")
    }

    /**
     * Decodes one zip entry from bytes starting at its local file header (the
     * result of a ranged download of [factoryEntryRange]), checking its CRC.
     */
    fun inflateLocalZipEntry(bytes: ByteArray): Pair<String, ByteArray> {
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        if (bytes.size < 30 || u32(0) != 0x04034b50L) throw FlashException("Not a zip local file header")
        val flags = u16(6)
        val method = u16(8)
        var crc = u32(14)
        val size = u32(22)
        val nameLen = u16(26)
        val extraLen = u16(28)
        if (bytes.size < 30 + nameLen + extraLen) throw FlashException("Truncated zip header")
        val name = String(bytes, 30, nameLen, Charsets.UTF_8)
        val start = 30 + nameLen + extraLen
        val hasDescriptor = flags and 0x08 != 0 // sizes/CRC follow the data instead
        val data = when (method) {
            0 -> {
                if (hasDescriptor) throw FlashException("Unsupported stored zip entry with data descriptor")
                if (start + size > bytes.size) throw FlashException("Truncated zip entry $name")
                bytes.copyOfRange(start, start + size.toInt())
            }
            8 -> {
                val inf = Inflater(true)
                try {
                    inf.setInput(bytes, start, bytes.size - start)
                    val out = java.io.ByteArrayOutputStream(if (hasDescriptor) 64 * 1024 else size.toInt())
                    val buf = ByteArray(64 * 1024)
                    while (!inf.finished()) {
                        val k = inf.inflate(buf)
                        if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        out.write(buf, 0, k)
                    }
                    if (!inf.finished()) throw FlashException("Truncated zip entry $name (${out.size()} bytes)")
                    if (hasDescriptor) {
                        var d = start + inf.bytesRead.toInt()
                        if (d + 4 <= bytes.size && u32(d) == 0x08074b50L) d += 4
                        if (d + 4 > bytes.size) throw FlashException("Truncated zip data descriptor")
                        crc = u32(d)
                    } else if (out.size().toLong() != size) {
                        throw FlashException("Zip entry $name has ${out.size()} bytes, header says $size")
                    }
                    out.toByteArray()
                } finally {
                    inf.end()
                }
            }
            else -> throw FlashException("Unsupported zip compression method $method")
        }
        val c = CRC32().apply { update(data) }.value
        if (c != crc) throw FlashException("CRC mismatch in $name")
        return name to data
    }

    /** Convenience for the ranged download: name check + image verification. */
    fun factoryImageFromRange(bytes: ByteArray): ByteArray {
        val (name, data) = inflateLocalZipEntry(bytes)
        if (name != factoryImageName) throw FlashException("Downloaded range holds $name, not $factoryImageName")
        verifyFactoryImage(data)
        return data
    }

    internal fun extractFactoryImage(zip: ByteArray) = extractFactoryImage(ByteArrayInputStream(zip))
}
