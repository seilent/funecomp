package net.seilent.funecomp

import net.seilent.funecomp.sources.ArtSources

internal object StoresMode {
    enum class Mode { LIVE, GOLDEN }

    fun of(arg: String?, art: Map<String, *>): Mode = when (arg) {
        "live" -> Mode.LIVE.also { require(art[ArtSources.DEFAULTS] == ArtSources.DEFAULTS_REV) { "live stores need an art map at defaults ${ArtSources.DEFAULTS_REV}, found ${art[ArtSources.DEFAULTS]}" } }
        "golden" -> Mode.GOLDEN.also { require(art.isEmpty()) { "golden stores need an empty scratch map, found ${art.keys}" } }
        else -> throw IllegalArgumentException("stores is live or golden, found $arg")
    }
}
