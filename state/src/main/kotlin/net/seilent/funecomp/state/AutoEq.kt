package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok

data class EqFilter(val type: Int, val hz: Double, val db: Double, val q: Double)

data class Clamp(val filter: Int, val field: String, val from: Double, val to: Double)

data class EqImport(val name: String, val preamp: Double, val filters: List<EqFilter>, val clamps: List<Clamp>)

sealed interface AutoEqResult {
    data class Ok(val eq: EqImport) : AutoEqResult
    data class Refused(val reason: String) : AutoEqResult
}

object AutoEq {
    const val PREAMP = 0
    const val FIELD_PREAMP = "preamp"
    const val FIELD_FC = "fc"
    const val FIELD_GAIN = "gain"
    const val FIELD_Q = "q"
    private const val NAME_MAX_BYTES = 64
    private const val KIB = 1024
    private val SUFFIXES = listOf(" ParametricEQ", " GraphicEQ")
    private val RESERVED = setOf("off", "flat", "custom")
    private val TYPES = mapOf("PK" to Enums.BandType.peak, "LSC" to Enums.BandType.lowShelf, "HSC" to Enums.BandType.highShelf)
    private val NUM = "([-+]?[0-9]+(?:[.,][0-9]+)?)"
    private val PREAMP_LINE = Regex("^Preamp\\s*:\\s*$NUM\\s*dB", RegexOption.IGNORE_CASE)
    private val FILTER_LINE = Regex("^Filter\\s*([0-9]+)\\s*:\\s*(ON|OFF)\\b\\s*(\\S*)(.*)$", RegexOption.IGNORE_CASE)
    private val FC = Regex("\\bFc\\s+$NUM\\s*Hz", RegexOption.IGNORE_CASE)
    private val GAIN = Regex("\\bGain\\s+$NUM\\s*dB", RegexOption.IGNORE_CASE)
    private val Q = Regex("\\bQ\\s+$NUM", RegexOption.IGNORE_CASE)

    fun name(file: String): String {
        var n = file.trim()
        if (n.endsWith(".txt", ignoreCase = true)) n = n.dropLast(4)
        SUFFIXES.forEach { if (n.endsWith(it)) n = n.removeSuffix(it) }
        return n.trim()
    }

    fun validName(n: String): Boolean {
        val bytes = n.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > NAME_MAX_BYTES) return false
        if (bytes.any { (it.toInt() and 0xff) < 0x20 || it.toInt() == 0x7f }) return false
        if ('/' in n || n.startsWith('.') || n.startsWith(' ') || n.endsWith(' ')) return false
        if (SUFFIXES.any { n.endsWith(it) }) return false
        return foldKey(n) !in RESERVED
    }

    fun foldKey(n: String) = buildString { n.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) } }

    fun profile(eq: EqImport): Profile {
        fun r(v: Double, k: Int) = (v * k + if (v < 0) -0.5 else 0.5).toInt()
        return Profile(r(eq.preamp, 10), eq.filters.map { Band(it.type, -1, r(it.hz, 1000), r(it.db, 10), r(it.q, 100)) })
    }

    fun parse(text: String, name: String): AutoEqResult {
        if (text.toByteArray(Charsets.UTF_8).size > Tok.eq_fileMaxKib.toInt() * KIB) return AutoEqResult.Refused("file over ${Tok.eq_fileMaxKib.toInt()} kib")
        var preamp = 0.0
        val filters = ArrayList<EqFilter>()
        for (raw in text.lineSequence()) {
            val line = raw.removePrefix("\uFEFF").trim()
            PREAMP_LINE.find(line)?.let { preamp = num(it.groupValues[1]) }
            val m = FILTER_LINE.find(line) ?: continue
            val n = m.groupValues[1]
            if (m.groupValues[2].equals("OFF", ignoreCase = true)) continue
            val type = TYPES[m.groupValues[3].uppercase()] ?: return AutoEqResult.Refused("filter $n type ${m.groupValues[3].lowercase()}")
            val rest = m.groupValues[4]
            val fc = FC.find(rest)?.let { num(it.groupValues[1]) } ?: return AutoEqResult.Refused("filter $n no fc")
            val gain = GAIN.find(rest)?.let { num(it.groupValues[1]) } ?: return AutoEqResult.Refused("filter $n no gain")
            val q = Q.find(rest)?.let { num(it.groupValues[1]) } ?: return AutoEqResult.Refused("filter $n no q")
            filters += EqFilter(type, fc, gain, q)
        }
        if (filters.isEmpty()) return AutoEqResult.Refused("no filters")
        val max = Tok.eq_bands.toInt()
        if (filters.size > max) return AutoEqResult.Refused("${filters.size} filters, max $max")
        val clamps = ArrayList<Clamp>()
        fun clamp(i: Int, field: String, v: Double, lo: Float, hi: Float): Double {
            val c = v.coerceIn(lo.toString().toDouble(), hi.toString().toDouble())
            if (c != v) clamps += Clamp(i, field, v, c)
            return c
        }
        val p = clamp(PREAMP, FIELD_PREAMP, preamp, Tok.eq_preampMin, Tok.eq_preampMax)
        val out = filters.mapIndexed { i, f ->
            EqFilter(
                f.type,
                clamp(i + 1, FIELD_FC, f.hz, Tok.eq_freqMin, Tok.eq_freqMax),
                clamp(i + 1, FIELD_GAIN, f.db, Tok.eq_gainMin, Tok.eq_gainMax),
                clamp(i + 1, FIELD_Q, f.q, Tok.eq_qMin, Tok.eq_qMax),
            )
        }
        return AutoEqResult.Ok(EqImport(name, p, out, clamps))
    }

    private fun num(s: String) = s.replace(',', '.').toDouble()
}
