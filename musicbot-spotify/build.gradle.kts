import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
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
    compileOnly(libs.musicbot) {
        isChanging = libs.versions.musicbot.get().contains("SNAPSHOT")
    }

    implementation(libs.spotify) {
        exclude("org.slf4j")
        exclude("com.google.guava")
        exclude("com.google.inject")
    }

    testImplementation(libs.musicbot)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly(libs.ktor.client)
}
