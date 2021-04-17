import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
}

version = "0.2.0"

tasks {
    val shadowJar by getting(ShadowJar::class) {
        dependsOn("relocateDependencies")
    }
    create<ConfigureShadowRelocation>("relocateDependencies") {
        target = shadowJar
        prefix = "radio"
    }
}

dependencies {
    compileOnly(libs.musicbot) {
        isChanging = libs.versions.musicbot.get().contains("SNAPSHOT")
    }

    implementation(libs.m3uparser) {
        exclude("org.jetbrains.kotlin")
        exclude("org.slf4j")
    }
    implementation(libs.fuzzywuzzy)

    testImplementation(libs.musicbot)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.slf4j.simple)
}
