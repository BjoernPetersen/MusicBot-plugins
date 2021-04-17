plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
}

version = "0.19.0"

tasks {
    shadowJar {
        relocate("org", "shadow.org")
    }
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    compileOnly(libs.musicbot) {
        isChanging = libs.versions.musicbot.get().contains("SNAPSHOT")
    }

    implementation(libs.youtube) {
        exclude("com.google.guava")
    }
    implementation(libs.caffeine)

    testImplementation(libs.musicbot)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.slf4j.simple)
}
