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
    api("net.seilent.funekit:artist")
    testImplementation(testFixtures("net.seilent.funekit:artist"))
    testImplementation(project(":push"))
    testImplementation(project(":sim"))
    testImplementation(testFixtures(project(":proto")))
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("http.proxyHost", "127.0.0.1")
    systemProperty("http.proxyPort", "9")
    systemProperty("https.proxyHost", "127.0.0.1")
    systemProperty("https.proxyPort", "9")
    inputs.file(rootProject.file("app/src/androidTest/resources/nemix-cases.tsv")).withPropertyName("nemixCases").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("app/src/androidTest/resources/ne-core.tsv")).withPropertyName("neCore").withPathSensitivity(PathSensitivity.RELATIVE)
}
