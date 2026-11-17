import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    api(project(":link"))
    implementation("net.seilent.funekit:core")
    testImplementation(project(":sim"))
    testImplementation(testFixtures(project(":proto")))
    testImplementation(libs.junit)
}
