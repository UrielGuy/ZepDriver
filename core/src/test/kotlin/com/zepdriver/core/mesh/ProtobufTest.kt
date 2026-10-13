package com.zepdriver.core.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun hex(s: String): ByteArray =
    s.split(' ', '\n').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

class ProtobufTest {
    @Test
    fun varintEncoding() {
        assertEquals("00", ProtoWriter.encodeVarint(0).toHex())
        assertEquals("01", ProtoWriter.encodeVarint(1).toHex())
        assertEquals("ac 02", ProtoWriter.encodeVarint(300).toHex())
        assertEquals("ff ff ff ff 0f", ProtoWriter.encodeVarint(0xFFFFFFFFL).toHex())
        // Negative int32 is sign-extended to 10 bytes.
        assertEquals("ff ff ff ff ff ff ff ff ff 01", ProtoWriter.encodeVarint(-1).toHex())
    }

    @Test
    fun scalarRoundTrip() {
        val bytes = ProtoWriter()
            .uint(1, 300)
            .int32(2, -5)
            .sint32(3, -2)
            .bool(4, true)
            .fixed32(5, -1192065000)
            .float(6, 1.5f)
            .string(7, "Zebra ❤")
            .bytes(8, byteArrayOf(1, 2, 3))
            .uint(9, 0xFFFFFFFFL)
            .toByteArray()
        val m = ProtoMessage.parse(bytes)
        assertEquals(300L, m.uint32(1))
        assertEquals(-5, m.int32(2))
        assertEquals(-2, m.sint32(3))
        assertTrue(m.bool(4))
        assertEquals(-1192065000, m.int32(5))
        assertEquals(1.5f, m.float(6))
        assertEquals("Zebra ❤", m.string(7))
        assertContentEquals(byteArrayOf(1, 2, 3), m.bytes(8))
        assertEquals(0xFFFFFFFFL, m.uint32(9))
        assertFalse(m.has(10))
        assertEquals(0, m.int32(10))
        assertNull(m.int32OrNull(10))
    }

    @Test
    fun defaultsAreNotWritten() {
        val bytes = ProtoWriter().uint(1, 0).int32(2, 0).bool(3, false).string(4, "").float(5, 0f).fixed32(6, 0).toByteArray()
        assertEquals(0, bytes.size)
    }

    @Test
    fun zigzag() {
        for (v in listOf(0, 1, -1, 2, -2, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(v, ProtoWriter.unzigzag32(ProtoWriter.zigzag32(v)))
        }
        assertEquals(3, ProtoWriter.zigzag32(-2))
    }

    @Test
    fun lastScalarWinsAndMessagesMerge() {
        val inner1 = ProtoWriter().uint(1, 7).toByteArray()
        val inner2 = ProtoWriter().uint(2, 9).toByteArray()
        val m = ProtoMessage.parse(ProtoWriter().uint(1, 1).uint(1, 2).message(3, inner1).message(3, inner2).toByteArray())
        assertEquals(2L, m.uint32(1))
        val merged = m.message(3)!!
        assertEquals(7L, merged.uint32(1))
        assertEquals(9L, merged.uint32(2))
    }

    @Test
    fun skipsUnknownWireTypesAndKeepsUnknownFields() {
        // field 1 varint, field 2 fixed64, field 3 fixed32, field 4 len
        val bytes = hex("08 96 01 11 01 02 03 04 05 06 07 08 1d 01 00 00 00 22 02 68 69")
        val m = ProtoMessage.parse(bytes)
        assertEquals(150L, m.uint32(1))
        assertEquals(0x0807060504030201L, m.uint64(2))
        assertEquals("hi", m.string(4))
        assertEquals("11 01 02 03 04 05 06 07 08 22 02 68 69", m.unknownFields(setOf(1, 3)).toHex())
    }

    @Test
    fun packedRepeated() {
        val packed = ProtoWriter().bytesAlways(5, hex("01 ac 02 03")).varintAlways(5, 4).toByteArray()
        assertEquals(listOf(1L, 300L, 3L, 4L), ProtoMessage.parse(packed).repeatedUint32(5))
    }

    @Test
    fun truncatedInputThrows() {
        assertFailsWith<ProtoException> { ProtoMessage.parse(hex("08")) }
        assertFailsWith<ProtoException> { ProtoMessage.parse(hex("12 05 01 02")) }
        assertFailsWith<ProtoException> { ProtoMessage.parse(hex("0d 01 02")) }
        assertFailsWith<ProtoException> { ProtoMessage.parse(hex("0b")) } // group start
    }
}
