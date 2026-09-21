package com.example.teachablevoiceassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service foundation.
 *
 * Responsibilities (and nothing more, for now):
 *  1. Receive AccessibilityEvent callbacks and log them.
 *  2. On selected events, fetch the active window's root node and dump its tree.
 */
class WorkflowAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        logEvent(event)

        if (event.eventType in TREE_DUMP_EVENT_TYPES) {
            dumpActiveWindow()
        }
    }

    override fun onInterrupt() {
        // Called when the system wants to interrupt feedback. Nothing to do yet.
        Log.w(TAG, "Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    private fun logEvent(event: AccessibilityEvent) {
        Log.d(
            TAG,
            "EVENT type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                    "package=${event.packageName} " +
                    "class=${event.className} " +
                    "text=${event.text}"
        )
    }

    private fun dumpActiveWindow() {
        // Kotlin property syntax for getRootInActiveWindow(). Can be null, e.g.
        // during window transitions or on secure (FLAG_SECURE) screens.
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow is null - nothing to dump")
            return
        }

        try {
            AccessibilityNodeDumper.dump(root)
        } finally {
            root.recycleCompat()
        }
    }

    companion object {
        private const val TAG = "WorkflowA11y"

        // Events that trigger a full tree dump. Add more types here to dump more often,
        // e.g. AccessibilityEvent.TYPE_VIEW_CLICKED. Avoid TYPE_WINDOW_CONTENT_CHANGED
        // unless you throttle it: it fires very frequently.
        private val TREE_DUMP_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
    }
}