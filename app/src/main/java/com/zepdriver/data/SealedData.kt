package com.zepdriver.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.zepdriver.core.embargo.DataRelease
import com.zepdriver.core.embargo.Naughty
import com.zepdriver.core.embargo.ReleaseSchedule
import com.zepdriver.core.embargo.Seal
import com.zepdriver.core.embargo.SealException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.FileNotFoundException

/**
 * The embargoed year's data sealed into this APK by tools/data/build_sealed.sh
 * (assets/sealed/), opened with the key for the certificate this app is signed
 * with.
 */
class SealedData private constructor(
    private val context: Context,
    val year: Int,
    private val key: ByteArray,
    /** When CI sealed it: real time can't be earlier than this. */
    val sealedAt: Long,
    val schedule: ReleaseSchedule,
) {
    /** MapLibre prefix for the year's public map layers. */
    val uriPrefix = "asset://sealed/$year/bundle/"

    /** Reads a bundle file: sealed ones are opened, the rest are plain assets. Throws [SealException] if changed. */
    fun read(path: String): String =
        if (path in DataRelease.SEALED_FILES) {
            val bytes = asset("sealed/$year/${path.removeSuffix(".json")}.zsl")
            String(Seal.open(key, bytes, "$year/$path"))
        } else {
            String(asset("sealed/$year/bundle/$path"))
        }

    /** HMAC for saved state, keyed by the data key. */
    fun mac(message: String): String = Seal.hex(Seal.mac(key, message.toByteArray()))

    private fun asset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    /** What [load] found. */
    sealed interface Result {
        /** This build carries no embargoed data (no API key in CI, or no placements yet). */
        data object None : Result
        data class Ok(val data: SealedData) : Result
        data class Tampered(val why: Naughty, val year: Int?) : Result
    }

    companion object {
        private const val TAG = "SealedData"

        fun load(context: Context): Result {
            val indexText = try {
                context.assets.open("sealed/index.json").bufferedReader().use { it.readText() }
            } catch (e: FileNotFoundException) {
                return Result.None
            }
            var year: Int? = null
            return try {
                val index = Json.parseToJsonElement(indexText).jsonObject
                year = index["year"]!!.jsonPrimitive.int
                val keys = index["keys"]!!.jsonObject
                // Signed by a certificate we didn't seal for: rebuilt or patched by someone else.
                val wrapped = signingCerts(context).firstNotNullOfOrNull { cert ->
                    keys[Seal.hex(cert)]?.jsonPrimitive?.contentOrNull?.let { Seal.unwrapKey(Seal.unhex(it), cert) }
                } ?: return Result.Tampered(Naughty.ModifiedApp, year)
                val meta = Json.parseToJsonElement(
                    String(Seal.open(wrapped, context.assets.open("sealed/$year/meta.zsl").use { it.readBytes() }, "$year/meta")),
                ).jsonObject
                val schedule = DataRelease.forYear(year)
                if (meta["year"]!!.jsonPrimitive.int != year || schedule == null) {
                    return Result.Tampered(Naughty.ModifiedData, year)
                }
                Result.Ok(SealedData(context, year, wrapped, meta["sealedAt"]!!.jsonPrimitive.long, schedule))
            } catch (e: SealException) {
                Log.w(TAG, "sealed data failed its integrity check", e)
                Result.Tampered(Naughty.ModifiedData, year)
            } catch (e: Exception) {
                Log.w(TAG, "sealed data unreadable", e)
                Result.Tampered(Naughty.ModifiedData, year)
            }
        }

        /** SHA-256 of each certificate this app is signed with (rotation history included). */
        @SuppressLint("PackageManagerGetSignatures")
        @Suppress("DEPRECATION")
        private fun signingCerts(context: Context): List<ByteArray> {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                    ?: return emptyList()
                if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            return signatures.orEmpty().map { Seal.sha256(it.toByteArray()) }
        }
    }
}
