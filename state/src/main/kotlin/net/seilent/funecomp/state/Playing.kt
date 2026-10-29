package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums

sealed interface Playing {
    data class Library(val track: Long) : Playing

    data class Service(val service: String, val key: Long?) : Playing

    data class Phone(val key: PhoneKey?) : Playing

    data object None : Playing

    companion object {
        fun of(s: State): Playing {
            s.service?.let { return Service(it, s.serviceKey) }
            if (s.source == Enums.Source.phone) return Phone(PhoneKey.of(s))
            return s.library?.let { Library(it) } ?: None
        }
    }
}
