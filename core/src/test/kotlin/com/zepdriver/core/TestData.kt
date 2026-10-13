package com.zepdriver.core

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.data.PlayaDataLoader
import java.io.File

/** Loads the real bundled 2026 data from app/src/main/assets. */
object TestData {
    val playa2026: PlayaData by lazy {
        val root = File(System.getProperty("zep.assets"), "playa/2026")
        PlayaDataLoader.load { File(root, it).readText() }
    }
}
