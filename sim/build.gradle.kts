import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":proto"))
    testImplementation(libs.junit)
}

application {
    mainClass.set("net.seilent.funecomp.sim.MainKt")
}

tasks.register<Jar>("simJar") {
    group = "funecomp"
    archiveFileName.set("sim.jar")
    manifest { attributes("Main-Class" to "net.seilent.funecomp.sim.MainKt") }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
