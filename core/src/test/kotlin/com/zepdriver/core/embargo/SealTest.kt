package com.zepdriver.core.embargo

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SealTest {
    private val key = Seal.dataKey("test-api-key")
    private val cert = Seal.sha256("our signing certificate".toByteArray())
    private val text = """[{"id":"a1","name":"Camp Test","lat":40.78,"lon":-119.2}]""".repeat(50).toByteArray()

    @Test fun roundTrip() {
        val sealed = Seal.seal(key, text, "2027/camps.json")
        assertContentEquals(text, Seal.open(key, sealed, "2027/camps.json"))
        // Gzipped before encrypting.
        assert(sealed.size < text.size)
    }

    @Test fun keyIsStableAndHidesTheApiKey() {
        assertContentEquals(key, Seal.dataKey(" test-api-key\n"))
        assertEquals(32, key.size)
        assertFalse(Seal.hex(key).contains(Seal.hex("test-api-key".toByteArray())))
    }

    @Test fun anyChangedByteFailsToOpen() {
        val sealed = Seal.seal(key, text, "2027/camps.json")
        for (i in listOf(4, 12, sealed.size / 2, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<SealException> { Seal.open(key, bad, "2027/camps.json") }
        }
        assertFailsWith<SealException> { Seal.open(key, sealed.copyOf(10), "2027/camps.json") }
    }

    @Test fun filesCantBeSwapped() {
        val art = Seal.seal(key, text, "2027/art.json")
        assertFailsWith<SealException> { Seal.open(key, art, "2027/camps.json") }
    }

    @Test fun keyIsBoundToTheSigningCertificate() {
        val wrapped = Seal.wrapKey(key, cert)
        assertContentEquals(key, Seal.unwrapKey(wrapped, cert))
        val otherCert = Seal.sha256("someone else's certificate".toByteArray())
        val wrong = Seal.unwrapKey(wrapped, otherCert)
        val sealed = Seal.seal(key, text, "2027/camps.json")
        assertFailsWith<SealException> { Seal.open(wrong, sealed, "2027/camps.json") }
    }

    @Test fun hexRoundTrip() {
        assertContentEquals(cert, Seal.unhex(Seal.hex(cert)))
    }
}
