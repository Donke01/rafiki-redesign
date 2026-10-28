package org.rafikioutreach.pin

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult

/** The owner's layout rules, checked on every text inside a tagged part of a screen (2.26.0):
 *  no word broken across lines, nothing cut off or ellipsised, nothing past the right edge of the screen. */
object LayoutCheck {
    fun problems(rule: ComposeTestRule, root: String, screenW: Float): List<String> =
        rule.onAllNodes(hasAnyAncestor(hasTestTag(root)), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { n -> problem(n, screenW) } +
            rule.onAllNodes(hasTestTag(root), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { n -> problem(n, screenW) }

    fun problem(n: SemanticsNode, screenW: Float): String? {
        val r = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(r)
        val l = r.firstOrNull() ?: return null
        val s = l.layoutInput.text.text
        if (s.isBlank()) return null
        for (i in 0 until l.lineCount - 1) {
            val e = l.getLineEnd(i)
            if (e in 1 until s.length && !s[e - 1].isWhitespace() && !s[e].isWhitespace() && s[e - 1] != '-' && s[e - 1] != '/' && s[e - 1] != '⁠') return "broken mid-word: \"$s\""
        }
        // cut: more lines than allowed (ellipsis), taller than its box, or one line that needs more width than it has
        // (a word wider than its box shows up above, as a word broken across lines)
        val wide = l.lineCount == 1 && l.multiParagraph.intrinsics.maxIntrinsicWidth > l.size.width + 1f
        if (l.multiParagraph.didExceedMaxLines || l.didOverflowHeight || wide) return "cut off: \"$s\" (${l.size}, lines ${l.lineCount}, need ${l.multiParagraph.intrinsics.maxIntrinsicWidth})"
        if (n.boundsInRoot.right > screenW + 1f || n.boundsInRoot.left < -1f) return "past the screen edge: \"$s\""
        return null
    }

    fun text(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
}
