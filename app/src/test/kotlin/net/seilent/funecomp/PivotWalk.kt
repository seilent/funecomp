package net.seilent.funecomp

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.settingsPivots
import kotlin.math.abs

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

internal fun ComposeTestRule.pivotNodes(): List<SemanticsNode> = onAllNodes(SemanticsMatcher("pivot") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("pivot.") == true }, useUnmergedTree = true)
    .fetchSemanticsNodes().sortedBy { it.positionInRoot.x }

internal fun ComposeTestRule.activePivot(page: String): String {
    val c = onNodeWithTag("hub.$page").fetchSemanticsNode().boundsInRoot
    val u = Ui.of(c.width, c.height).u
    val node = pivotNodes().firstOrNull { abs(it.positionInRoot.x - c.left - Tok.hubEdge * u) < 1f } ?: error("no active pivot on $page")
    return node.config[SemanticsProperties.TestTag].removePrefix("pivot.")
}

internal fun ComposeTestRule.pivotTo(page: String, label: String, settle: (Long) -> Unit) {
    repeat(10) {
        val ns = pivotNodes()
        val active = activePivot(page)
        if (active == label) return
        val next = ns.getOrNull(ns.indexOfFirst { it.config[SemanticsProperties.TestTag] == "pivot.$active" } + 1) ?: error("no pivot $label on $page")
        onNodeWithTag(next.config[SemanticsProperties.TestTag], useUnmergedTree = true).performClick()
        settle(1000)
    }
    error("no pivot $label on $page")
}
