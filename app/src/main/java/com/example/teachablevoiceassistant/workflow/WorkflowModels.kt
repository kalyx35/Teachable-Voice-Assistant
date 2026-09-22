package com.example.teachablevoiceassistant.workflow

import com.example.teachablevoiceassistant.accessibility.UiRole

/** The kinds of user actions a workflow step can describe. */
enum class ActionType {
    CLICK,
    LONG_CLICK,
    TEXT_INPUT,
    FOCUS,
    SCROLL,
    SCREEN_TRANSITION,
    SENSITIVE_BOUNDARY // safe marker: "something sensitive happened here", no content stored
}

/** Screen rectangle. Debug metadata only - never used to decide what an element is. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    override fun toString() = "[$left,$top][$right,$bottom]"
}

/**
 * Semantic description of one UI element (the Day 2 information, frozen in time).
 * Holds no reference to the live AccessibilityNodeInfo, so it is safe to keep.
 */
data class NodeSnapshot(
    val role: UiRole,
    /** Own text. Deliberately null for editable fields (that would be what the user typed). */
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val className: String?,
    val viewIdResourceName: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val isPassword: Boolean,
    val bounds: Bounds,
    val childCount: Int,
    /** Short labels found inside this node. Only collected when the node has no text/description of its own. */
    val descendantTexts: List<String>
) {
    /** Best human-readable name for this element, or null if it has none. */
    val label: String?
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: hintText?.takeIf { it.isNotBlank() }
            ?: descendantTexts.firstOrNull()

    /** Identity used for duplicate detection. Semantic only: no bounds. */
    val identityKey: String
        get() = "$className|$viewIdResourceName|$label"
}

/** Which screen (window) an action happened on. */
data class ScreenInfo(
    val windowClassName: String?,
    val title: String?
)

data class WorkflowStep(
    /** 1-based position in the workflow. */
    val index: Int,
    val actionType: ActionType,
    val packageName: String,
    val screen: ScreenInfo?,
    /** The element acted on, when applicable. */
    val target: NodeSnapshot?,
    /** Typed text (TEXT_INPUT) or scroll direction (SCROLL), when applicable. */
    val inputValue: String?,
    /** Extra explanation, e.g. why a sensitive boundary was recorded. */
    val note: String?,
    /** Epoch milliseconds. */
    val timestamp: Long
)

data class Workflow(
    val id: String,
    val originalCommand: String,
    /** Epoch milliseconds: when teaching started. */
    val createdAt: Long,
    val steps: List<WorkflowStep>
)