package net.seilent.funecomp

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRulesTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private val keys = listOf(
        "sharedpref art.xml",
        "sharedpref scrobble.xml",
    )

    private fun excludes(id: Int): Map<String, List<String>> {
        val x = app.resources.getXml(id)
        val out = LinkedHashMap<String, MutableList<String>>()
        var section = ""
        while (x.next() != XmlPullParser.END_DOCUMENT) {
            if (x.eventType != XmlPullParser.START_TAG) continue
            if (x.name == "exclude") out.getOrPut(section) { ArrayList() } += "${x.getAttributeValue(null, "domain")} ${x.getAttributeValue(null, "path")}" else section = x.name
        }
        return out
    }

    @Test
    fun cloudBackupLeavesOutTheCachesAndTransferCarriesAllButTheKeys() {
        val cloud = (keys + listOf("file lyrics/", "file art/", "file thumb/", "file firmware/", "file autoeq/")).sorted()
        val rules = excludes(R.xml.data_extraction_rules)
        assertEquals(cloud, rules["cloud-backup"]?.sorted())
        assertEquals(keys.sorted(), rules["device-transfer"]?.sorted())
        assertEquals(mapOf("full-backup-content" to cloud), excludes(R.xml.backup_rules).mapValues { it.value.sorted() })
    }
}
