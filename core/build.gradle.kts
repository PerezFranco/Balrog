// Platform-independent heart of Balrog: the vault's cryptography, its file
// format, and sync. Plain Kotlin/JVM so the Android app and the desktop app
// share one implementation, and so it can be tested without an Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Android ships org.json itself; the desktop app brings its own copy.
    // Only use the subset of the API that Android has.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}
