import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("jvm")

    id("org.jetbrains.dokka") version Plugin.DOKKA

    signing
    `maven-publish`

    id("com.github.johnrengelman.shadow") version Plugin.SHADOW_JAR
}

version = "0.18.0-SNAPSHOT"

tasks {
    "dokka"(DokkaTask::class) {
        outputFormat = "html"
        outputDirectory = "$buildDir/kdoc"
    }

    @Suppress("UNUSED_VARIABLE")
    val dokkaJavadoc by creating(DokkaTask::class) {
        outputFormat = "javadoc"
        outputDirectory = "$buildDir/javadoc"
    }

    @Suppress("UNUSED_VARIABLE")
    val publishedJar by creating(Jar::class) {
        archiveBaseName.set("published-api")
        from(sourceSets["main"].output) {
            exclude("**/*Impl*", "**/META-INF/services/*")
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val javadocJar by creating(Jar::class) {
        dependsOn("dokkaJavadoc")
        archiveClassifier.set("javadoc")
        from("$buildDir/javadoc")
    }

    @Suppress("UNUSED_VARIABLE")
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    shadowJar {
        relocate("org", "shadow.org")
    }
}

dependencies {
    compileOnly(
        group = "com.github.bjoernpetersen",
        name = "musicbot",
        version = Lib.MUSICBOT
    ) {
        isChanging = Lib.MUSICBOT.contains("SNAPSHOT")
    }

    implementation(
        group = "com.google.apis",
        name = "google-api-services-youtube",
        version = Lib.YOUTUBE_API
    ) {
        exclude("com.google.guava")
    }
    implementation(
        group = "com.github.ben-manes.caffeine",
        name = "caffeine",
        version = Lib.CAFFEINE
    )

    testImplementation(
        group = "com.github.bjoernpetersen",
        name = "musicbot",
        version = Lib.MUSICBOT
    )
    testImplementation(
        group = "org.junit.jupiter",
        name = "junit-jupiter-api",
        version = Lib.JUNIT
    )
    testRuntimeOnly(
        group = "org.junit.jupiter",
        name = "junit-jupiter-engine",
        version = Lib.JUNIT
    )
    testRuntimeOnly(
        group = "org.slf4j",
        name = "slf4j-simple",
        version = Lib.SLF4J
    )
}

publishing {
    publications {
        create("Maven", MavenPublication::class) {
            artifact(tasks.getByName("publishedJar"))
            artifact(tasks.getByName("javadocJar"))
            artifact(tasks.getByName("sourcesJar"))

            pom {
                name.set("YouTube Provider for MusicBot")
                description.set("Provides YouTube songs for MusicBot")
                url.set("https://github.com/BjoernPetersen/MusicBot-YouTube")

                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/BjoernPetersen/MusicBot-YouTube.git")
                    developerConnection
                        .set("scm:git:git@github.com:BjoernPetersen/MusicBot-YouTube.git")
                    url.set("https://github.com/BjoernPetersen/MusicBot-YouTube")
                }

                developers {
                    developer {
                        id.set("BjoernPetersen")
                        name.set("Björn Petersen")
                        email.set("pheasn@gmail.com")
                        url.set("https://github.com/BjoernPetersen")
                    }
                }
            }
        }
        repositories {
            maven {
                val releasesRepoUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
                val snapshotsRepoUrl = "https://oss.sonatype.org/content/repositories/snapshots"
                // change to point to your repo, e.g. http://my.org/repo
                url = uri(
                    if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl
                    else releasesRepoUrl
                )
                credentials {
                    username = project.properties["ossrh.username"]?.toString()
                    password = project.properties["ossrh.password"]?.toString()
                }
            }
        }
    }
}

signing {
    sign(publishing.publications.getByName("Maven"))
}
