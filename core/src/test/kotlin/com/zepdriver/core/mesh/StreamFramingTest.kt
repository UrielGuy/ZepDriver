package com.zepdriver.core.mesh

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamFramingTest {
    @Test
    fun frameHeader() {
        val f = StreamFraming.frame(byteArrayOf(1, 2, 3))
        assertEquals("94 c3 00 03 01 02 03", f.toHex())
        val big = StreamFraming.frame(ByteArray(300))
        assertEquals("94 c3 01 2c", big.copyOf(4).toHex())
        assertFailsWith<IllegalArgumentException> { StreamFraming.frame(ByteArray(513)) }
        assertEquals(32, StreamFraming.WAKE.size)
    }

    @Test
    fun deframesWithDebugTextAndNoise() {
        val lines = mutableListOf<String>()
        val d = StreamFraming.Deframer { lines += it }
        val p1 = ByteArray(40) { (it * 7).toByte() }
        val p2 = "second".toByteArray()
        val stream = "INFO  | 12:00:01 Booting\r\n".toByteArray() +
            StreamFraming.frame(p1) +
            byteArrayOf(0x94.toByte(), 0x41) + // a lone START1 followed by text
            "DEBUG | lora ok\n".toByteArray() +
            byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x7F, 0x7F) + // header with impossible length (32639)
            StreamFraming.frame(p2)
        val out = d.feed(stream)
        assertEquals(2, out.size)
        assertContentEquals(p1, out[0])
        assertContentEquals(p2, out[1])
        assertTrue(lines.any { it.contains("Booting") })
        assertTrue(lines.any { it.contains("lora ok") })
    }

    @Test
    fun framesSplitAcrossFeeds() {
        val payloads = (1..20).map { n -> Random(n).nextBytes(Random(n).nextInt(1, 512)) }
        val stream = payloads.fold(ByteArray(0)) { acc, p -> acc + "noise\n".toByteArray() + StreamFraming.frame(p) }
        for (chunk in listOf(1, 3, 7, 64, 1000)) {
            val d = StreamFraming.Deframer()
            val out = mutableListOf<ByteArray>()
            var i = 0
            while (i < stream.size) {
                val n = minOf(chunk, stream.size - i)
                val buf = ByteArray(2000)
                stream.copyInto(buf, 0, i, i + n)
                out += d.feed(buf, n) // only the first n bytes count
                i += n
            }
            assertEquals(payloads.size, out.size, "chunk $chunk")
            payloads.zip(out).forEach { (a, b) -> assertContentEquals(a, b) }
        }
    }

    @Test
    fun resyncsWhenGarbageHeaderHidesRealFrame() {
        // 0x94 0x94 0xC3 ...: the first START1 is noise; the real frame starts at the second.
        val payload = byteArrayOf(9, 8, 7)
        val out = StreamFraming.Deframer().feed(byteArrayOf(0x94.toByte()) + StreamFraming.frame(payload))
        assertEquals(1, out.size)
        assertContentEquals(payload, out[0])
        // An invalid length hides a real header inside it: 94 c3 [94 c3] 00 02 aa bb
        val tricky = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x94.toByte(), 0xC3.toByte(), 0x00, 0x02, 0xAA.toByte(), 0xBB.toByte())
        val out2 = StreamFraming.Deframer().feed(tricky)
        assertEquals(listOf("aa bb"), out2.map { it.toHex() })
    }

    @Test
    fun meshProtocolOverSerial() {
        val p = MeshProtocol()
        p.handleFromRadio(FromRadio.encode(FromRadio.MyInfo(0, MyNodeInfo(1))))
        val frames = listOf(p.textPacket("hi"), p.heartbeat()).map { StreamFraming.frame(it) }
        val out = StreamFraming.Deframer().feed(frames[0] + frames[1])
        assertEquals("hi", String((ToRadio.decode(out[0]) as ToRadio.Packet).packet.decoded!!.payload))
    }
}
