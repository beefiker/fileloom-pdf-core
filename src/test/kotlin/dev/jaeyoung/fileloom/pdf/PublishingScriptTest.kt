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
        assertTrue(script.contains("Set SIGNING_GNUPG_PASSPHRASE"))
    }

    @Test
    fun mavenCentralBundleStageTracksArtifactVersionForUpToDateChecks() {
        val script = findRepositoryFile("build.gradle.kts").readText()
        val stageTask = script
            .substringAfter("tasks.register(\"stageMavenCentralBundle\")")
            .substringBefore("tasks.register<Zip>(\"publishToMavenCentralBundle\")")

        assertTrue(stageTask.contains("inputs.property(\"artifactPathSegment\""))
        assertTrue(stageTask.contains("inputs.property(\"artifactVersion\""))
        assertTrue(stageTask.contains("outputs.dir(stagingDir)"))
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
