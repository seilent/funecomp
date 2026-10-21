import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = rootProject.file("app/keystores/keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "net.seilent.funecomp"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.seilent.funecomp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            keystoreProperties?.let {
                storeFile = file(it["storeFile"].toString())
                storePassword = it["storePassword"].toString()
                keyAlias = it["keyAlias"].toString()
                keyPassword = it["keyPassword"].toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProperties != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("shots.dir", layout.buildDirectory.dir("shots").get().asFile.path)
                it.systemProperty("shots.run", providers.gradleProperty("shots").getOrElse("false"))
                it.systemProperty("shots.only", providers.gradleProperty("shotsOnly").getOrElse(""))
                it.systemProperty("shots.backdrop", providers.gradleProperty("shotsBackdrop").getOrElse(""))
                it.systemProperty("shots.input", providers.gradleProperty("shotsInput").getOrElse(""))
                it.maxHeapSize = "4g"
                it.maxParallelForks = 3
            }
        }
    }
}

dependencies {
    implementation(project(":link"))
    implementation(project(":artist"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(project(":sim"))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}

tasks.named("preBuild") {
    dependsOn(":proto:checkPinSums", ":proto:checkGen")
}

val checkNoGms = tasks.register("checkNoGms") {
    val ids = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
        .map { all -> all.map { a -> a.id.componentIdentifier.displayName } }
    inputs.property("ids", ids)
    doLast {
        val bad = ids.get().filter { it.startsWith("com.google.android.gms") || it.startsWith("com.google.firebase") }
        check(bad.isEmpty()) { "release classpath has Google Play Services or Firebase: $bad" }
    }
}

tasks.named("check") { dependsOn(checkNoGms) }
