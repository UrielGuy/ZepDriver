package com.zepdriver.core.embargo

import com.zepdriver.core.brc.CampGeocoder
import com.zepdriver.core.data.PlayaDataLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyStore
import kotlin.system.exitProcess

/**
 * Build step: turns a data bundle for an embargoed year (the output of
 * tools/data/build_playa_data.py) into APK assets, sealing the camp and art
 * files. Camps without coordinates (the API gives camps addresses only) are
 * placed from their address first.
 *
 * Usage: SealMain <bundleDir> <outDir> <cert>...
 * where each cert is a keystore ("keystore:<file>:<storepass>:<alias>") or a
 * certificate SHA-256 in hex (e.g. Play App Signing's). The API key comes from
 * the ZEP_BM_API_KEY environment variable and never reaches the output.
 *
 * Output, under outDir/sealed/:
 * - index.json: the year and the data key wrapped once per signing certificate
 * - <year>/meta.zsl: sealed {year, sealedAt}
 * - <year>/camps.zsl, <year>/art.zsl: the sealed camp and art files
 * - <year>/bundle/: the rest of the bundle (map layers, layout), which is public
 *
 * Also writes outDir/../sealed-fingerprint.txt: a keyed hash of the sealed
 * content (not readable without the key), so CI can tell whether the data
 * changed since the last published build.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: SealMain <bundleDir> <outDir> <cert>...")
        exitProcess(2)
    }
    val apiKey = System.getenv("ZEP_BM_API_KEY")?.takeIf { it.isNotBlank() }
        ?: run { System.err.println("ZEP_BM_API_KEY is not set"); exitProcess(2) }
    val out = File(args[1])
    val fingerprint = sealBundle(File(args[0]), out, args.drop(2).map(::certSha256), apiKey)
    File(out.absoluteFile.parentFile, "sealed-fingerprint.txt").writeText(fingerprint)
}

/**
 * Writes [outRoot]/sealed/ from [bundle]; see the file comment for the layout.
 * Returns the content fingerprint: the same data always gives the same one,
 * whatever order the API listed it in.
 */
fun sealBundle(bundle: File, outRoot: File, certs: List<ByteArray>, apiKey: String): String {
    require(certs.isNotEmpty()) { "no signing certificates" }
    val out = File(outRoot, "sealed")
    val layout = Json.parseToJsonElement(File(bundle, "layout.json").readText()).jsonObject
    val year = layout["year"]!!.jsonPrimitive.int
    requireNotNull(DataRelease.forYear(year)) {
        "No release dates coded for $year: add them to DataRelease (annual review) before shipping its data."
    }

    val contents = DataRelease.SEALED_FILES.associateWith { name ->
        val file = File(bundle, name)
        require(file.isFile) { "missing $file" }
        if (name == "camps.json") geocodeCamps(bundle, file.readText()) else file.readText()
    }

    val key = Seal.dataKey(apiKey)
    out.deleteRecursively()
    val yearDir = File(out, "$year").apply { mkdirs() }
    for ((name, text) in contents) {
        File(yearDir, name.removeSuffix(".json") + ".zsl").writeBytes(Seal.seal(key, text.toByteArray(), "$year/$name"))
    }
    val meta = JsonObject(mapOf("year" to JsonPrimitive(year), "sealedAt" to JsonPrimitive(System.currentTimeMillis())))
    File(yearDir, "meta.zsl").writeBytes(Seal.seal(key, meta.toString().toByteArray(), "$year/meta"))

    bundle.walkTopDown().filter { it.isFile }.forEach { f ->
        val rel = f.relativeTo(bundle).invariantSeparatorsPath
        if (rel !in DataRelease.SEALED_FILES) f.copyTo(File(yearDir, "bundle/$rel"), overwrite = true)
    }

    val keys = certs.associate { Seal.hex(it) to JsonPrimitive(Seal.hex(Seal.wrapKey(key, it))) }
    val index = JsonObject(mapOf("year" to JsonPrimitive(year), "keys" to JsonObject(keys)))
    File(out, "index.json").writeText(index.toString())
    println("sealed $year for ${certs.size} signing certificate(s) into $out")
    return fingerprint(key, year, contents, bundle)
}

/** Keyed hash of the sealed records (order-independent) plus the public map files. */
private fun fingerprint(key: ByteArray, year: Int, contents: Map<String, String>, bundle: File): String {
    val canonical = buildString {
        append(year)
        for ((name, text) in contents.toSortedMap()) {
            append('\n').append(name)
            Json.parseToJsonElement(text).jsonArray.map { it.toString() }.sorted().forEach { append('\n').append(it) }
        }
        bundle.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(bundle).invariantSeparatorsPath to it }
            .filter { (rel, _) -> rel !in DataRelease.SEALED_FILES && rel != "manifest.json" }
            .sortedBy { it.first }
            .forEach { (rel, f) -> append('\n').append(rel).append('\n').append(f.readText()) }
    }
    return Seal.hex(Seal.mac(key, canonical.toByteArray())).take(16)
}

/** Fills in coordinates for camps that only have an address; drops the ones that can't be placed. */
private fun geocodeCamps(bundle: File, campsJson: String): String {
    val data = PlayaDataLoader.load { path ->
        if (path in DataRelease.SEALED_FILES) "[]" else File(bundle, path).readText()
    }
    val geocoder = CampGeocoder(data)
    var placed = 0
    var unplaced = 0
    val camps = Json.parseToJsonElement(campsJson).jsonArray.mapNotNull { e ->
        val camp = e.jsonObject
        if (camp["lat"] !is JsonNull && camp["lat"] != null) return@mapNotNull camp
        val address = camp["address"]?.jsonPrimitive?.contentOrNull
        val at = geocoder.locate(address)
        if (at == null) {
            unplaced++
            return@mapNotNull null
        }
        placed++
        JsonObject(camp + mapOf("lat" to JsonPrimitive(round6(at.lat)), "lon" to JsonPrimitive(round6(at.lon))))
    }
    // Counts only: CI logs are public, and these locations are confidential until released.
    println("placed $placed camps from their address; $unplaced couldn't be placed")
    return JsonArray(camps).toString()
}

private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

private fun certSha256(spec: String): ByteArray {
    if (!spec.startsWith("keystore:")) return Seal.unhex(spec.replace(":", "").lowercase())
    val (file, pass, alias) = spec.removePrefix("keystore:").split(":", limit = 3)
    val ks = KeyStore.getInstance(File(file), pass.toCharArray())
    val cert = requireNotNull(ks.getCertificate(alias)) { "no certificate $alias in $file" }
    return Seal.sha256(cert.encoded)
}
