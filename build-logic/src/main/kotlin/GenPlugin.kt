import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class GenTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spec: DirectoryProperty

    @get:OutputDirectory
    abstract val out: DirectoryProperty

    @TaskAction
    fun run() {
        val dir = spec.get().asFile
        val o = out.get().asFile
        o.mkdirs()
        try {
            File(o, "Proto.kt").writeText(Gen.protoKt(dir))
            File(o, "Tok.kt").writeText(Gen.tokKt(dir))
            File(o, "TokBoard.kt").writeText(Gen.tokBoardKt(dir))
            logger.lifecycle("gen: board tokens ${Gen.boardTokens(dir).joinToString(" ")}")
        } catch (e: GenException) {
            throw GradleException(e.message ?: "gen failed")
        }
    }
}

@CacheableTask
abstract class CheckGenTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spec: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val committed: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun run() {
        val dir = spec.get().asFile
        val have = committed.get().asFile
        val want = try {
            mapOf("Proto.kt" to Gen.protoKt(dir), "Tok.kt" to Gen.tokKt(dir), "TokBoard.kt" to Gen.tokBoardKt(dir))
        } catch (e: GenException) {
            throw GradleException(e.message ?: "gen failed")
        }
        for ((name, text) in want) {
            val f = File(have, name)
            if (!f.isFile || f.readText() != text)
                throw GradleException("checkGen: ${f.relativeTo(dir.parentFile.parentFile)} is not generated from third_party/funeos and PIN, run ./gradlew :proto:gen")
        }
        stamp.get().asFile.writeText(want.values.joinToString("") { it.hashCode().toString() + "\n" })
    }
}

@CacheableTask
abstract class CheckPinTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spec: DirectoryProperty

    @get:Input
    abstract val allowDraft: Property<Boolean>

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun run() {
        try {
            Gen.checkPin(spec.get().asFile, allowDraft.get())
        } catch (e: GenException) {
            throw GradleException(e.message ?: "checkPin failed")
        }
        stamp.get().asFile.writeText(Gen.sha256(File(spec.get().asFile, "PIN")))
    }
}

class GenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val spec = project.rootProject.layout.projectDirectory.dir("third_party/funeos")
        val gen = project.layout.projectDirectory.dir("src/main/kotlin/net/seilent/funecomp/proto/gen")
        project.tasks.register("gen", GenTask::class.java) {
            group = "funecomp"
            this.spec.set(spec)
            out.set(gen)
        }
        val checkGen = project.tasks.register("checkGen", CheckGenTask::class.java) {
            group = "verification"
            this.spec.set(spec)
            committed.set(gen)
            stamp.set(project.layout.buildDirectory.file("checks/checkGen"))
        }
        val checkPinSums = project.tasks.register("checkPinSums", CheckPinTask::class.java) {
            group = "verification"
            this.spec.set(spec)
            allowDraft.set(true)
            stamp.set(project.layout.buildDirectory.file("checks/checkPinSums"))
        }
        val checkPin = project.tasks.register("checkPin", CheckPinTask::class.java) {
            group = "verification"
            this.spec.set(spec)
            allowDraft.set(false)
            stamp.set(project.layout.buildDirectory.file("checks/checkPin"))
        }
        project.tasks.matching { it.name == "compileKotlin" }.configureEach { dependsOn(checkPinSums, checkGen) }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(checkPin, checkGen) }
    }
}
