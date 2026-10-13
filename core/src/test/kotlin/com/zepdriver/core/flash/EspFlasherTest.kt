package com.zepdriver.core.flash

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EspFlasherTest {
    private val noSleep: (Long) -> Unit = {}

    /** Firmware-like test image: compressible header + random body, odd length. */
    private fun image(size: Int, seed: Int = 1): ByteArray {
        val r = Random(seed)
        return ByteArray(size) { i -> if (i < size / 3) (i % 251).toByte() else if (i % 5 == 0) r.nextInt().toByte() else 0 }
    }

    @Test
    fun flashesImageByteExactAtOffset() {
        val rom = FakeEsp32S3Rom()
        val img = image(300_001)
        val progress = mutableListOf<Float>()
        val log = mutableListOf<String>()
        EspFlasher(rom, { log += it }, sleep = noSleep).flash(listOf(FlashImage(0x10000, img))) { progress += it }

        val padded = (img.size + 3) / 4 * 4
        assertContentEquals(img, rom.flash.copyOfRange(0x10000, 0x10000 + img.size))
        // esptool-style 0xFF padding to a 4-byte multiple.
        assertTrue(rom.flash.copyOfRange(0x10000 + img.size, 0x10000 + padded).all { it == 0xFF.toByte() })
        // Untouched flash before the image keeps the old contents.
        assertEquals(0x5A, rom.flash[0xFFFF].toInt())
        assertEquals(8 * 1024 * 1024, rom.reportedFlashSize)
        assertEquals(EspFlasher.ROM_BLOCK_SIZE, rom.blockSizeSeen)
        assertTrue(921_600 in rom.bauds) // transfer at the fast rate
        assertEquals(115_200, rom.baud) // port left at the Meshtastic API rate after the reset
        assertEquals(1, rom.hardResets)
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(EspFlasher.OP_SPI_FLASH_MD5 in rom.commands)
        assertTrue(EspFlasher.OP_FLASH_DEFL_END !in rom.commands) // ROM: skipped, hard reset instead
        assertTrue(log.any { it.startsWith("Verified") })
    }

    @Test
    fun flashesSeveralImagesAndRetriesBadBlock() {
        val rom = FakeEsp32S3Rom(failBlockOnce = 3)
        val boot = image(20_000, 2)
        val app = image(150_000, 3)
        EspFlasher(rom, options = FlashOptions(fastBaud = null), sleep = noSleep)
            .flash(listOf(FlashImage(0x10000, app), FlashImage(0x0, boot)))
        assertContentEquals(boot, rom.flash.copyOfRange(0, boot.size))
        assertContentEquals(app, rom.flash.copyOfRange(0x10000, 0x10000 + app.size))
        assertEquals(115_200, rom.baud)
        assertTrue(EspFlasher.OP_CHANGE_BAUDRATE !in rom.commands)
    }

    @Test
    fun fullSizeFactoryImageAtZero() {
        // Same size as the pinned Heltec V3 factory image.
        val rom = FakeEsp32S3Rom()
        val img = image(FirmwareCatalog.FACTORY_IMAGE_SIZE, 4)
        EspFlasher(rom, sleep = noSleep).flash(listOf(FlashImage(0, img)))
        assertContentEquals(img, rom.flash.copyOf(img.size))
    }

    @Test
    fun oldRomWithoutSecurityInfoUsesMagicRegister() {
        val rom = FakeEsp32S3Rom(chipId = null, magic = 0x9)
        EspFlasher(rom, sleep = noSleep).flash(listOf(FlashImage(0, image(5000))))
        assertTrue(EspFlasher.OP_READ_REG in rom.commands)
    }

    @Test
    fun rejectsOtherChips() {
        val c3 = assertFailsWith<FlashException> {
            EspFlasher(FakeEsp32S3Rom(chipId = 5), sleep = noSleep).flash(listOf(FlashImage(0, image(100))))
        }
        assertTrue("ESP32-C3" in c3.message!!, c3.message)
        val esp32 = assertFailsWith<FlashException> {
            EspFlasher(FakeEsp32S3Rom(chipId = null, magic = 0x00F01D83), sleep = noSleep).flash(listOf(FlashImage(0, image(100))))
        }
        assertTrue("an ESP32," in esp32.message!!, esp32.message)
    }

    @Test
    fun noBootloaderGivesClearError() {
        val rom = FakeEsp32S3Rom(autoResetWorks = false)
        val e = assertFailsWith<FlashException> {
            EspFlasher(rom, options = FlashOptions(connectAttempts = 2), sleep = noSleep).flash(listOf(FlashImage(0, image(100))))
        }
        assertEquals("No response from bootloader — hold BOOT and tap RST, then retry", e.message)
    }

    @Test
    fun rejectsBadImages() {
        val f = EspFlasher(FakeEsp32S3Rom(), sleep = noSleep)
        assertFailsWith<FlashException> { f.flash(listOf(FlashImage(0x100, image(10)))) } // not 4K aligned
        assertFailsWith<FlashException> { f.flash(listOf(FlashImage(0x7FF000, image(0x2000)))) } // past 8 MB
        assertFailsWith<FlashException> { f.flash(listOf(FlashImage(0, image(0x2000)), FlashImage(0x1000, image(10)))) } // overlap
    }

    @Test
    fun slipRoundTripAndChecksum() {
        val pkt = byteArrayOf(0x00, 0xC0.toByte(), 0xDB.toByte(), 0x01, 0xC0.toByte())
        val enc = Slip.encode(pkt)
        assertEquals("c0 00 db dc db dd 01 db dc c0", enc.joinToString(" ") { "%02x".format(it) })
        val dec = Slip.Decoder()
        val frames = dec.feed("boot text".toByteArray() + enc.copyOf(4)) + dec.feed(enc.copyOfRange(4, enc.size) + enc)
        assertEquals(2, frames.size)
        frames.forEach { assertContentEquals(pkt, it) }
        assertEquals(0xEF xor 0x01 xor 0x02, EspFlasher.checksum(byteArrayOf(1, 2)))
        assertEquals(3_000, EspFlasher.timeoutPerMb(40_000, 1000))
        assertEquals(40_000, EspFlasher.timeoutPerMb(40_000, 1_000_000))
    }

    @Test
    fun firmwareCatalog() {
        assertEquals("2.7.26.54e0d8d", FirmwareCatalog.version)
        assertEquals(
            "https://github.com/meshtastic/firmware/releases/download/v2.7.26.54e0d8d/firmware-esp32s3-2.7.26.54e0d8d.zip",
            FirmwareCatalog.downloadUrl,
        )
        assertEquals("firmware-heltec-v3-2.7.26.54e0d8d.factory.bin", FirmwareCatalog.factoryImageName)
        assertEquals(0, FirmwareCatalog.FLASH_OFFSET)
        assertFailsWith<FlashException> { FirmwareCatalog.verifyFactoryImage(ByteArray(10)) }
        assertFailsWith<FlashException> { FirmwareCatalog.verifyFactoryImage(ByteArray(FirmwareCatalog.FACTORY_IMAGE_SIZE)) }
    }

    @Test
    fun zipEntryHelpers() {
        val content = image(50_000, 9)
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("firmware-other-board.factory.bin")); z.write(ByteArray(100)); z.closeEntry()
                z.putNextEntry(ZipEntry("thing.bin")); z.write(content); z.closeEntry()
            }
        }.toByteArray()
        // Ranged download path: bytes from the entry's local header onwards.
        val off = indexOf(zip, "PK\u0003\u0004thing".length, "thing.bin".toByteArray()) - 30
        val (name, data) = FirmwareCatalog.inflateLocalZipEntry(zip.copyOfRange(off, zip.size))
        assertEquals("thing.bin", name)
        assertContentEquals(content, data)
        // The real factory image isn't in this archive.
        val e = assertFailsWith<FlashException> { FirmwareCatalog.extractFactoryImage(zip) }
        assertTrue("not found" in e.message!!)
        assertEquals(CRC32().apply { update(content) }.value, CRC32().apply { update(data) }.value)
    }

    private fun indexOf(hay: ByteArray, from: Int, needle: ByteArray): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
