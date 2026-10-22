import java.net.URLEncoder
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = rootProject.file("app/keystores/keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

val lastFmFile = rootProject.file("app/keystores/lastfm.properties")
val noLastFm = providers.gradleProperty("noLastFm").isPresent
val lastFmHex = Regex("[0-9a-f]{32}")
val lastFm = lastFmFile.takeIf { it.exists() && !noLastFm }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

fun lastFmValue(name: String) = "\"" + lastFm?.getProperty(name)?.trim()?.takeIf { lastFmHex.matches(it) }.orEmpty() + "\""

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
        buildConfigField("String", "LASTFM_KEY", "\"\"")
        buildConfigField("String", "LASTFM_SECRET", "\"\"")
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
            buildConfigField("String", "LASTFM_KEY", lastFmValue("lastfm.key"))
            buildConfigField("String", "LASTFM_SECRET", lastFmValue("lastfm.secret"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

val checkLastFmKey = tasks.register("checkLastFmKey") {
    val file = lastFmFile
    val skip = noLastFm
    val hex = lastFmHex
    doLast {
        if (skip) return@doLast
        check(file.exists()) { "app/keystores/lastfm.properties missing: add lastfm.key and lastfm.secret or pass -PnoLastFm" }
        val p = Properties().apply { file.inputStream().use { load(it) } }
        for (name in listOf("lastfm.key", "lastfm.secret")) {
            check(hex.matches(p.getProperty(name)?.trim().orEmpty())) { "$name in app/keystores/lastfm.properties is not 32 lowercase hex" }
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkLastFmKey) }

val checkNoKeyLeak = tasks.register("checkNoKeyLeak") {
    val root = rootProject.projectDir
    val file = lastFmFile
    rootProject.subprojects.forEach { mustRunAfter(it.tasks.withType<Test>()) }
    doLast {
        if (!file.exists()) {
            logger.lifecycle("no key file, scan skipped")
            return@doLast
        }
        val p = Properties().apply { file.inputStream().use { load(it) } }
        val values = listOf("lastfm.key", "lastfm.secret").mapNotNull { p.getProperty(it)?.trim()?.takeIf { v -> v.isNotEmpty() } }
        val needles = values.flatMap { listOf(it, URLEncoder.encode(it, "UTF-8"), it.uppercase(), it.lowercase()) }.distinct()
        fun scanned(rel: List<String>): Boolean {
            if (rel.isEmpty()) return true
            if (rel.any { it in setOf(".git", ".gradle", ".kotlin") }) return false
            if (rel.take(2) == listOf("app", "keystores")) return false
            val b = rel.indexOf("build")
            return b < 0 || b == rel.lastIndex || rel[b + 1] in setOf("test-results", "reports")
        }
        val hits = root.walkTopDown()
            .onEnter { scanned(it.relativeTo(root).invariantSeparatorsPath.split('/').filter { s -> s.isNotEmpty() }) }
            .filter { it.isFile }
            .filter { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath.split('/')
                val b = rel.indexOf("build")
                b < 0 || b < rel.lastIndex - 1
            }
            .filter { f ->
                val text = String(f.readBytes(), Charsets.ISO_8859_1)
                needles.any { text.contains(it) }
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        check(hits.isEmpty()) { "Last.fm key or secret found in: ${hits.joinToString(", ")}" }
        logger.lifecycle("no key leak in ${root.name}")
    }
}

rootProject.subprojects.forEach { p -> p.tasks.withType<Test>().configureEach { finalizedBy(checkNoKeyLeak) } }

val checkScrobblesOff = tasks.register("checkScrobblesOff") {
    val dir = file("src/test/kotlin")
    doLast {
        val builds = Regex("""buildActivity\(Main::class|ActivityScenario\.launch|buildService\(LinkService::class""")
        val bad = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { builds.containsMatchIn(it) && !it.contains("scrobblesOff(") && !it.contains("Scrobbles.init(") } }
            .map { it.name }.sorted().toList()
        check(bad.isEmpty()) { "builds Main or LinkService without scrobblesOff: ${bad.joinToString(", ")}" }
    }
}

tasks.withType<Test>().configureEach { dependsOn(checkScrobblesOff) }

tasks.named("check") { dependsOn(checkNoGms, checkNoKeyLeak) }
