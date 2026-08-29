package com.example.othello

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class PortraitOrientationContractTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"
    private val manifest = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    @Test
    fun mainActivityIsPortraitLockedByTheManifest() {
        val activities = manifest.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) }
            .single { it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue == ".MainActivity" }

        assertEquals(
            "portrait",
            mainActivity.attributes.getNamedItemNS(androidNamespace, "screenOrientation")?.nodeValue,
        )
        assertFalse(mainActivity.attributes.getNamedItemNS(androidNamespace, "configChanges") != null)
    }
}
