package com.example.teachablevoiceassistant.workflow

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.teachablevoiceassistant.accessibility.AccessibilityNodeDumper
import com.example.teachablevoiceassistant.accessibility.recycleCompat

/**
 * Converts a live AccessibilityNodeInfo into an immutable [NodeSnapshot].
 * Reuses the role classification and list-detection logic from Day 2's
 * AccessibilityNodeDumper instead of duplicating it.
 */
object NodeSnapshotFactory {

    private const val MAX_DESCENDANT_TEXTS = 5
    private const val MAX_DESCENDANT_SEARCH_NODES = 40
    private const val MAX_DESCENDANT_DEPTH = 6

    /** Builds a snapshot of [node]. Does not recycle [node] - the caller owns it. */
    fun from(node: AccessibilityNodeInfo): NodeSnapshot {
        val className = node.className?.toString()
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val parentIsList = isParentListContainer(node)
        val role = AccessibilityNodeDumper.classify(node, className, parentIsList)

        // Editable fields hold user-entered text - never copy it into `text`.
        // The recorder captures typed values separately, and skips password fields entirely.
        val text = if (node.isEditable) null else node.text?.toString()
        val description = node.contentDescription?.toString()
        val hint = extractHintText(node)

        val descendantTexts =
            if (text.isNullOrBlank() && description.isNullOrBlank()) collectDescendantTexts(node)
            else emptyList()

        return NodeSnapshot(
            role = role,
            text = text,
            contentDescription = description,
            hintText = hint,
            className = className,
            viewIdResourceName = node.viewIdResourceName,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            focusable = node.isFocusable,
            focused = node.isFocused,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            visibleToUser = node.isVisibleToUser,
            checkable = node.isCheckable,
            checked = node.isChecked,
            isPassword = node.isPassword,
            bounds = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            childCount = node.childCount,
            descendantTexts = descendantTexts
        )
    }

    private fun isParentListContainer(node: AccessibilityNodeInfo): Boolean {
        val parent = try {
            node.parent
        } catch (e: Exception) {
            null
        } ?: return false

        return try {
            AccessibilityNodeDumper.isListContainer(parent, parent.className?.toString())
        } finally {
            parent.recycleCompat()
        }
    }

    private fun extractHintText(node: AccessibilityNodeInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            node.hintText?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Short labels found inside [node]'s subtree, used when a container (e.g. a
     * tappable list row) has no text or description of its own. Depth- and
     * count-bounded so it can never hang on a huge subtree.
     */
    private fun collectDescendantTexts(node: AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        val remainingBudget = intArrayOf(MAX_DESCENDANT_SEARCH_NODES)
        collectDescendantTextsRecursive(node, depth = 0, out = results, budget = remainingBudget)
        return results.take(MAX_DESCENDANT_TEXTS)
    }

    private fun collectDescendantTextsRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<String>,
        budget: IntArray
    ) {
        if (depth >= MAX_DESCENDANT_DEPTH) return

        for (i in 0 until node.childCount) {
            if (out.size >= MAX_DESCENDANT_TEXTS || budget[0] <= 0) return
            budget[0]--

            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue

            try {
                val label = child.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (label != null) out.add(label)
                collectDescendantTextsRecursive(child, depth + 1, out, budget)
            } finally {
                child.recycleCompat()
            }
        }
    }
}