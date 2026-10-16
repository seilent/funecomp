plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
