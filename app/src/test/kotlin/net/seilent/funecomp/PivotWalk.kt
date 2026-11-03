package net.seilent.funecomp

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.ui.settingsPivots

fun ComposeTestRule.walkPivots(rows: List<Row>, to: String, from: String? = null, settle: () -> Unit) {
    val pivots = settingsPivots(rows)
    val a = from?.let { pivots.indexOf(it) } ?: 0
    val b = pivots.indexOf(to)
    check(a >= 0 && b >= a) { "no walk from $from to $to in $pivots" }
    for (p in pivots.subList(a + 1, b + 1)) {
        onNodeWithTag("pivot.$p").performClick()
        settle()
    }
}
