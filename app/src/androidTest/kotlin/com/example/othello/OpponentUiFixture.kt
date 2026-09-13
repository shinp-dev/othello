package com.example.othello

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipInputStream

internal fun opponentUiFixture(): InstalledOpponentPack {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val folder = File(context.cacheDir, "opponent-ui-fixture").apply { mkdirs() }
    ZipInputStream(context.assets.open("opponents/animal-v1.zip")).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val target = File(folder, entry.name)
            target.parentFile!!.mkdirs()
            target.outputStream().use { zip.copyTo(it) }
        }
    }
    return InstalledOpponentPack(parseOpponentManifest(File(folder, "manifest.json").readText()), folder)
}
