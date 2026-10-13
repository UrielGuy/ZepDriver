package com.zepdriver.core.flash

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.Inflater

/**
 * Simulated ESP32-S3 ROM bootloader behind a CP2102-style auto-reset circuit,
 * implementing [SerialPort]. It decodes SLIP command packets, validates
 * lengths, checksums and sequence numbers, inflates FLASH_DEFL_DATA into a
 * simulated flash chip and answers SPI_FLASH_MD5 like the real ROM (32 hex
 * characters followed by 4 status bytes).
 */
class FakeEsp32S3Rom(
    val flashSize: Int = 8 * 1024 * 1024,
    /** Chip id returned by GET_SECURITY_INFO (9 = ESP32-S3), or null to act like an old ROM without the command. */
    private val chipId: Int? = 9,
    /** Value of the chip-detect magic register (used when [chipId] is null). */
    private val magic: Int = 0x9,
    /** If false the reset circuit doesn't work (user must press BOOT): the chip never enters download mode. */
    private val autoResetWorks: Boolean = true,
    /** Corrupt the checksum check of this FLASH_DEFL_DATA sequence number once, to exercise retries. */
    private var failBlockOnce: Int? = null,
) : SerialPort {
    val flash = ByteArray(flashSize) { 0x5A } // "old firmware"
    val commands = mutableListOf<Int>()
    var baud = 115_200
        private set
    var inDownloadMode = false
        private set
    var hardResets = 0
        private set
    var spiAttached = false
        private set
    var reportedFlashSize = 0
        private set
    var blockSizeSeen = 0
        private set

    private var dtr = false
    private var rts = false
    private var rxBaud = 115_200 // baud the chip's UART currently uses
    private val toHost = ArrayDeque<Byte>()
    private val decoder = Slip.Decoder()

    // FLASH_DEFL state
    private var deflOffset = 0
    private var deflWritten = 0
    private var deflEraseSize = 0
    private var deflBlocks = 0
    private var nextSeq = 0
    private var inflater: Inflater? = null

    val bauds = mutableListOf<Int>()

    override fun setBaudRate(baud: Int) { this.baud = baud; bauds += baud }

    override fun setDtr(value: Boolean) { dtr = value }

    override fun setRts(value: Boolean) {
        val wasReset = rts
        rts = value
        if (wasReset && !value) {
            // EN released: the chip boots, sampling IO0 (low when DTR is asserted).
            rxBaud = 115_200
            inDownloadMode = dtr && autoResetWorks
            if (inDownloadMode) {
                emitText("ESP-ROM:esp32s3-20210327\r\nrst:0x1 (POWERON),boot:0x0 (DOWNLOAD(USB/UART0))\r\nwaiting for download\r\n")
            } else {
                hardResets++
                emitText("ESP-ROM:esp32s3-20210327\r\nrst:0x1 (POWERON),boot:0x8 (SPI_FAST_FLASH_BOOT)\r\n")
            }
        }
    }

    override fun write(bytes: ByteArray) {
        if (!inDownloadMode) return
        if (baud != rxBaud) return // garbled at the wrong baud rate
        for (frame in decoder.feed(bytes)) handle(frame)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buffer.size && toHost.isNotEmpty()) buffer[n++] = toHost.removeFirst()
        return n
    }

    private fun emitText(s: String) = s.toByteArray().forEach { toHost.addLast(it) }

    private fun respond(op: Int, value: Long = 0, data: ByteArray = ByteArray(0), status: Int = 0, error: Int = 0) {
        val body = data + byteArrayOf(status.toByte(), error.toByte(), 0, 0) // ESP32-S3 ROM: 4 status bytes
        val pkt = ByteArray(8 + body.size)
        pkt[0] = 1
        pkt[1] = op.toByte()
        pkt[2] = body.size.toByte()
        pkt[3] = (body.size shr 8).toByte()
        for (i in 0 until 4) pkt[4 + i] = (value ushr (8 * i)).toByte()
        body.copyInto(pkt, 8)
        Slip.encode(pkt).forEach { toHost.addLast(it) }
    }

    private fun fail(op: Int, error: Int) = respond(op, status = 1, error = error)

    private fun u32(b: ByteArray, at: Int): Long = EspFlasher.u32(b, at)

    private fun handle(frame: ByteArray) {
        if (frame.size < 8 || frame[0].toInt() != 0) return
        val op = frame[1].toInt() and 0xFF
        val len = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        val chk = u32(frame, 4).toInt()
        val data = frame.copyOfRange(8, frame.size)
        commands += op
        if (len != data.size) return fail(op, 0x05)
        when (op) {
            EspFlasher.OP_SYNC -> {
                val expected = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
                if (!data.contentEquals(expected)) return
                repeat(8) { respond(op, value = 0x07071220) } // ROM answers 8 times, non-zero value
            }
            EspFlasher.OP_GET_SECURITY_INFO -> {
                if (chipId == null) return fail(op, 0x05)
                respond(op, data = EspFlasher.le32(0) + ByteArray(8) + EspFlasher.le32(chipId, 0))
            }
            EspFlasher.OP_READ_REG -> {
                if (u32(data, 0) != EspFlasher.CHIP_DETECT_MAGIC_REG.toLong()) return fail(op, 0x05)
                respond(op, value = magic.toLong() and 0xFFFFFFFFL)
            }
            EspFlasher.OP_SPI_ATTACH -> {
                if (data.size != 8) return fail(op, 0x05)
                spiAttached = true
                respond(op)
            }
            EspFlasher.OP_SPI_SET_PARAMS -> {
                if (data.size != 24) return fail(op, 0x05)
                reportedFlashSize = u32(data, 4).toInt()
                respond(op)
            }
            EspFlasher.OP_CHANGE_BAUDRATE -> {
                if (data.size != 8 || u32(data, 4) != 0L) return fail(op, 0x05) // ROM: second word must be 0
                respond(op) // sent at the old rate
                rxBaud = u32(data, 0).toInt()
            }
            EspFlasher.OP_FLASH_DEFL_BEGIN -> {
                if (!spiAttached) return fail(op, 0x06)
                if (data.size != 20) return fail(op, 0x05) // ESP32-S3 ROM takes the 5th (encrypted) word
                val eraseSize = u32(data, 0).toInt()
                val blocks = u32(data, 4).toInt()
                val blockSize = u32(data, 8).toInt()
                val offset = u32(data, 12).toInt()
                if (offset % 0x1000 != 0 || offset.toLong() + eraseSize > flashSize) return fail(op, 0x05)
                blockSizeSeen = blockSize
                flash.fill(0xFF.toByte(), offset, offset + ((eraseSize + 0xFFF) / 0x1000 * 0x1000).coerceAtMost(flashSize - offset))
                deflOffset = offset
                deflWritten = 0
                deflEraseSize = eraseSize
                deflBlocks = blocks
                nextSeq = 0
                inflater?.end()
                inflater = Inflater() // zlib stream with header
                respond(op)
            }
            EspFlasher.OP_FLASH_DEFL_DATA -> {
                val inf = inflater ?: return fail(op, 0x06)
                val size = u32(data, 0).toInt()
                val seq = u32(data, 4).toInt()
                val payload = data.copyOfRange(16, data.size)
                if (size != payload.size || size > blockSizeSeen) return fail(op, 0x05)
                var sum = EspFlasher.CHECKSUM_MAGIC
                payload.forEach { sum = sum xor (it.toInt() and 0xFF) }
                if (failBlockOnce == seq) { failBlockOnce = null; return fail(op, 0x07) }
                if (sum != chk) return fail(op, 0x07)
                if (seq != nextSeq || seq >= deflBlocks) return fail(op, 0x05)
                nextSeq++
                inf.setInput(payload)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = try { inf.inflate(buf) } catch (e: Exception) { return fail(op, 0x0B) }
                    if (n == 0) break
                    out.write(buf, 0, n)
                }
                val bytes = out.toByteArray()
                if (deflWritten + bytes.size > deflEraseSize) return fail(op, 0x08)
                bytes.copyInto(flash, deflOffset + deflWritten)
                deflWritten += bytes.size
                respond(op)
            }
            EspFlasher.OP_FLASH_DEFL_END -> {
                respond(op)
                if (u32(data, 0) == 0L) inDownloadMode = false // "reboot"
            }
            EspFlasher.OP_SPI_FLASH_MD5 -> {
                val addr = u32(data, 0).toInt()
                val size = u32(data, 4).toInt()
                if (addr.toLong() + size > flashSize) return fail(op, 0x05)
                val md5 = MessageDigest.getInstance("MD5").digest(flash.copyOfRange(addr, addr + size))
                respond(op, data = md5.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII))
            }
            else -> fail(op, 0x05)
        }
    }
}
