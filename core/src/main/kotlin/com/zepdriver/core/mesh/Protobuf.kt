package com.zepdriver.core.mesh

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format codec (proto3 semantics), hand written so the
 * app does not ship code generated from the GPL-3 Meshtastic .proto files.
 *
 * Supported wire types: 0 (varint), 1 (64-bit), 2 (length-delimited), 5 (32-bit).
 * Groups (3/4) are rejected.
 */
class ProtoException(message: String) : Exception(message)

object WireType {
    const val VARINT = 0
    const val I64 = 1
    const val LEN = 2
    const val I32 = 5
}

/** Builds one encoded message. Implicit-presence (proto3) helpers skip default values. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wireType: Int) {
        require(field in 1..0x1FFFFFFF) { "bad field number $field" }
        rawVarint(((field.toLong()) shl 3) or wireType.toLong())
    }

    private fun rawFixed32(value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    /** Always writes the field (explicit presence / oneof members / forced). */
    fun varintAlways(field: Int, value: Long): ProtoWriter { tag(field, WireType.VARINT); rawVarint(value); return this }

    /** uint32 / uint64 / enum. Values are taken as unsigned; skipped when 0. */
    fun uint(field: Int, value: Long): ProtoWriter = if (value == 0L) this else varintAlways(field, value)

    /** int32 (negative numbers are sign-extended to 10 bytes, as protobuf requires). Skipped when 0. */
    fun int32(field: Int, value: Int): ProtoWriter = if (value == 0) this else varintAlways(field, value.toLong())

    fun int32Always(field: Int, value: Int): ProtoWriter = varintAlways(field, value.toLong())

    /** sint32 (zigzag). Skipped when 0. */
    fun sint32(field: Int, value: Int): ProtoWriter =
        if (value == 0) this else varintAlways(field, zigzag32(value).toLong() and 0xFFFFFFFFL)

    fun bool(field: Int, value: Boolean): ProtoWriter = if (!value) this else varintAlways(field, 1)

    fun boolAlways(field: Int, value: Boolean): ProtoWriter = varintAlways(field, if (value) 1 else 0)

    /** fixed32 / sfixed32 given as the raw 32 bits. Skipped when 0. */
    fun fixed32(field: Int, value: Int): ProtoWriter = if (value == 0) this else fixed32Always(field, value)

    fun fixed32Always(field: Int, value: Int): ProtoWriter { tag(field, WireType.I32); rawFixed32(value); return this }

    fun float(field: Int, value: Float): ProtoWriter =
        if (value == 0f && 1f / value > 0) this else fixed32Always(field, java.lang.Float.floatToIntBits(value))

    fun floatAlways(field: Int, value: Float): ProtoWriter = fixed32Always(field, java.lang.Float.floatToIntBits(value))

    fun bytesAlways(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WireType.LEN); rawVarint(value.size.toLong()); out.write(value); return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter = if (value.isEmpty()) this else bytesAlways(field, value)

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    /** Embedded message (always written, even when empty: sets presence / the oneof case). */
    fun message(field: Int, encoded: ByteArray): ProtoWriter = bytesAlways(field, encoded)

    /** Appends already-encoded fields verbatim (e.g. preserved unknown fields). */
    fun raw(encodedFields: ByteArray): ProtoWriter { out.write(encodedFields); return this }

    companion object {
        fun zigzag32(n: Int): Int = (n shl 1) xor (n shr 31)
        fun unzigzag32(n: Int): Int = (n ushr 1) xor -(n and 1)

        fun encodeVarint(value: Long): ByteArray {
            val w = ProtoWriter(); w.rawVarint(value); return w.toByteArray()
        }
    }
}

/** One decoded field occurrence. [value] is the varint / fixed bits; [bytes] for length-delimited fields. */
class ProtoField(
    val number: Int,
    val wireType: Int,
    val value: Long,
    val bytes: ByteArray?,
    /** The complete encoding of this occurrence (tag included), for re-emitting unknown fields. */
    val raw: ByteArray,
)

/**
 * A decoded message: every field occurrence in wire order. Accessors follow
 * protobuf semantics: last occurrence wins for scalars; embedded messages
 * occurring several times are merged (their encodings concatenated).
 */
class ProtoMessage private constructor(val fields: List<ProtoField>) {

    fun has(n: Int): Boolean = fields.any { it.number == n }

    private fun last(n: Int): ProtoField? = fields.lastOrNull { it.number == n }

    private fun lastScalar(n: Int, vararg wireTypes: Int): ProtoField? {
        val f = last(n) ?: return null
        if (f.wireType !in wireTypes) throw ProtoException("field $n: unexpected wire type ${f.wireType}")
        return f
    }

    /** Unsigned 32-bit (uint32 / enum / fixed32) as a non-negative Long. */
    fun uint32(n: Int): Long = (lastScalar(n, WireType.VARINT, WireType.I32)?.value ?: 0L) and 0xFFFFFFFFL

    fun uint32OrNull(n: Int): Long? = if (has(n)) uint32(n) else null

    fun uint64(n: Int): Long = lastScalar(n, WireType.VARINT, WireType.I64)?.value ?: 0L

    /** int32 (varint, negatives sign-extended) or sfixed32. */
    fun int32(n: Int): Int = (lastScalar(n, WireType.VARINT, WireType.I32)?.value ?: 0L).toInt()

    fun int32OrNull(n: Int): Int? = if (has(n)) int32(n) else null

    fun sint32(n: Int): Int = ProtoWriter.unzigzag32(int32(n))

    fun sint32OrNull(n: Int): Int? = if (has(n)) sint32(n) else null

    fun bool(n: Int): Boolean = (lastScalar(n, WireType.VARINT)?.value ?: 0L) != 0L

    fun boolOrNull(n: Int): Boolean? = if (has(n)) bool(n) else null

    fun float(n: Int): Float = java.lang.Float.intBitsToFloat((lastScalar(n, WireType.I32)?.value ?: 0L).toInt())

    fun bytes(n: Int): ByteArray = lastScalar(n, WireType.LEN)?.bytes ?: ByteArray(0)

    fun string(n: Int): String = String(bytes(n), Charsets.UTF_8)

    /** Embedded message bytes (merged if repeated on the wire), or null when absent. */
    fun messageBytes(n: Int): ByteArray? {
        val occ = fields.filter { it.number == n }
        if (occ.isEmpty()) return null
        if (occ.any { it.wireType != WireType.LEN }) throw ProtoException("field $n: not length-delimited")
        if (occ.size == 1) return occ[0].bytes
        val out = ByteArrayOutputStream()
        occ.forEach { out.write(it.bytes!!) }
        return out.toByteArray()
    }

    fun message(n: Int): ProtoMessage? = messageBytes(n)?.let { parse(it) }

    /** Repeated scalar varints, accepting both packed and unpacked encodings. */
    fun repeatedUint32(n: Int): List<Long> = fields.filter { it.number == n }.flatMap { f ->
        if (f.wireType == WireType.LEN) {
            val r = Reader(f.bytes!!)
            buildList { while (!r.atEnd()) add(r.varint() and 0xFFFFFFFFL) }
        } else listOf(f.value and 0xFFFFFFFFL)
    }

    /** The number of the last field present out of [candidates] (oneof case), or null. */
    fun oneofCase(vararg candidates: Int): Int? = fields.lastOrNull { it.number in candidates }?.number

    /** Re-encodes every field whose number is not in [known], in wire order. */
    fun unknownFields(known: Set<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        fields.filter { it.number !in known }.forEach { out.write(it.raw) }
        return out.toByteArray()
    }

    internal class Reader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
        fun atEnd() = pos >= end
        fun position() = pos

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw ProtoException("truncated varint")
                val b = buf[pos++].toInt() and 0xFF
                if (shift < 64) result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 70) throw ProtoException("varint too long")
            }
        }

        fun fixed32(): Long {
            if (end - pos < 4) throw ProtoException("truncated fixed32")
            var v = 0L
            for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }

        fun fixed64(): Long {
            if (end - pos < 8) throw ProtoException("truncated fixed64")
            var v = 0L
            for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }

        fun bytes(len: Long): ByteArray {
            if (len < 0 || len > end - pos) throw ProtoException("truncated length-delimited field")
            val r = buf.copyOfRange(pos, pos + len.toInt())
            pos += len.toInt()
            return r
        }

        fun slice(from: Int) = buf.copyOfRange(from, pos)
    }

    companion object {
        fun parse(bytes: ByteArray): ProtoMessage {
            val r = Reader(bytes)
            val fields = ArrayList<ProtoField>()
            while (!r.atEnd()) {
                val start = r.position()
                val key = r.varint()
                val number = (key ushr 3).toInt()
                val wt = (key and 7).toInt()
                if (number <= 0) throw ProtoException("invalid field number $number")
                when (wt) {
                    WireType.VARINT -> { val v = r.varint(); fields += ProtoField(number, wt, v, null, r.slice(start)) }
                    WireType.I64 -> { val v = r.fixed64(); fields += ProtoField(number, wt, v, null, r.slice(start)) }
                    WireType.LEN -> {
                        val b = r.bytes(r.varint())
                        fields += ProtoField(number, wt, 0, b, r.slice(start))
                    }
                    WireType.I32 -> { val v = r.fixed32(); fields += ProtoField(number, wt, v, null, r.slice(start)) }
                    else -> throw ProtoException("unsupported wire type $wt (field $number)")
                }
            }
            return ProtoMessage(fields)
        }
    }
}
