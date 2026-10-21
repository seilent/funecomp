package net.seilent.funecomp.artist

import java.io.IOException

class LyricChain(
    private val sources: LyricSources,
    private val lrclib: (Lrclib.Query) -> Lrclib.Found?,
    private val netease: (Lrclib.Query) -> Lrclib.Found?,
    private val skip: (String) -> Boolean = { false },
) {
    sealed interface Outcome {
        data object Answered : Outcome
        data class Failed(val e: IOException) : Outcome
        data object Skipped : Outcome
        data object NotAsked : Outcome
    }

    data class Chain(val found: Lrclib.Found?, val outcome: Map<String, Outcome>)

    fun get(q: Lrclib.Query): Chain {
        val outcome = LinkedHashMap<String, Outcome>()
        var synced: Lrclib.Found? = null
        var plain: Lrclib.Found? = null
        for (id in sources.ordered) {
            if (synced != null) {
                outcome[id] = Outcome.NotAsked
                continue
            }
            if (skip(id)) {
                outcome[id] = Outcome.Skipped
                continue
            }
            val found = try {
                if (id == LyricSources.LRCLIB) lrclib(q) else netease(q)
            } catch (e: LyricsFileException) {
                throw e
            } catch (e: IOException) {
                outcome[id] = Outcome.Failed(e)
                continue
            }
            outcome[id] = Outcome.Answered
            if (found?.synced == true) synced = found else if (found != null && plain == null) plain = found
        }
        return Chain(synced ?: plain, outcome)
    }
}
