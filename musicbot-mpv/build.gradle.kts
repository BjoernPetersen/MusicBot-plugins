plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version Plugin.SHADOW_JAR
}

version = "0.9.0-SNAPSHOT"

tasks {
    shadowJar {
        relocate("com", "shadow.com")
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
        group = "com.github.bjoernpetersen",
        name = "musicbot-youtube",
        version = Lib.YOUTUBE_PROVIDER
    ) {
        isChanging = Lib.YOUTUBE_PROVIDER.contains("SNAPSHOT")
    }

    implementation(
        group = "com.zaxxer",
        name = "nuprocess",
        version = Lib.NU_PROCESS
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
