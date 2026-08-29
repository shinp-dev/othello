package com.example.othello

import java.io.File
import kotlin.test.assertContains
import org.junit.Test

class PlayReleaseSigningContractTest {
    private val buildScript = File("build.gradle.kts").readText()

    @Test
    fun playReleaseVerificationPinsTheOfficialUploadCertificate() {
        assertContains(
            buildScript,
            "47:87:8B:52:E9:3A:9C:FD:5F:D9:0C:DE:BF:E3:B6:E4:02:9D:BF:8F:FB:A9:B4:48:14:0B:05:DB:A8:1C:79:DD",
        )
        assertContains(buildScript, "JarFile(bundle, true)")
        assertContains(buildScript, "entry.certificates")
        assertContains(
            buildScript,
            "chanrivaExpectedUploadCertificateSha256 !in entryFingerprints",
        )
        assertContains(
            buildScript,
            "chanrivaExpectedUploadCertificateSha256 !in signingCertificateFingerprints",
        )
    }
}
