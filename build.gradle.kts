plugins {
    kotlin("jvm") version "2.2.10"
    `java-library`
    `maven-publish`
}

group = providers.gradleProperty("group").orNull ?: "dev.jaeyoung"
version = providers.gradleProperty("version").orNull ?: "0.1.0-SNAPSHOT"

description = "Fileloom PDF content/text extraction core library"

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

tasks.register<Zip>("publishToMavenCentralBundle") {
    description = "Builds a Maven Central upload bundle (signed artifacts + checksums) under build/maven-central-bundle/."
    group = "publishing"

    dependsOn("jar", "sourcesJar", "javadocJar", "generatePomFileForMavenJavaPublication")

    val artifactPath = "${project.group.toString().replace('.', '/')}/${project.name}/${project.version}"
    archiveFileName.set("${project.name}-${project.version}-maven-central-bundle.zip")
    destinationDirectory.set(mavenCentralBundleDir)

    val sources = listOf(
        tasks.named<Jar>("jar").map { it.archiveFile.get().asFile },
        tasks.named<Jar>("sourcesJar").map { it.archiveFile.get().asFile },
        tasks.named<Jar>("javadocJar").map { it.archiveFile.get().asFile },
        tasks.named("generatePomFileForMavenJavaPublication").map {
            layout.buildDirectory.file("publications/mavenJava/pom-default.xml").get().asFile
        },
    )

    sources.forEach { provider ->
        from(provider) {
            into(artifactPath)
            rename { name ->
                if (name == "pom-default.xml") {
                    "${project.name}-${project.version}.pom"
                } else {
                    name
                }
            }
        }
    }

    doFirst {
        logger.lifecycle(
            "Note: bundle expects GPG signatures (.asc) and checksums (.md5/.sha1) to be added " +
                "before upload. Run gpg --detach-sign --armor on each artifact, then sha1sum and md5sum."
        )
    }
}
