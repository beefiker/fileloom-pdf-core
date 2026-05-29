import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.2.10"
    `java-library`
    `maven-publish`
}

group = providers.gradleProperty("group").orNull ?: "dev.jaeyoung"
version = providers.gradleProperty("version").orNull ?: "0.1.0-SNAPSHOT"

description = "Fileloom PDF text, outline, and annotation core library"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("dev.jaeyoung:fileloom-pdf-parser-core:0.3.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("fileloom-pdf-core")
                description.set(project.description)
                url.set("https://github.com/beefiker/fileloom-pdf-core")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("jaeyoung")
                        name.set("Jaeyoung")
                    }
                }
                scm {
                    url.set("https://github.com/beefiker/fileloom-pdf-core")
                    connection.set("scm:git:https://github.com/beefiker/fileloom-pdf-core.git")
                    developerConnection.set("scm:git:ssh://git@github.com:beefiker/fileloom-pdf-core.git")
                }
            }
        }
    }
}

val mavenCentralBundleDir = layout.buildDirectory.dir("maven-central-bundle")
val mavenCentralStagingDir = layout.buildDirectory.dir("maven-central-bundle/staging")

/**
 * Stages the artifacts (jar/sources/javadoc/pom) into the layout Maven Central
 * expects (`dev/jaeyoung/fileloom-pdf-core/0.1.0/...`), then GPG-signs each
 * one and writes `.md5`/`.sha1` checksums alongside.
 *
 * GPG key is taken from `signing.gnupg.keyName` Gradle property if set,
 * otherwise gpg's default key is used.
 */
tasks.register("stageMavenCentralBundle") {
    description = "Stages signed + checksummed artifacts for Maven Central under build/maven-central-bundle/staging/."
    group = "publishing"

    dependsOn("jar", "sourcesJar", "javadocJar", "generatePomFileForMavenJavaPublication")

    val artifactPathSegment = "${project.group.toString().replace('.', '/')}/${project.name}/${project.version}"
    val jarFileProvider = tasks.named<Jar>("jar").map { it.archiveFile.get().asFile }
    val sourcesJarFileProvider = tasks.named<Jar>("sourcesJar").map { it.archiveFile.get().asFile }
    val javadocJarFileProvider = tasks.named<Jar>("javadocJar").map { it.archiveFile.get().asFile }
    val pomFileProvider = tasks.named("generatePomFileForMavenJavaPublication").map {
        layout.buildDirectory.file("publications/mavenJava/pom-default.xml").get().asFile
    }
    val signingKey = providers.gradleProperty("signing.gnupg.keyName")
    val signingPassphrase = providers.gradleProperty("signing.gnupg.passphrase")
        .orElse(providers.environmentVariable("SIGNING_GNUPG_PASSPHRASE"))
    val stagingDir = mavenCentralStagingDir
    val artifactName = project.name
    val artifactVersion = project.version.toString()

    outputs.dir(stagingDir)

    doLast {
        val targetDir = stagingDir.get().asFile.resolve(artifactPathSegment)
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        val sources = listOf(
            jarFileProvider.get() to "$artifactName-$artifactVersion.jar",
            sourcesJarFileProvider.get() to "$artifactName-$artifactVersion-sources.jar",
            javadocJarFileProvider.get() to "$artifactName-$artifactVersion-javadoc.jar",
            pomFileProvider.get() to "$artifactName-$artifactVersion.pom",
        )

        sources.forEach { (sourceFile, targetName) ->
            val destFile = targetDir.resolve(targetName)
            sourceFile.copyTo(destFile, overwrite = true)
            writeChecksum(destFile, "MD5", destFile.resolveSibling("$targetName.md5"))
            writeChecksum(destFile, "SHA-1", destFile.resolveSibling("$targetName.sha1"))
            signWithGpg(destFile, signingKey.orNull, signingPassphrase.orNull)
        }

        logger.lifecycle("Maven Central staging ready at ${targetDir.absolutePath}")
    }
}

tasks.register<Zip>("publishToMavenCentralBundle") {
    description = "Builds a Maven Central upload bundle (signed artifacts + checksums) under build/maven-central-bundle/."
    group = "publishing"

    dependsOn("stageMavenCentralBundle")

    from(mavenCentralStagingDir)
    archiveFileName.set("${project.name}-${project.version}-maven-central-bundle.zip")
    destinationDirectory.set(mavenCentralBundleDir)

    doLast {
        logger.lifecycle("Bundle ready: ${archiveFile.get().asFile.absolutePath}")
        logger.lifecycle("Upload at https://central.sonatype.com/publishing")
    }
}

fun writeChecksum(file: java.io.File, algorithm: String, output: java.io.File) {
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    val hex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    output.writeText(hex)
}

fun signWithGpg(file: java.io.File, keyName: String?, passphrase: String?) {
    val signature = file.resolveSibling("${file.name}.asc")
    if (signature.exists()) signature.delete()
    val cmd = mutableListOf("gpg", "--batch", "--yes", "--detach-sign", "--armor", "--output", signature.absolutePath)
    if (keyName != null) {
        cmd += listOf("--local-user", keyName)
    }
    if (!passphrase.isNullOrEmpty()) {
        cmd += listOf("--pinentry-mode", "loopback", "--passphrase", passphrase)
    }
    cmd += file.absolutePath
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    if (exit != 0) {
        val passphraseHint = if (passphrase.isNullOrEmpty()) {
            "\nSet SIGNING_GNUPG_PASSPHRASE or -Psigning.gnupg.passphrase for non-interactive Maven Central bundle signing."
        } else {
            ""
        }
        throw GradleException("gpg sign failed for ${file.name} (exit $exit):\n$output$passphraseHint")
    }
}
