package com.zepdriver.core.embargo

import com.zepdriver.core.TestData
import com.zepdriver.core.data.PlayaDataLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The build step's output, opened the way the app opens it (SealedData). */
class SealBundleTest {
    private val bundle = File(System.getProperty("zep.assets"), "playa/2026")
    private val cert = Seal.sha256("our cert".toByteArray())

    @Test fun sealsAndOpensTheWholeBundle() {
        val out = Files.createTempDirectory("sealed").toFile()
        try {
            sealBundle(bundle, out, listOf(cert), apiKey = "k")
            val root = File(out, "sealed")
            val index = Json.parseToJsonElement(File(root, "index.json").readText()).jsonObject
            assertEquals(2026, index["year"]!!.jsonPrimitive.int)
            val key = Seal.unwrapKey(Seal.unhex(index["keys"]!!.jsonObject[Seal.hex(cert)]!!.jsonPrimitive.content), cert)

            val data = PlayaDataLoader.load { path ->
                if (path in DataRelease.SEALED_FILES) {
                    String(Seal.open(key, File(root, "2026/${path.removeSuffix(".json")}.zsl").readBytes(), "2026/$path"))
                } else {
                    File(root, "2026/bundle/$path").readText()
                }
            }
            val original = TestData.playa2026
            assertEquals(original.camps.size, data.camps.size)
            assertEquals(original.art.size, data.art.size)
            assertEquals(original.camps.first().name, data.camps.first().name)

            // Nothing readable about camps or art outside the sealed files.
            val name = original.camps.first().name
            root.walkTopDown().filter { it.isFile && it.extension != "zsl" }.forEach {
                assertFalse(it.readText().contains(name), "$it leaks camp data")
            }
            assertFalse(File(root, "2026/bundle/camps.json").exists())
            assertTrue(File(root, "2026/meta.zsl").isFile)
        } finally {
            out.deleteRecursively()
        }
    }
}
