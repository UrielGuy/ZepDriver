package com.zepdriver.core.flash

/** Minimal serial port the flasher needs; implemented with usb-serial-for-android on the phone. */
interface SerialPort {
    fun write(bytes: ByteArray)
    /** Reads up to [buffer].size bytes; returns 0 on timeout. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    fun setBaudRate(baud: Int)
    fun setDtr(value: Boolean)
    fun setRts(value: Boolean)
}

/** A binary to write at a flash [offset]. */
class FlashImage(val offset: Int, val data: ByteArray)

class FlashException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * ESP32-S3 ROM bootloader client (the esptool serial protocol): reset into
 * the bootloader, sync, attach SPI flash, write compressed images, verify MD5,
 * reset into the new firmware. Blocking; run on a background thread.
 */
class EspFlasher(
    private val port: SerialPort,
    private val log: (String) -> Unit = {},
) {
    /** [progress] receives 0.0..1.0. Throws [FlashException] on failure. */
    fun flash(images: List<FlashImage>, progress: (Float) -> Unit = {}): Unit = TODO("mesh agent")
}

/** Where to get Meshtastic firmware for the recommended board (Heltec V3). */
object FirmwareCatalog {
    /** Pinned Meshtastic firmware version. */
    val version: String get() = TODO("mesh agent")
    /** URL of the release archive containing the Heltec V3 image. */
    val downloadUrl: String get() = TODO("mesh agent")
    /** Name of the full ("factory") image inside the archive, flashed at offset 0. */
    val factoryImageName: String get() = TODO("mesh agent")
}
