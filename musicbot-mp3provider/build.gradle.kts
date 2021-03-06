import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
}

version = "0.9.0"

tasks {
    val shadowJar by getting(ShadowJar::class) {
        dependsOn("relocateDependencies")
    }
    create<ConfigureShadowRelocation>("relocateDependencies") {
        target = shadowJar
    }
}

dependencies {
    compileOnly(libs.musicbot) {
        isChanging = libs.versions.musicbot.get().contains("SNAPSHOT")
    }

    implementation(libs.id3tag)
    implementation(libs.fuzzywuzzy)

    // Used for PlaylistMp3Suggester
    implementation(libs.m3uparser) {
        isChanging = libs.versions.m3uparser.get().contains("SNAPSHOT")
        exclude("org.jetbrains.kotlin")
        exclude("org.slf4j")
    }

    testImplementation(libs.musicbot)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly(libs.sqlite)
}
