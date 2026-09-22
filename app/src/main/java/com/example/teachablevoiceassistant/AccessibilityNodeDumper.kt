package com.example.teachablevoiceassistant.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * What kind of UI element a node most likely is.
 *
 * Decided ONLY from class name, node flags and hierarchy.
 * Screen coordinates are never used to decide meaning.
 */
enum class UiRole {
    EDIT_TEXT,
    CHECKBOX,
    SWITCH,
    RADIO_BUTTON,
    BUTTON,
    LIST_ITEM,
    SCROLLABLE_CONTAINER,
    CLICKABLE_CONTAINER, // clickable and has children (e.g. a tappable card or row)
    CLICKABLE,           // clickable leaf (e.g. a tappable icon or text)
    NONE;                // not an interactive element

    val isInteractive: Boolean get() = this != NONE
}

/**
 * Plain-data copy of one AccessibilityNodeInfo. It holds no reference to the
 * real node, so it stays valid after the node has been recycled.
 */
data class UiNode(
    val depth: Int,
    val role: UiRole,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isFocusable: Boolean,
    val isFocused: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    val isVisibleToUser: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val boundsInScreen: Rect, // debugging metadata only
    val childCount: Int,      // as reported by Android (may be more than children.size)
    val children: List<UiNode>
) {
    /** Best human-readable name: text, then contentDescription, then the id name. */
    val label: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: viewId?.substringAfter(":id/")
            ?: "(no label)"
}

/** The whole visible UI of one window at one moment. */
data class UiTreeSnapshot(
    val packageName: String,
    val root: UiNode,
    val nodeCount: Int,
    val hiddenNodesSkipped: Int,
    val wasTruncated: Boolean
)

/**
 * Turns the live AccessibilityNodeInfo tree into a [UiTreeSnapshot] and logs it.
 *
 * - capture(): build the structured snapshot (reusable later for semantic matching).
 * - dump():    capture + print it to Logcat.
 */
object AccessibilityNodeDumper {

    private const val TAG = "WorkflowA11yTree"

    // Safety limits so a huge or strange tree can never freeze or crash the service.
    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 2000

    // Only affects logging: keeps very long texts from flooding Logcat.
    private const val MAX_LOGGED_TEXT = 80

    /** Small mutable bag used while walking the tree. */
    private class BuildState(val includeInvisible: Boolean) {
        var nodeCount = 0
        var hiddenSkipped = 0
        var truncated = false
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Captures and logs the tree under [root]. The caller owns [root] and
     * must recycle it. Never throws.
     */
    fun dump(root: AccessibilityNodeInfo, trigger: String = "manual") {
        try {
            val snapshot = capture(root)
            if (snapshot == null) {
                Log.w(TAG, "Could not read the root node (trigger=$trigger)")
                return
            }
            logSnapshot(snapshot, trigger)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump UI tree (trigger=$trigger)", e)
        }
    }

    /**
     * Builds a structured snapshot of the tree under [root].
     * Nodes that are not visible to the user are skipped unless [includeInvisible] is true.
     */
    fun capture(root: AccessibilityNodeInfo, includeInvisible: Boolean = false): UiTreeSnapshot? {
        val state = BuildState(includeInvisible)
        val rootNode = buildNode(root, depth = 0, parentIsList = false, state = state) ?: return null
        return UiTreeSnapshot(
            packageName = root.packageName?.toString() ?: "unknown",
            root = rootNode,
            nodeCount = state.nodeCount,
            hiddenNodesSkipped = state.hiddenSkipped,
            wasTruncated = state.truncated
        )
    }

    // ------------------------------------------------------------------
    // Step 1: build the snapshot
    // ------------------------------------------------------------------

    private fun buildNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        parentIsList: Boolean,
        state: BuildState
    ): UiNode? {
        if (state.nodeCount >= MAX_NODES) {
            state.truncated = true
            return null
        }

        // One broken node must never break the whole dump.
        try {
            val visible = node.isVisibleToUser
            if (depth > 0 && !visible && !state.includeInvisible) {
                state.hiddenSkipped++
                return null
            }
            state.nodeCount++

            val className = node.className?.toString()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val childCount = node.childCount
            val isListContainer = isListContainer(node, className)

            val children = mutableListOf<UiNode>()
            if (depth >= MAX_DEPTH) {
                if (childCount > 0) state.truncated = true
            } else {
                for (i in 0 until childCount) {
                    val child = node.childOrNull(i) ?: continue // getChild() can be null
                    try {
                        buildNode(child, depth + 1, isListContainer, state)?.let { children.add(it) }
                    } finally {
                        child.recycleCompat()
                    }
                }
            }

            return UiNode(
                depth = depth,
                role = classify(node, className, parentIsList),
                className = className,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                viewId = node.viewIdResourceName,
                isClickable = node.isClickable,
                isLongClickable = node.isLongClickable,
                isFocusable = node.isFocusable,
                isFocused = node.isFocused,
                isScrollable = node.isScrollable,
                isEditable = node.isEditable,
                isEnabled = node.isEnabled,
                isVisibleToUser = visible,
                isCheckable = node.isCheckable,
                isChecked = node.isChecked,
                boundsInScreen = bounds,
                childCount = childCount,
                children = children
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skipping unreadable node at depth $depth: ${e.message}")
            return null
        }
    }

    /** getChild() wrapped so an odd node can't throw out of the loop. */
    private fun AccessibilityNodeInfo.childOrNull(index: Int): AccessibilityNodeInfo? =
        try {
            getChild(index)
        } catch (e: Exception) {
            null
        }

    /** Is this node a list/grid whose direct children are list items? */
    internal fun isListContainer(node: AccessibilityNodeInfo, className: String?): Boolean {
        val simpleName = className?.substringAfterLast('.').orEmpty()
        return node.collectionInfo != null ||
                simpleName.endsWith("RecyclerView") ||
                simpleName.endsWith("ListView") ||
                simpleName.endsWith("GridView")
    }

    /**
     * Decides the role of a node. Order matters: the first matching rule wins.
     * Uses class name, flags and parent info only - never coordinates.
     */
    internal fun classify(node: AccessibilityNodeInfo, className: String?, parentIsList: Boolean): UiRole {
        val simpleName = className?.substringAfterLast('.').orEmpty()

        return when {
            node.isEditable || simpleName.endsWith("EditText") -> UiRole.EDIT_TEXT
            simpleName.endsWith("CheckBox") -> UiRole.CHECKBOX
            simpleName.contains("Switch") || simpleName.endsWith("ToggleButton") -> UiRole.SWITCH
            simpleName.endsWith("RadioButton") -> UiRole.RADIO_BUTTON
            simpleName.endsWith("Button") -> UiRole.BUTTON // Button, ImageButton, MaterialButton, FAB...
            node.isCheckable -> UiRole.CHECKBOX            // custom checkable widget
            parentIsList || node.collectionItemInfo != null -> UiRole.LIST_ITEM
            node.isScrollable -> UiRole.SCROLLABLE_CONTAINER
            node.isClickable && node.childCount > 0 -> UiRole.CLICKABLE_CONTAINER
            node.isClickable -> UiRole.CLICKABLE
            else -> UiRole.NONE
        }
    }

    // ------------------------------------------------------------------
    // Step 2: log the snapshot
    // ------------------------------------------------------------------

    private fun logSnapshot(snapshot: UiTreeSnapshot, trigger: String) {
        Log.d(TAG, "===== UI TREE START =====")
        Log.d(
            TAG,
            "package=${snapshot.packageName} | trigger=$trigger | " +
                    "nodes=${snapshot.nodeCount} | hiddenNodesSkipped=${snapshot.hiddenNodesSkipped}"
        )
        if (snapshot.wasTruncated) {
            Log.w(TAG, "Tree was truncated (limits: depth=$MAX_DEPTH, nodes=$MAX_NODES)")
        }
        Log.d(
            TAG,
            "Legend: '* [ROLE]' = interactive element | flags lists only properties that are true " +
                    "(plus DISABLED/HIDDEN) | bounds are debug metadata only"
        )

        logNode(snapshot.root)
        logInteractiveSummary(snapshot)

        Log.d(TAG, "===== UI TREE END =====")
    }

    private fun logNode(node: UiNode) {
        val indent = "  ".repeat(node.depth)
        val marker = if (node.role.isInteractive) "* [${node.role}] " else ""
        val shownChildren = node.children.size
        val children =
            if (shownChildren == node.childCount) "${node.childCount}"
            else "${node.childCount} ($shownChildren shown)"

        Log.d(
            TAG,
            "$indent[d${node.depth}] $marker${node.className} " +
                    "text=${quote(node.text)} " +
                    "desc=${quote(node.contentDescription)} " +
                    "id=${node.viewId} " +
                    "flags=${flagSummary(node)} " +
                    "bounds=${node.boundsInScreen.toShortString()} " +
                    "children=$children"
        )

        node.children.forEach { logNode(it) }
    }

    /** A short list of just the interactive elements, for quick scanning. */
    private fun logInteractiveSummary(snapshot: UiTreeSnapshot) {
        val interactive = mutableListOf<UiNode>()
        collectInteractive(snapshot.root, interactive)

        Log.d(TAG, "--- Interactive elements (${interactive.size}) ---")
        interactive.forEach { el ->
            val state = buildString {
                if (el.isCheckable) append(if (el.isChecked) " (checked)" else " (unchecked)")
                if (!el.isEnabled) append(" DISABLED")
            }
            Log.d(TAG, "  [${el.role}] ${quote(el.label)} id=${el.viewId}$state")
        }
    }

    private fun collectInteractive(node: UiNode, out: MutableList<UiNode>) {
        if (node.role.isInteractive) out.add(node)
        node.children.forEach { collectInteractive(it, out) }
    }

    private fun flagSummary(node: UiNode): String {
        val flags = mutableListOf<String>()
        if (node.isClickable) flags.add("clickable")
        if (node.isLongClickable) flags.add("longClickable")
        if (node.isFocusable) flags.add("focusable")
        if (node.isFocused) flags.add("focused")
        if (node.isScrollable) flags.add("scrollable")
        if (node.isEditable) flags.add("editable")
        if (node.isCheckable) flags.add(if (node.isChecked) "checked" else "unchecked")
        flags.add(if (node.isEnabled) "enabled" else "DISABLED")
        flags.add(if (node.isVisibleToUser) "visible" else "HIDDEN")
        return flags.joinToString(",", "[", "]")
    }

    /** Quotes a string for logging, flattening newlines and clipping very long text. */
    private fun quote(value: String?): String {
        if (value == null) return "null"
        val oneLine = value.replace('\n', ' ')
        val clipped =
            if (oneLine.length > MAX_LOGGED_TEXT) oneLine.take(MAX_LOGGED_TEXT) + "..." else oneLine
        return "\"$clipped\""
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