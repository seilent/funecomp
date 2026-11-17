package net.seilent.funecomp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import net.seilent.funecomp.sources.ArtSources
import net.seilent.funecomp.sources.YouTubeArt
import net.seilent.funekit.artist.Accounts
import net.seilent.funekit.artist.ArtBlocks
import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.Listen
import net.seilent.funekit.artist.PushLedger
import net.seilent.funekit.artist.ScrobbleQueue
import net.seilent.funekit.artist.Sources
import net.seilent.funekit.artist.Wikimedia
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Properties

class InstalledStoresTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = File(context.cacheDir, "stores")

    @After
    fun tearDown() {
        stores.deleteRecursively()
    }

    private fun golden(name: String): ByteArray = javaClass.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }

    private fun props(b: ByteArray) = Properties().apply { load(b.inputStream().reader(Charsets.UTF_8)) }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }

    private fun scratch(c: Context): ContextWrapper = object : ContextWrapper(c) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$SCRATCH.$name", mode)
    }

    @Test
    fun theScrobbleQueueReadsAndCommitsItsOwnLines() {
        val dir = File(stores, "scrobble").apply { mkdirs() }
        when (mode) {
            StoresMode.Mode.LIVE -> File(context.filesDir, "scrobble").listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(dir, it.name)) }
            StoresMode.Mode.GOLDEN -> for (n in listOf(ScrobbleQueue.QUEUE, "${ScrobbleQueue.SENT}.${Accounts.LAST_FM}", "${ScrobbleQueue.SENT}.${Accounts.LISTEN_BRAINZ}")) File(dir, n).writeBytes(golden("vc9/scrobble/$n"))
        }
        val file = File(dir, ScrobbleQueue.QUEUE)
        assertTrue("no ${file.name}", file.isFile)
        val input = file.readText().split('\n').dropLast(1)
        val next = input.first().removePrefix("next\t").toLong()
        val cursors = input.drop(1).filter { it.startsWith("${ScrobbleQueue.DAP}\t") }.map { it.split('\t') }.associate { java.lang.Long.parseUnsignedLong(it[1], 16) to it[2].toLong() }
        val listens = input.drop(1).filter { !it.startsWith("${ScrobbleQueue.DAP}\t") && !it.startsWith("${Listen.MARK}\t") }
        val services = listOf(Accounts.LAST_FM, Accounts.LISTEN_BRAINZ).filter { File(dir, "${ScrobbleQueue.SENT}.$it").isFile }
        val sent = services.associateWith { File(dir, "${ScrobbleQueue.SENT}.$it").readBytes() }
        val q = ScrobbleQueue(dir)
        assertEquals(listens.size, q.size)
        for ((log, seq) in cursors) assertEquals(seq, q.logSeq(log))
        for ((svc, b) in sent) assertEquals(String(b).trim().toLong(), q.cursor(svc))
        val probe = q.add(Listen(System.currentTimeMillis() / 1000, "Probe", "Probe"), services.toSet().ifEmpty { setOf(Accounts.LAST_FM) })!!
        assertTrue(probe.id >= next)
        val v2 = input.drop(1).map { if (it in listens && it.split('\t').size == 6) Listen.parse(it)!!.line() else it }
        assertEquals((listOf("next\t${probe.id + 1}") + v2 + probe.line()).joinToString("") { "$it\n" }, file.readText())
        for ((svc, b) in sent) assertArrayEquals(svc, b, File(dir, "${ScrobbleQueue.SENT}.$svc").readBytes())
    }

    @Test
    fun theArtBlockFileSavesBackByteEqual() {
        val input = when (mode) {
            StoresMode.Mode.LIVE -> File(context.filesDir, "artblock.tsv").takeIf { it.isFile }?.readText() ?: ""
            StoresMode.Mode.GOLDEN -> String(golden("art/artblock.tsv"), Charsets.UTF_8)
        }
        val now = System.currentTimeMillis()
        val want = input.split('\n').filterNot { l ->
            val p = l.split('\t')
            p[0] == "drop" && p.size in 7..8 && p[6] == "clear" && p[4].toLongOrNull()?.let { now - it >= CLEAR_KEEP_MS } == true
        }.joinToString("\n")
        for (ledger in listOf(PushLedger(), null)) {
            val f = File(stores.apply { mkdirs() }, "artblock.tsv")
            f.writeText(input)
            ArtBlocks(f, { now }, ledger).save()
            assertEquals("ledger ${ledger != null}", want, f.readText())
        }
    }

    @Test
    fun theArtMetaSavesBackToEqualKeyAndValueSets() {
        val dir = File(stores, "art").apply { mkdirs() }
        when (mode) {
            StoresMode.Mode.LIVE -> {
                val all = File(context.filesDir, "art").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
                val names = all.mapTo(HashSet()) { it.name }
                val hits = all.filter { f -> f.name.endsWith(".meta") && f.name.removeSuffix(".meta") + ".src" in names && props(f.readBytes()).let { it["state"] == "hit" && HIT_KEYS.containsAll(it.keys) } }.take(MAX_HITS)
                val misses = all.filter { f -> f.name.endsWith(".miss") && props(f.readBytes()).let { it["cleaned"] == CLEANED && it["floor"] != null } }
                val keep = all.filter { it.name.endsWith(".properties") } + misses + hits + hits.map { File(it.parentFile, it.name.removeSuffix(".meta") + ".src") }
                keep.forEach { it.copyTo(File(dir, it.name)) }
            }
            StoresMode.Mode.GOLDEN -> GOLDEN_CACHE.forEach { File(dir, it).writeBytes(golden("cache/$it")) }
        }
        val copied = dir.listFiles().orEmpty().filter { f -> listOf(".meta", ".miss", ".properties").any { f.name.endsWith(it) } }.associate { it.name to props(it.readBytes()) }
        val c = ArtCache(dir)
        for ((n, p) in copied.filterKeys { it.endsWith(".meta") }) {
            val key = p.getValue("key")
            val e = c.hit(key)
            assertNotNull(n, e)
            c.putHit(e!!, c.source(key)!!)
            if (e.sized.isNotEmpty()) c.putSized(key, e.at, e.sized)
            if (e.failed != 0L) c.putFailed(key, e.at, e.failed)
        }
        for ((n, p) in copied.filterKeys { it.endsWith(".miss") }) {
            val key = p.getValue("key")
            val m = c.miss(key)
            assertNotNull(n, m)
            c.putMiss(key, m!!.at, m.sources, m.floor!!, m.keyHash)
        }
        for ((n, p) in copied) assertEquals(n, p, props(File(dir, n).readBytes()))
    }

    @Test
    fun theArtPrefsReadAsTheReleaseRead() {
        when (mode) {
            StoresMode.Mode.LIVE -> {
                val p = context.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)
                val before = p.all
                assertEquals(release(before), Main.sources(context))
                assertEquals(before, p.all)
            }
            StoresMode.Mode.GOLDEN -> {
                val wrapped = scratch(context)
                val p = wrapped.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)
                try {
                    p.edit()
                        .putInt(ArtSources.DEFAULTS, ArtSources.DEFAULTS_REV)
                        .putBoolean(Sources.THE_AUDIO_DB, false)
                        .putBoolean(Sources.DISCOGS, true)
                        .putBoolean(Sources.FANART_TV, true)
                        .putString("fanarttvKey", "fixturekey")
                        .putBoolean(Sources.DEEZER, false)
                        .putBoolean(YouTubeArt.SOURCE, true)
                        .putBoolean(Wikimedia.SOURCE, true)
                        .putString("order", "deezer,youtube,discogs,fanarttv,theaudiodb,commons")
                        .putString("phoneTarget", "1080x2400")
                        .putString("deviceTargets", "v1=480x640")
                        .putInt("sweep", 2)
                        .putBoolean("barescan", true)
                        .commit()
                    val before = p.all
                    assertEquals(release(before), Main.sources(wrapped))
                    assertEquals(before, p.all)
                } finally {
                    p.edit().clear().commit()
                    context.deleteSharedPreferences("$SCRATCH.${Main.ART_PREFS}")
                }
            }
        }
    }

    private fun release(p: Map<String, *>): Sources {
        fun on(k: String) = p[k] as? Boolean ?: true
        val stored = (p["order"] as? String ?: "").split(',').map { it.trim() }.filter { it in ArtSources.ORDER }.distinct()
        val s = ArtSources.DEFAULT.copy(
            theAudioDb = on(Sources.THE_AUDIO_DB),
            discogs = on(Sources.DISCOGS),
            fanartTv = on(Sources.FANART_TV),
            fanartTvKey = p["fanarttvKey"] as? String ?: "",
            deezer = on(Sources.DEEZER),
            order = stored + ArtSources.ORDER.filter { it !in stored },
            commons = on(Wikimedia.SOURCE),
        )
        return YouTubeArt.with(s, on(YouTubeArt.SOURCE))
    }

    companion object {
        private const val SCRATCH = "stores"
        private const val MAX_HITS = 100
        private const val CLEAR_KEEP_MS = 90L * 24 * 60 * 60 * 1000
        private const val CLEANED = "3"
        private val HIT_KEYS = setOf(
            "state", "key", "name", "mbid", "source", "url", "at", "id", "sha", "resolvedBy", "w", "h", "base", "bare", "channel",
            "author", "license", "page", "licenseUrl", "refusedSha", "refusals", "sized", "failed",
        )
        private val GOLDEN_CACHE = listOf(
            "1a3f934c19eb4074b856.miss", "36e8bb102593070cfc91.miss", "bare.properties", "f5a383c16210a8ea4dbf.meta",
            "f5a383c16210a8ea4dbf.src", "fdd73749ac65905f81af.meta", "fdd73749ac65905f81af.src", "names.properties",
        )

        private lateinit var mode: StoresMode.Mode

        @BeforeClass
        @JvmStatic
        fun readMode() {
            val c = InstrumentationRegistry.getInstrumentation().targetContext
            val arg = InstrumentationRegistry.getArguments().getString("stores")
            val art = when (arg) {
                "live" -> c.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).all
                "golden" -> c.getSharedPreferences("$SCRATCH.${Main.ART_PREFS}", Context.MODE_PRIVATE).all
                else -> emptyMap<String, Any>()
            }
            mode = StoresMode.of(arg, art)
        }
    }
}
