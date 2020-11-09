import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version Plugin.SHADOW_JAR
}

version = "0.21.0"

tasks {
    val shadowJar by getting(ShadowJar::class) {
        dependsOn("relocateDependencies")
    }
    create<ConfigureShadowRelocation>("relocateDependencies") {
        target = shadowJar
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
        group = "se.michaelthelin.spotify",
        name = "spotify-web-api-java",
        version = Lib.SPOTIFY
    ) {
        exclude("org.slf4j")
        exclude("com.google.guava")
        exclude("com.google.inject")
    }

    testImplementation(
        group = "com.github.bjoernpetersen",
        name = "musicbot",
        version = Lib.MUSICBOT
    )
    testRuntimeOnly(
        group = "io.ktor",
        name = "ktor-client-okhttp",
        version = Lib.KTOR
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
