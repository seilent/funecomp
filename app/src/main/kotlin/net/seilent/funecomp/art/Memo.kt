package net.seilent.funecomp.art

class Memo<T : Any>(private val capacity: Int = 1) {
    private val values = object : LinkedHashMap<String, T>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > capacity
    }

    @Synchronized
    fun get(key: String, make: () -> T?): T? {
        values[key]?.let { return it }
        val v = make() ?: return null
        values[key] = v
        return v
    }

    @Synchronized
    fun clear() = values.clear()
}
