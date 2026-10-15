import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("funecomp.gen")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    testImplementation(libs.junit)
}

val vectors = rootProject.layout.projectDirectory.file("third_party/funeos/vectors.txt")
tasks.test {
    inputs.file(vectors).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("funecomp.vectors", "../third_party/funeos/vectors.txt")
}
