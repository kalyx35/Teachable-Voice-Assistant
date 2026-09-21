package com.example.teachablevoiceassistant.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Recursively walks an AccessibilityNodeInfo tree and logs every node.
 *
 * Kept separate from the service so it can later be reused/extended
 * (e.g. turned into a screen-snapshot builder for workflow recording).
 */
object AccessibilityNodeDumper {

    private const val TAG = "WorkflowA11yTree"

    // Safety net against pathological or cyclic trees.
    private const val MAX_DEPTH = 50

    /**
     * Dumps the tree starting at [root]. The caller owns [root]
     * and is responsible for recycling it.
     */
    fun dump(root: AccessibilityNodeInfo) {
        Log.d(TAG, "===== NODE TREE START (package=${root.packageName}) =====")
        dumpNode(root, depth = 0)
        Log.d(TAG, "===== NODE TREE END =====")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        Log.d(
            TAG,
            "${"  ".repeat(depth)}[$depth] " +
                    "text='${node.text}' | " +
                    "desc='${node.contentDescription}' | " +
                    "class=${node.className} | " +
                    "id=${node.viewIdResourceName} | " +
                    "clickable=${node.isClickable} | " +
                    "enabled=${node.isEnabled} | " +
                    "bounds=${bounds.toShortString()} | " +
                    "children=${node.childCount}"
        )

        if (depth >= MAX_DEPTH) {
            Log.w(TAG, "Max depth ($MAX_DEPTH) reached, not descending further")
            return
        }

        for (i in 0 until node.childCount) {
            // getChild() can return null if the node disappeared mid-traversal.
            val child = node.getChild(i) ?: continue
            try {
                dumpNode(child, depth + 1)
            } finally {
                child.recycleCompat()
            }
        }
    }
}

/**
 * AccessibilityNodeInfo.recycle() is deprecated and a no-op on API 33+,
 * but still useful on older devices. Wrapped here so the deprecation
 * suppression lives in one place.
 */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleCompat() {
    recycle()
}