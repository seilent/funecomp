package net.seilent.funecomp.state

sealed interface NpKey {
    data class Lib(val track: Long) : NpKey

    data class Phone(val key: PhoneKey) : NpKey

    data class Service(val service: String, val key: Long?) : NpKey
}
