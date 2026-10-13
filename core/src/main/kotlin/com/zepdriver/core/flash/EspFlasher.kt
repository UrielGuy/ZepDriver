package com.zepdriver.core.flash

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

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

/** How to get the chip into its ROM bootloader. */
enum class ResetMode {
    /**
     * USB-UART bridge with the usual two-transistor auto-reset circuit
     * (CP210x on the Heltec V3, CH340, FTDI): RTS drives EN, DTR drives IO0.
     */
    CLASSIC,
    /** The ESP32-S3's built-in USB-Serial/JTAG port (USB VID 0x303A, PID 0x1001). */
    USB_JTAG_SERIAL,
    /** Don't touch DTR/RTS: the user holds BOOT and taps RST. */
    NONE,
}

class FlashOptions(
    val resetMode: ResetMode = ResetMode.CLASSIC,
    /** SPI flash size; 8 MB on the Heltec WiFi LoRa 32 V3. */
    val flashSizeBytes: Int = 8 * 1024 * 1024,
    /** Baud rate the ROM bootloader starts at. */
    val initialBaud: Int = 115_200,
    /** Switch to this baud rate for the transfer (null = stay at [initialBaud]). CP2102 handles 921600. */
    val fastBaud: Int? = 921_600,
    /** Reset-into-bootloader attempts (each followed by several SYNCs). */
    val connectAttempts: Int = 4,
    /** Verify every image with SPI_FLASH_MD5 after writing. */
    val verify: Boolean = true,
    /** Pulse EN after flashing so the new firmware boots. */
    val resetAfter: Boolean = true,
)

/**
 * ESP32-S3 ROM bootloader client (the esptool serial protocol): reset into
 * the bootloader, sync, attach SPI flash, write compressed images, verify MD5,
 * reset into the new firmware. Blocking; run on a background thread.
 *
 * Protocol notes (checked against esptool's loader.py):
 * - Packets are SLIP framed (0xC0 delimiters, 0xDB 0xDC / 0xDB 0xDD escapes).
 * - Command: `00 op len16 checksum32 data` (little endian). The checksum is
 *   only meaningful for data commands: 0xEF xor every byte of the payload.
 * - Response: `01 op len16 value32 data`; `data` ends with status bytes,
 *   positioned right after any command-specific result. The ESP32-S3 ROM
 *   sends 4 status bytes (status, error, 2 reserved); the stub sends 2. Only
 *   the first two matter, so we read them at `data[resultLen]`.
 * - No stub loader is uploaded; we talk to the ROM directly. ROM consequences:
 *   compressed block size 0x400 (esptool's ROM FLASH_WRITE_SIZE; 0x4000 is
 *   the stub's), FLASH_DEFL_BEGIN erases up front (timeout 40 s/MB) and takes
 *   a 5th "encrypted" word on chips newer than ESP32, CHANGE_BAUDRATE's
 *   second word is 0, SPI_FLASH_MD5 returns 32 ASCII hex chars (the stub
 *   returns 16 raw bytes), and FLASH_DEFL_END is *not* sent (on the ROM it
 *   exits the loader; esptool skips it too) — we hard-reset instead.
 */
class EspFlasher(
    private val port: SerialPort,
    private val log: (String) -> Unit = {},
    private val options: FlashOptions = FlashOptions(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val rx = ByteArray(1024)
    private var rxLen = 0
    private var rxPos = 0

    /** [progress] receives 0.0..1.0. Throws [FlashException] on failure. */
    fun flash(images: List<FlashImage>, progress: (Float) -> Unit = {}) {
        require(images.isNotEmpty()) { "nothing to flash" }
        val prepared = images.sortedBy { it.offset }.map { prepare(it) }
        prepared.zipWithNext().forEach { (a, b) ->
            if (a.offset + a.data.size > b.offset) throw FlashException("Images overlap at 0x%x".format(b.offset))
        }
        progress(0f)
        connect()
        detectChip()
        checkCommand("attach SPI flash", OP_SPI_ATTACH, ByteArray(8))
        checkCommand("set SPI flash parameters", OP_SPI_SET_PARAMS, le32(0, options.flashSizeBytes, 64 * 1024, 4 * 1024, 256, 0xFFFF))
        options.fastBaud?.takeIf { it != options.initialBaud }?.let { changeBaud(it) }

        val totalBlocks = prepared.sumOf { it.blocks }.coerceAtLeast(1)
        var doneBlocks = 0
        for (img in prepared) {
            writeImage(img) { doneBlocks++; progress(doneBlocks.toFloat() / totalBlocks * 0.98f) }
            if (options.verify) verify(img)
        }
        if (options.resetAfter) hardReset()
        progress(1f)
        log("Flash complete")
    }

    // -----------------------------------------------------------------------------------------
    // Connection

    private fun connect() {
        port.setBaudRate(options.initialBaud)
        val attempts = if (options.resetMode == ResetMode.NONE) 1 else options.connectAttempts
        for (attempt in 0 until attempts) {
            // Alternate esptool's default (50 ms) and extended (550 ms) EN-release delays.
            val delay = if (attempt % 2 == 0) RESET_DELAY_MS else RESET_DELAY_MS + 500
            when (options.resetMode) {
                ResetMode.CLASSIC -> classicReset(delay)
                ResetMode.USB_JTAG_SERIAL -> usbJtagReset()
                ResetMode.NONE -> Unit
            }
            for (i in 0 until SYNC_TRIES) {
                purgeInput()
                if (trySync()) {
                    log("Connected to ROM bootloader")
                    return
                }
                sleep(50)
            }
            log("No sync (attempt ${attempt + 1})")
        }
        throw FlashException("No response from bootloader — hold BOOT and tap RST, then retry")
    }

    /** DTR = IO0, RTS = EN (both inverted through the board's auto-reset transistors). */
    private fun classicReset(delayMs: Long) {
        port.setDtr(false)  // IO0 high
        port.setRts(true)   // EN low: chip in reset
        sleep(100)
        port.setDtr(true)   // IO0 low
        port.setRts(false)  // EN high: chip boots, samples IO0 = download mode
        sleep(delayMs)
        port.setDtr(false)  // release IO0
    }

    private fun usbJtagReset() {
        port.setRts(false)
        port.setDtr(false)  // idle
        sleep(100)
        port.setDtr(true)   // IO0
        port.setRts(false)
        sleep(100)
        port.setRts(true)   // reset; set in this order to pass through (1,1) not (0,0)
        port.setDtr(false)
        port.setRts(true)
        sleep(100)
        port.setDtr(false)
        port.setRts(false)  // out of reset
    }

    private fun hardReset() {
        log("Resetting into new firmware")
        port.setDtr(false)
        port.setRts(true)
        if (options.resetMode == ResetMode.USB_JTAG_SERIAL) {
            sleep(200); port.setRts(false); sleep(200)
        } else {
            sleep(100); port.setRts(false)
        }
        port.setBaudRate(options.initialBaud)
    }

    private fun trySync(): Boolean {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        return try {
            command(OP_SYNC, payload, 0, SYNC_TIMEOUT_MS)
            // The ROM answers a SYNC 8 times; the extras are skipped by op matching later.
            true
        } catch (e: FlashException) {
            false
        }
    }

    private fun detectChip() {
        val info = try {
            checkCommand("get security info", OP_GET_SECURITY_INFO, ByteArray(0), resultLen = 20)
        } catch (e: FlashException) {
            null
        }
        if (info != null) {
            val flags = u32(info, 0)
            val chipId = u32(info, 12).toInt()
            if (chipId != CHIP_ID_ESP32S3) {
                throw FlashException("This board is an ${CHIP_IDS[chipId] ?: "unknown chip (id $chipId)"}, not an ESP32-S3 (Heltec V3)")
            }
            if (flags and SECURE_DOWNLOAD_FLAG != 0L) throw FlashException("The chip is in secure download mode and can't be flashed")
            log("Chip is ESP32-S3")
            return
        }
        // Older ROMs (ESP32, ESP32-S2, ESP8266) lack GET_SECURITY_INFO: identify by magic register.
        val magic = checkCommandValue("read chip magic", OP_READ_REG, le32(CHIP_DETECT_MAGIC_REG))
        if (magic != MAGIC_ESP32S3) {
            throw FlashException("This board is an ${MAGICS[magic] ?: "unknown chip (magic 0x%08x)".format(magic)}, not an ESP32-S3 (Heltec V3)")
        }
        log("Chip is ESP32-S3")
    }

    private fun changeBaud(baud: Int) {
        command(OP_CHANGE_BAUDRATE, le32(baud, 0), 0, DEFAULT_TIMEOUT_MS) // reply comes at the old rate
        port.setBaudRate(baud)
        sleep(50)
        purgeInput()
        log("Baud rate $baud")
    }

    // -----------------------------------------------------------------------------------------
    // Writing

    private class Prepared(val offset: Int, val data: ByteArray, val compressed: ByteArray) {
        val blocks get() = (compressed.size + ROM_BLOCK_SIZE - 1) / ROM_BLOCK_SIZE
    }

    private fun prepare(img: FlashImage): Prepared {
        if (img.offset < 0 || img.offset % SECTOR_SIZE != 0) throw FlashException("Flash offset 0x%x is not 4 KB aligned".format(img.offset))
        if (img.data.isEmpty()) throw FlashException("Empty image for 0x%x".format(img.offset))
        // esptool pads to a 4-byte multiple with 0xFF (erased flash).
        val padded = if (img.data.size % 4 == 0) img.data else img.data.copyOf((img.data.size + 3) / 4 * 4).also {
            it.fill(0xFF.toByte(), img.data.size, it.size)
        }
        if (img.offset.toLong() + padded.size > options.flashSizeBytes) {
            throw FlashException("Image at 0x%x (%d bytes) doesn't fit in %d MB flash".format(img.offset, padded.size, options.flashSizeBytes shr 20))
        }
        return Prepared(img.offset, padded, deflate(padded))
    }

    private fun writeImage(img: Prepared, onBlock: () -> Unit) {
        val eraseSize = (img.data.size + ROM_BLOCK_SIZE - 1) / ROM_BLOCK_SIZE * ROM_BLOCK_SIZE
        log("Writing ${img.data.size} bytes (${img.compressed.size} compressed) at 0x%x".format(img.offset))
        checkCommand(
            "start flashing (erase)", OP_FLASH_DEFL_BEGIN,
            le32(eraseSize, img.blocks, ROM_BLOCK_SIZE, img.offset, 0),
            timeoutMs = timeoutPerMb(ERASE_TIMEOUT_PER_MB_MS, eraseSize),
        )
        val inflater = Inflater()
        val scratch = ByteArray(64 * 1024)
        try {
            for (seq in 0 until img.blocks) {
                val from = seq * ROM_BLOCK_SIZE
                val block = img.compressed.copyOfRange(from, minOf(from + ROM_BLOCK_SIZE, img.compressed.size))
                // The ROM writes each block to flash before ACKing: scale the timeout by its uncompressed size.
                inflater.setInput(block)
                var unc = 0
                while (true) {
                    val n = inflater.inflate(scratch)
                    unc += n
                    if (n == 0) break
                }
                val timeout = timeoutPerMb(ERASE_WRITE_TIMEOUT_PER_MB_MS, unc)
                val packet = le32(block.size, seq, 0, 0) + block
                var attempt = 0
                while (true) {
                    try {
                        checkCommand("write block $seq", OP_FLASH_DEFL_DATA, packet, checksum(block), timeoutMs = timeout)
                        break
                    } catch (e: FlashException) {
                        if (++attempt >= WRITE_BLOCK_ATTEMPTS) throw e
                        log("Retrying block $seq: ${e.message}")
                    }
                }
                onBlock()
            }
        } finally {
            inflater.end()
        }
    }

    private fun verify(img: Prepared) {
        val result = checkCommand(
            "verify (MD5)", OP_SPI_FLASH_MD5, le32(img.offset, img.data.size, 0, 0),
            resultLen = 32, timeoutMs = timeoutPerMb(MD5_TIMEOUT_PER_MB_MS, img.data.size),
        )
        val got = String(result, Charsets.US_ASCII).lowercase()
        val want = md5Hex(img.data)
        if (got != want) throw FlashException("Verification failed at 0x%x: flash MD5 %s, expected %s".format(img.offset, got, want))
        log("Verified 0x%x (MD5 %s)".format(img.offset, want))
    }

    // -----------------------------------------------------------------------------------------
    // Commands

    private class Response(val value: Long, val data: ByteArray)

    private fun checkCommand(
        what: String,
        op: Int,
        data: ByteArray,
        chk: Int = 0,
        resultLen: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ByteArray = checkResponse(what, command(op, data, chk, timeoutMs), resultLen).data.copyOf(resultLen)

    private fun checkCommandValue(what: String, op: Int, data: ByteArray): Long =
        checkResponse(what, command(op, data, 0, DEFAULT_TIMEOUT_MS), 0).value

    private fun checkResponse(what: String, r: Response, resultLen: Int): Response {
        if (r.data.size < resultLen + 2) {
            if (r.data.size >= 2 && r.data[0].toInt() != 0) throw statusError(what, r.data[1].toInt() and 0xFF)
            throw FlashException("Failed to $what: short response (${r.data.size} bytes)")
        }
        if (r.data[resultLen].toInt() != 0) throw statusError(what, r.data[resultLen + 1].toInt() and 0xFF)
        return r
    }

    private fun statusError(what: String, code: Int) =
        FlashException("Failed to $what: ${ROM_ERRORS[code] ?: "error 0x%02x".format(code)}")

    private fun command(op: Int, data: ByteArray, chk: Int, timeoutMs: Long): Response {
        val pkt = ByteArray(8 + data.size)
        pkt[0] = 0
        pkt[1] = op.toByte()
        pkt[2] = data.size.toByte()
        pkt[3] = (data.size shr 8).toByte()
        for (i in 0 until 4) pkt[4 + i] = (chk ushr (8 * i)).toByte()
        data.copyInto(pkt, 8)
        port.write(Slip.encode(pkt))

        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val frame = readFrame(deadline) ?: throw FlashException("Timed out waiting for the bootloader (op 0x%02x)".format(op))
            if (frame.size < 8 || frame[0].toInt() != 1) continue
            val rOp = frame[1].toInt() and 0xFF
            val body = frame.copyOfRange(8, frame.size)
            if (rOp == op) return Response(u32(frame, 4), body)
            if (body.size >= 2 && body[0].toInt() != 0 && (body[1].toInt() and 0xFF) == ROM_INVALID_RECV_MSG) {
                throw FlashException("Bootloader rejected command 0x%02x".format(op))
            }
        }
    }

    /** Next complete SLIP frame, or null at [deadlineNanos]. Bytes outside frames (boot log) are skipped. */
    private fun readFrame(deadlineNanos: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        var inFrame = false
        var escape = false
        while (true) {
            val b = readByte(deadlineNanos) ?: return null
            if (!inFrame) {
                if (b == 0xC0) inFrame = true
                continue
            }
            when {
                escape -> {
                    escape = false
                    when (b) {
                        0xDC -> out.write(0xC0)
                        0xDD -> out.write(0xDB)
                        else -> { out.reset(); inFrame = b == 0xC0 } // invalid escape: drop frame
                    }
                }
                b == 0xDB -> escape = true
                b == 0xC0 -> {
                    if (out.size() > 0) return out.toByteArray()
                    // Empty frame: this 0xC0 starts the next one.
                }
                else -> out.write(b)
            }
        }
    }

    private fun readByte(deadlineNanos: Long): Int? {
        while (rxPos >= rxLen) {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return null
            rxLen = port.read(rx, remainingMs.coerceIn(1, 100).toInt())
            rxPos = 0
        }
        return rx[rxPos++].toInt() and 0xFF
    }

    private fun purgeInput() {
        rxPos = 0
        rxLen = 0
        val tmp = ByteArray(1024)
        val deadline = System.nanoTime() + 200_000_000
        while (System.nanoTime() < deadline && port.read(tmp, 5) > 0) Unit
    }

    companion object {
        const val OP_FLASH_BEGIN = 0x02
        const val OP_FLASH_DATA = 0x03
        const val OP_FLASH_END = 0x04
        const val OP_SYNC = 0x08
        const val OP_WRITE_REG = 0x09
        const val OP_READ_REG = 0x0A
        const val OP_SPI_SET_PARAMS = 0x0B
        const val OP_SPI_ATTACH = 0x0D
        const val OP_CHANGE_BAUDRATE = 0x0F
        const val OP_FLASH_DEFL_BEGIN = 0x10
        const val OP_FLASH_DEFL_DATA = 0x11
        const val OP_FLASH_DEFL_END = 0x12
        const val OP_SPI_FLASH_MD5 = 0x13
        const val OP_GET_SECURITY_INFO = 0x14

        const val CHECKSUM_MAGIC = 0xEF
        const val ROM_INVALID_RECV_MSG = 0x05
        /** Compressed bytes per FLASH_DEFL_DATA when talking to the ROM loader. */
        const val ROM_BLOCK_SIZE = 0x400
        const val SECTOR_SIZE = 0x1000
        const val CHIP_DETECT_MAGIC_REG = 0x40001000
        const val CHIP_ID_ESP32S3 = 9
        const val MAGIC_ESP32S3 = 0x9L
        private const val SECURE_DOWNLOAD_FLAG = 1L shl 2

        private const val RESET_DELAY_MS = 50L
        private const val SYNC_TRIES = 5
        private const val SYNC_TIMEOUT_MS = 100L
        private const val DEFAULT_TIMEOUT_MS = 3_000L
        private const val ERASE_TIMEOUT_PER_MB_MS = 40_000L
        private const val ERASE_WRITE_TIMEOUT_PER_MB_MS = 40_000L
        private const val MD5_TIMEOUT_PER_MB_MS = 8_000L
        private const val WRITE_BLOCK_ATTEMPTS = 3

        private val CHIP_IDS = mapOf(
            0 to "ESP32", 2 to "ESP32-S2", 5 to "ESP32-C3", 9 to "ESP32-S3", 12 to "ESP32-C2",
            13 to "ESP32-C6", 16 to "ESP32-H2", 18 to "ESP32-P4", 20 to "ESP32-C61", 23 to "ESP32-C5",
        )
        private val MAGICS = mapOf(
            0x00F01D83L to "ESP32", 0x000007C6L to "ESP32-S2", 0xFFF0C101L to "ESP8266",
        )
        private val ROM_ERRORS = mapOf(
            0x05 to "invalid message", 0x06 to "failed to act on message", 0x07 to "invalid checksum",
            0x08 to "flash write error", 0x09 to "flash read error", 0x0A to "flash read length error",
            0x0B to "deflate error",
        )

        /** Timeout scaled like esptool's timeout_per_mb (never below the 3 s default). */
        fun timeoutPerMb(perMbMs: Long, bytes: Int): Long = maxOf(DEFAULT_TIMEOUT_MS, (perMbMs * (bytes / 1e6)).toLong())

        fun checksum(data: ByteArray): Int {
            var c = CHECKSUM_MAGIC
            for (b in data) c = c xor (b.toInt() and 0xFF)
            return c
        }

        /** zlib stream (with header), as esptool's `zlib.compress(image, 9)`. */
        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION)
            try {
                d.setInput(data)
                d.finish()
                val out = ByteArrayOutputStream(data.size / 2 + 64)
                val buf = ByteArray(64 * 1024)
                while (!d.finished()) out.write(buf, 0, d.deflate(buf))
                return out.toByteArray()
            } finally {
                d.end()
            }
        }

        fun md5Hex(data: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

        internal fun le32(vararg values: Int): ByteArray {
            val out = ByteArray(values.size * 4)
            values.forEachIndexed { i, v -> for (k in 0 until 4) out[i * 4 + k] = (v ushr (8 * k)).toByte() }
            return out
        }

        internal fun u32(b: ByteArray, at: Int): Long =
            (0 until 4).fold(0L) { acc, k -> acc or ((b[at + k].toLong() and 0xFF) shl (8 * k)) }
    }
}

/** SLIP framing as used by the ESP ROM loader. */
object Slip {
    const val END = 0xC0
    const val ESC = 0xDB
    const val ESC_END = 0xDC
    const val ESC_ESC = 0xDD

    fun encode(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(packet.size + 8)
        out.write(END)
        for (b in packet) {
            when (b.toInt() and 0xFF) {
                END -> { out.write(ESC); out.write(ESC_END) }
                ESC -> { out.write(ESC); out.write(ESC_ESC) }
                else -> out.write(b.toInt())
            }
        }
        out.write(END)
        return out.toByteArray()
    }

    /** Incremental decoder; bytes outside frames are ignored. */
    class Decoder {
        private val buf = ByteArrayOutputStream()
        private var inFrame = false
        private var escape = false

        fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
            val frames = ArrayList<ByteArray>()
            for (i in 0 until length) {
                val b = bytes[i].toInt() and 0xFF
                if (!inFrame) {
                    if (b == END) inFrame = true
                    continue
                }
                when {
                    escape -> {
                        escape = false
                        when (b) {
                            ESC_END -> buf.write(END)
                            ESC_ESC -> buf.write(ESC)
                            else -> { buf.reset(); inFrame = b == END }
                        }
                    }
                    b == ESC -> escape = true
                    // END closes a frame; an END right after an opening END starts it afresh.
                    b == END -> if (buf.size() > 0) { frames += buf.toByteArray(); buf.reset(); inFrame = false }
                    else -> buf.write(b)
                }
            }
            return frames
        }
    }
}
