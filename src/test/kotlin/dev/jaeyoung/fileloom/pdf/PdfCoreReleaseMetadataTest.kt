package dev.jaeyoung.fileloom.pdf

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PdfCoreReleaseMetadataTest {
    @Test
    fun releaseMetadataTargetsOutlineRecoveryVersion() {
        val properties = findRepositoryFile("gradle.properties").readText()
        val readme = findRepositoryFile("README.md").readText()

        assertTrue(properties.lineSequence().any { it == "version=0.2.7" })
        assertTrue(readme.contains("fileloom-pdf-core:0.2.7"))
        assertTrue(readme.contains("bounded trailing-data outline recovery"))
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
