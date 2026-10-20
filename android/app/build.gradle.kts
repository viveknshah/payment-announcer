import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.viveknshah.payannouncer"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.viveknshah.payannouncer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").assets.srcDir(
            layout.buildDirectory.dir("generated/configAssets"),
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * One config source: the Python app's config.json at the repository root.
 * Copy it into the APK assets instead of checking in a second copy.
 */
val copyConfigAsset by tasks.registering(Copy::class) {
    from(rootProject.file("../config.json"))
    into(layout.buildDirectory.dir("generated/configAssets"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyConfigAsset) }
tasks.matching { it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(copyConfigAsset) }

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ledger"))
    implementation(project(":storage"))

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
