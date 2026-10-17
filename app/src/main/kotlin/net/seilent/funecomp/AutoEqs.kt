package net.seilent.funecomp

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import net.seilent.funecomp.artist.AutoEqIndex
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.proto.gen.Tok
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

object AutoEqs {
    private const val KIB = 1024
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "autoeq").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var index: AutoEqIndex? = null

    fun init(c: Context) {
        if (context != null) return
        val app = c.applicationContext
        context = app
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        index = AutoEqIndex(File(app.filesDir, "autoeq"), UrlHttp(), version)
    }

    fun find(query: String, done: (List<AutoEqIndex.Entry>?) -> Unit) = exec.execute {
        val r = try {
            index?.search(query)
        } catch (e: IOException) {
            Log.w(Remote.TAG, "autoeq index: $e")
            null
        }
        main.post { done(r) }
    }

    fun profile(e: AutoEqIndex.Entry, done: (String?) -> Unit) = exec.execute {
        val r = try {
            index?.profile(e)
        } catch (x: IOException) {
            Log.w(Remote.TAG, "autoeq profile: $x")
            null
        }
        main.post { done(r) }
    }

    fun read(uri: Uri, done: (String, String?) -> Unit) = exec.execute {
        val c = context ?: return@execute
        val name = try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: RuntimeException) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
        val text = try {
            c.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(Tok.eq_fileMaxKib.toInt() * KIB + 1)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            Log.w(Remote.TAG, "autoeq file: $e")
            null
        } catch (e: SecurityException) {
            Log.w(Remote.TAG, "autoeq file: $e")
            null
        }
        main.post { done(name, text) }
    }
}
