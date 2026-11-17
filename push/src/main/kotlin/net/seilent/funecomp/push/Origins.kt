package net.seilent.funecomp.push

import net.seilent.funekit.artist.ImgOrigin

object Origins {
    fun of(id: Int?): ImgOrigin? = id?.let { ImgOrigin.of(it) }

    fun wire(o: ImgOrigin?): Int? = o?.id
}
