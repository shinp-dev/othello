package com.example.othello

import java.io.File
import java.util.zip.ZipFile

internal fun animalManifest(): String = ZipFile(File("src/main/assets/opponents/animal-v1.zip")).use {
    it.getInputStream(it.getEntry("manifest.json")).bufferedReader().use { reader -> reader.readText() }
}
internal fun animalPack(): OpponentPack = parseOpponentManifest(animalManifest())
