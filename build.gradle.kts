import com.diffplug.spotless.LineEnding
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.10"
    id("com.github.ben-manes.versions") version "0.34.0"
    id("com.diffplug.spotless") version "5.7.0"
    id("io.gitlab.arturbosch.detekt") version "1.14.2"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    idea
}

tasks {
    dependencyUpdates {
        rejectVersionIf {
            isUnstable(candidate.version, currentVersion)
        }
    }
}

allprojects {
    group = "com.github.bjoernpetersen"

    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "idea")

    spotless {
        kotlin {
            ktlint(libs.versions.ktlint.get())
            lineEndings = LineEnding.UNIX
            endWithNewline()
        }
        kotlinGradle {
            ktlint(libs.versions.ktlint.get())
            lineEndings = LineEnding.UNIX
            endWithNewline()
        }
        format("markdown") {
            target("**/*.md")
            lineEndings = LineEnding.UNIX
            endWithNewline()
        }
    }

    detekt {
        config = files("$rootDir/buildConfig/detekt.yml")
        buildUponDefaultConfig = true
    }

    idea {
        module {
            isDownloadJavadoc = true
        }
    }

    repositories {
        mavenLocal {
            mavenContent {
                snapshotsOnly()
            }
        }
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots") {
            mavenContent {
                snapshotsOnly()
            }
        }
        maven(url = "https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") {
            content {
                includeModule("org.jetbrains.kotlinx", "kotlinx-html-jvm")
            }
        }
    }
}

subprojects {
    tasks {
        withType<KotlinCompile> {
            kotlinOptions {
                jvmTarget = "1.8"
                freeCompilerArgs = listOf(
                    "-Xopt-in=kotlin.RequiresOptIn"
                )
            }
        }

        withType<Test> {
            useJUnitPlatform()
            systemProperties["junit.jupiter.execution.parallel.enabled"] = true
        }

        withType<Jar> {
            from(project.projectDir) {
                include("LICENSE")
            }
        }
    }

    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(1, TimeUnit.MINUTES)
    }
}

fun isUnstable(version: String, currentVersion: String): Boolean {
    val lowerVersion = version.toLowerCase()
    val lowerCurrentVersion = currentVersion.toLowerCase()
    return listOf(
        "alpha",
        "beta",
        "rc",
        "m",
        "eap"
    ).any { it in lowerVersion && it !in lowerCurrentVersion }
}

fun isWrongPlatform(version: String, currentVersion: String): Boolean {
    return "android" in currentVersion && "android" !in version
}
