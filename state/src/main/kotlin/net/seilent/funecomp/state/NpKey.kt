package net.seilent.funecomp.state

sealed interface NpKey {
    data class Lib(val track: Long) : NpKey

    data class Phone(val key: PhoneKey) : NpKey
}
