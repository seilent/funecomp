package net.seilent.funecomp

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult

private val TITLE_TAGS = setOf("primary", "settings.title")

internal fun ComposeTestRule.titleAlpha(tag: String): Float {
    val texts = onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
    val n = texts.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) in TITLE_TAGS } ?: texts.minWith(compareBy<SemanticsNode>({ it.positionInRoot.x }, { it.positionInRoot.y }))
    val out = ArrayList<TextLayoutResult>()
    n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
    return out.single().layoutInput.style.color.alpha
}
