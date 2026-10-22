package net.seilent.funecomp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import java.io.IOException

class ScrobbleCapture : ContentProvider() {
    override fun onCreate(): Boolean {
        val c = context ?: return false
        base(c)?.let { b ->
            Scrobbles.capture = Capture(b) { Guarded(it) }
            Log.w(Remote.TAG, "scrobble capture $b")
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    class Guarded(private val http: Http) : Http {
        private fun check(url: String) {
            val host = Uri.parse(url).host.orEmpty()
            if (host in REAL) throw IOException("capture: $host refused")
        }

        override fun get(url: String, headers: Map<String, String>): Response {
            check(url)
            return http.get(url, headers)
        }

        override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
            check(url)
            return http.post(url, headers, body)
        }

        override fun download(url: String, headers: Map<String, String>, sink: (Int, ByteArray, Int) -> Boolean): Response {
            check(url)
            return http.download(url, headers, sink)
        }
    }

    companion object {
        const val PREFS = "scrobcap"
        const val BASE = "base"
        val REAL = setOf("ws.audioscrobbler.com", "api.listenbrainz.org")

        fun base(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BASE, null)?.trim()?.removeSuffix("/")?.takeIf { it.isNotEmpty() }
    }
}
