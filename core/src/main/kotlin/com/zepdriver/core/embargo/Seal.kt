package com.zepdriver.core.embargo

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A sealed file failed to open: wrong key or changed bytes. */
class SealException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seals embargoed data files at build time and opens them in the app.
 *
 * A file is gzipped, then encrypted with AES-256-GCM, whose tag also makes any
 * change to the bytes fail to open. The data key is derived from the Burning
 * Man API key (a CI secret), so it is stable across builds but never ships in
 * a form that reveals the API key. In the APK the data key is stored only
 * XOR-masked with a hash of the signing certificate: a copy re-signed by
 * someone else derives the wrong key and opens nothing.
 *
 * This keeps the data out of reach of unzipping the APK, editing files or
 * resetting the phone's clock. Nothing that ships inside an app is safe from
 * a determined reverse engineer; every offline app has that limit.
 */
object Seal {
    private val MAGIC = "ZSL1".toByteArray()
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** The data key, from the API key. HKDF-SHA256. */
    fun dataKey(apiKey: String): ByteArray =
        hkdf(ikm = apiKey.trim().toByteArray(), salt = "zepdriver-seal".toByteArray(), info = "data-key-v1".toByteArray())

    /** The data key as stored for one signing certificate (its SHA-256). */
    fun wrapKey(dataKey: ByteArray, certSha256: ByteArray): ByteArray = xor(dataKey, certMask(certSha256))

    fun unwrapKey(wrapped: ByteArray, certSha256: ByteArray): ByteArray = xor(wrapped, certMask(certSha256))

    /** [label] names the file (e.g. "2027/camps.json"), so sealed files can't be swapped for each other. */
    fun seal(key: ByteArray, plaintext: ByteArray, label: String, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(label.toByteArray())
        return MAGIC + nonce + cipher.doFinal(gzip(plaintext))
    }

    fun open(key: ByteArray, sealed: ByteArray, label: String): ByteArray {
        if (sealed.size < MAGIC.size + NONCE_BYTES + TAG_BITS / 8 ||
            !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        ) {
            throw SealException("not a sealed file")
        }
        val nonce = sealed.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
        val body = sealed.copyOfRange(MAGIC.size + NONCE_BYTES, sealed.size)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(label.toByteArray())
            cipher.doFinal(body)
        } catch (e: GeneralSecurityException) {
            throw SealException("sealed file $label failed its integrity check", e)
        }
        return gunzip(plain)
    }

    /** HMAC-SHA256 of [message], for small saved state (e.g. the confirmed-time floor). */
    fun mac(key: ByteArray, message: ByteArray): ByteArray = hmac(hkdf(key, "zepdriver-mac".toByteArray(), "state-v1".toByteArray()), message)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun certMask(certSha256: ByteArray): ByteArray = sha256("zepdriver-cert-mask-v1".toByteArray() + certSha256)

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "key size mismatch" }
        return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    /** RFC 5869 HKDF-SHA256, one 32-byte block. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1))
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
