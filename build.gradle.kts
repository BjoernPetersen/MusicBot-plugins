import com.diffplug.spotless.LineEnding
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version Plugin.KOTLIN
    id("com.github.ben-manes.versions") version Plugin.VERSIONS
    id("com.diffplug.spotless") version Plugin.SPOTLESS
    id("io.gitlab.arturbosch.detekt") version Plugin.DETEKT
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
            ktlint(Plugin.KTLINT)
            lineEndings = LineEnding.UNIX
            endWithNewline()
        }
        kotlinGradle {
            ktlint(Plugin.KTLINT)
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
        toolVersion = Plugin.DETEKT
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
        jcenter()
        maven("https://oss.sonatype.org/content/repositories/snapshots") {
            mavenContent {
                snapshotsOnly()
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
