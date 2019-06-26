import com.diffplug.spotless.LineEnding
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version Plugin.KOTLIN
    id("com.github.ben-manes.versions") version Plugin.VERSIONS
    id("com.diffplug.gradle.spotless") version Plugin.SPOTLESS
    idea
}

allprojects {
    group = "com.github.bjoernpetersen"

    apply(plugin = "com.diffplug.gradle.spotless")
    apply(plugin = "idea")

    spotless {
        kotlin {
            ktlint()
            lineEndings = LineEnding.UNIX
            endWithNewline()
        }
        kotlinGradle {
            ktlint()
            lineEndings = LineEnding.UNIX
            endWithNewline()
        }
        format("markdown") {
            target("**/*.md")
            lineEndings = LineEnding.UNIX
            endWithNewline()
        }
    }

    idea {
        module {
            isDownloadJavadoc = true
        }
    }

    repositories {
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
            kotlinOptions.jvmTarget = "1.8"
        }

        withType<Test> {
            useJUnitPlatform()
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
