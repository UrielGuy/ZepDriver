package com.zepdriver.core.alerts

import com.zepdriver.core.data.PlayaDataLoader
import com.zepdriver.core.rules.ruleSetFor
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** Stable file id for a phrase's audio clip. */
fun speechClipId(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

/**
 * Writes the warning-phrase catalog for the bundled data as JSON
 * (`[{"id": …, "text": …}]`) for tools/speech/generate_clips.py.
 * Args: <bundle dir, e.g. app/src/main/assets/playa/2026> <output json>
 */
fun main(args: Array<String>) {
    val bundle = File(args[0])
    val data = PlayaDataLoader.load { File(bundle, it).readText() }
    val phrases = Speech.catalog(data, ruleSetFor(data.layout.year))
    val json = buildJsonArray {
        for (p in phrases) add(buildJsonObject { put("id", speechClipId(p)); put("text", p) })
    }
    File(args[1]).apply { parentFile?.mkdirs() }.writeText(json.toString())
    println("${phrases.size} phrases -> ${args[1]}")
}
