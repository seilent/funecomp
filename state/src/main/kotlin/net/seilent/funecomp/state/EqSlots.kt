package net.seilent.funecomp.state

import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.gen.Enums

enum class EqForm { OUTPUT, SLOTS, STAGES }

enum class EqStage { USER, CORRECTION }

enum class EqDefault { CUSTOM, OFF, FILE, GRAPHIC, GRAPHIC_MISSING }

object EqSlots {
    const val HEADPHONES = "headphones"
    const val BLUETOOTH = "bluetooth"
    const val EAR_HEADPHONES = "earheadphones"
    const val EAR_BLUETOOTH = "earbluetooth"
    const val DEFAULT = "default"
    private const val PARAMETRIC_TAIL = " ParametricEQ"
    private const val MISSING = " (missing)"

    fun of(stage: EqStage, output: Int): String = when (stage) {
        EqStage.USER -> if (output == Enums.EqOutput.bt) BLUETOOTH else HEADPHONES
        EqStage.CORRECTION -> if (output == Enums.EqOutput.bt) EAR_BLUETOOTH else EAR_HEADPHONES
    }

    fun ofOutput(output: Int?): String? = when (output) {
        Enums.EqOutput.wired -> HEADPHONES
        Enums.EqOutput.bt -> BLUETOOTH
        else -> null
    }

    fun output(slot: String): Int = if (slot == BLUETOOTH || slot == EAR_BLUETOOTH) Enums.EqOutput.bt else Enums.EqOutput.wired

    fun stage(slot: String): EqStage = if (slot == EAR_HEADPHONES || slot == EAR_BLUETOOTH) EqStage.CORRECTION else EqStage.USER

    private fun base(path: String) = path.substringAfterLast('/')

    private fun strip(name: String, ext: String) = if (name.length > ext.length && name.takeLast(ext.length).lowercase() == ext) name.dropLast(ext.length) else name

    fun sourceName(path: String): String {
        val n = strip(base(path), ".txt")
        return if (n.length > PARAMETRIC_TAIL.length && n.endsWith(PARAMETRIC_TAIL)) n.dropLast(PARAMETRIC_TAIL.length) else n
    }

    fun presetName(path: String): String = strip(base(path), ".ini")

    fun defaultForm(name: String?): EqDefault? {
        val n = name?.uppercase() ?: return null
        if (!n.startsWith("DEFAULT (") || !n.endsWith(")")) return null
        val t = n.substring("DEFAULT (".length, n.length - 1)
        return when {
            t == "CUSTOM" -> EqDefault.CUSTOM
            t == "OFF" -> EqDefault.OFF
            t.startsWith("GRAPHIC ") -> if (t.endsWith(MISSING.uppercase())) EqDefault.GRAPHIC_MISSING else EqDefault.GRAPHIC
            else -> EqDefault.FILE
        }
    }

    fun value(m: EqMirror?): String {
        if (m == null || m.on == false) return "off"
        if (!m.graphic) return profileText(m)
        return presetText(m).let { if (it == "off" || it.startsWith(DEFAULT)) it else "graphic $it" }
    }

    fun profileText(m: EqMirror?): String {
        val k = m?.key ?: return "off"
        if (k == DEFAULT) return defaultName(m.names)
        if (k == "off" || k == "custom") return k
        return listed(m.names, k, m.complete, ::sourceName)
    }

    fun presetText(m: EqMirror?): String {
        val k = m?.graphicKey ?: return "off"
        if (k == DEFAULT) return defaultName(m.graphicNames)
        if ('/' !in k) return k
        return listed(m.graphicNames, k, m.graphicComplete, ::presetName)
    }

    private fun defaultName(list: List<EqName>) = (list.firstOrNull { it.key == DEFAULT }?.name ?: DEFAULT).lowercase()

    private fun listed(list: List<EqName>, k: String, complete: Boolean, name: (String) -> String): String {
        list.firstOrNull { it.key == k }?.let { return it.name ?: name(k) }
        if ('/' !in k) return k
        return if (complete) name(k) + MISSING else name(k)
    }
}
