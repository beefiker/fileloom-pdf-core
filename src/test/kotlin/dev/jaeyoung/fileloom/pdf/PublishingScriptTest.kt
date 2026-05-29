package dev.jaeyoung.fileloom.pdf

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PublishingScriptTest {
    @Test
    fun mavenCentralBundleSupportsNonInteractiveGpgPassphrase() {
        val script = findRepositoryFile("build.gradle.kts").readText()

        assertTrue(script.contains("signing.gnupg.passphrase"))
        assertTrue(script.contains("SIGNING_GNUPG_PASSPHRASE"))
        assertTrue(script.contains("--pinentry-mode"))
        assertTrue(script.contains("loopback"))
        assertTrue(script.contains("--passphrase"))
    }

    private fun findRepositoryFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            val file = File(current, relativePath)
            if (file.isFile) return file
            current = current.parentFile
        }
        error("Unable to locate $relativePath")
    }
}
