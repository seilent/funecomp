package net.seilent.funecomp

import android.content.Context

object LyricsMode {
    const val PREFS = "lyricsmode"
    private const val MODE = "mode"

    fun on(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MODE, false)

    fun on(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MODE, on).apply()
}
