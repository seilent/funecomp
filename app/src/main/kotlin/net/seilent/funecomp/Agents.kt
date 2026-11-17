package net.seilent.funecomp

import net.seilent.funekit.artist.Agent

internal object Agents {
    fun of(version: String) = Agent("funecomp/$version (https://github.com/seilent/funecomp)", "funecomp/$version (github@seilent.net)", "funeOS", "funecomp", version)
}
