plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version Plugin.SHADOW_JAR
}

version = "0.18.0"

tasks {
    shadowJar {
        relocate("org", "shadow.org")
    }
}

repositories {
    jcenter()
    google()
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
