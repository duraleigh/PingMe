// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.fail

/**
 * What a screen-reader user would hit on the screen as drawn (Phase 8, P8.1): a control
 * with no spoken name, and a tap target under 48 dp, the two rules Android's accessibility
 * scanner applies that can be read off the semantics tree.
 */
fun ComposeContentTestRule.accessibilityProblems(): List<String> = audit().problems

/** The problems found, and how many controls were looked at (zero means the walk saw nothing). */
data class AccessibilityReport(
    val problems: List<String>,
    val inspected: Int,
)

fun ComposeContentTestRule.audit(): AccessibilityReport {
    // A sheet, menu, or dialog is a window of its own: every root is walked.
    val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
    val problems = mutableListOf<String>()
    var inspected = 0
    roots.forEach { root ->
        val judge = Judge(density.density, root.size.width, root.size.height)

        fun walk(
            node: SemanticsNode,
            trail: List<String>,
        ) {
            val label = labelOf(node)
            if (judge.isControl(node)) {
                inspected++
                problems += judge.problemsOf(node, label, trail)
            }
            node.children.forEach { walk(it, if (label.isNotBlank()) trail + label else trail) }
        }
        walk(root, emptyList())
    }
    return AccessibilityReport(problems.distinct(), inspected)
}

/** Fails the test with every problem listed, so a run names them all at once. */
fun ComposeContentTestRule.assertAccessible() {
    val report = audit()
    if (report.inspected == 0) fail("The audit saw no controls at all: the screen was not drawn")
    if (report.problems.isNotEmpty()) {
        fail(
            "Accessibility problems (${report.problems.size}) among ${report.inspected} controls:\n" +
                report.problems.joinToString("\n"),
        )
    }
}

/** The two rules, applied to one window of [width] by [height] pixels. */
private class Judge(
    private val density: Float,
    private val width: Int,
    private val height: Int,
) {
    /** Something a finger or a screen reader can act on, drawn on screen. */
    fun isControl(node: SemanticsNode): Boolean {
        val config = node.config
        val actionable =
            config.contains(SemanticsActions.OnClick) ||
                config.contains(SemanticsActions.OnLongClick) ||
                config.contains(SemanticsActions.SetText)
        // Composed but not placed (a lazy list's items beyond the screen) has no size: nothing to judge.
        val placed = node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0
        return actionable && placed && !config.contains(SemanticsProperties.InvisibleToUser)
    }

    fun problemsOf(
        node: SemanticsNode,
        label: String,
        trail: List<String>,
    ): List<String> {
        val found = mutableListOf<String>()
        val place = trail.takeLast(TRAIL).joinToString(" > ").ifEmpty { "top" }
        if (label.isBlank()) {
            val role = node.config.getOrNull(SemanticsProperties.Role)?.toString() ?: "control"
            found += "A $role with no spoken name, under: $place"
        }
        val bounds = node.touchBoundsInRoot
        val w = bounds.width / density
        val h = bounds.height / density
        val onScreen = bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height
        val tooSmall = w < MIN_DP || h < MIN_DP
        val typing = node.config.contains(SemanticsActions.SetText)
        if (onScreen && tooSmall && !typing) {
            found += "'${label.ifBlank { "unnamed" }}' is ${w.toInt()}x${h.toInt()} dp (under $MIN_DP), under: $place"
        }
        return found
    }
}

private fun labelOf(node: SemanticsNode): String {
    val config = node.config
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    val editable = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    val state = config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
    return listOf(description, text, editable, state).filter { it.isNotBlank() }.joinToString(" ").trim()
}

private const val MIN_DP = 48f
private const val TRAIL = 3
