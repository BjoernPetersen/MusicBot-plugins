plugins {
    kotlin("jvm")
    kotlin("kapt")
    id("com.github.johnrengelman.shadow")
}

version = "0.10.0"

dependencies {
    compileOnly(libs.musicbot) {
        isChanging = libs.versions.musicbot.get().contains("SNAPSHOT")
    }

    implementation(libs.nuprocess)

    implementation(libs.moshi.core)
    kapt(libs.moshi.codegen)

    implementation(libs.unixsocket)

    testImplementation(libs.musicbot)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.slf4j.simple)
}
