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
    api(project(":proto"))
    testImplementation("net.seilent.funekit:core")
    testImplementation(libs.junit)
}

tasks.test {
    inputs.file(rootProject.file("app/src/androidTest/resources/svc-cases.tsv")).withPropertyName("svcCases").withPathSensitivity(PathSensitivity.RELATIVE)
}
