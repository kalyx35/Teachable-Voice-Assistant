package com.example.teachablevoiceassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.teachablevoiceassistant.workflow.WorkflowRecorder

/**
 * Accessibility service foundation.
 *
 * Day 2: filtered, debounced UI tree dumps to Logcat, for exploring what the
 * accessibility tree looks like on different screens.
 *
 * Day 3: every event is also forwarded to WorkflowRecorder, which no-ops unless
 * Teach mode is active. While recording, Day 2's tree dump is skipped so a
 * session doesn't also print full-tree dumps to Logcat.
 */
class WorkflowAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTrigger: String = ""
    private val dumpRunnable = Runnable { dumpActiveWindow(pendingTrigger) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        WorkflowRecorder.attachService(this)
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // An uncaught exception here would kill the service, so guard everything.
        try {
            // Day 3: the recorder decides for itself whether this event matters;
            // it does nothing at all when Teach mode is off.
            WorkflowRecorder.onAccessibilityEvent(this, event)

            if (WorkflowRecorder.isRecording) return // keep Logcat clean during a recording session

            val delayMs = DUMP_DELAY_MS_BY_EVENT[event.eventType] ?: return
            logEvent(event)
            scheduleTreeDump(AccessibilityEvent.eventTypeToString(event.eventType), delayMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error while handling accessibility event", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Service unbound")
        WorkflowRecorder.detachService(this)
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        WorkflowRecorder.detachService(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * (Re)starts the timer. If another meaningful event arrives before it fires,
     * the timer restarts, so we only dump once things have quieted down.
     */
    private fun scheduleTreeDump(trigger: String, delayMs: Long) {
        pendingTrigger = trigger
        handler.removeCallbacks(dumpRunnable)
        handler.postDelayed(dumpRunnable, delayMs)
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

    private fun dumpActiveWindow(trigger: String) {
        try {
            // Can be null during window transitions or on secure (FLAG_SECURE) screens.
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "rootInActiveWindow is null - nothing to dump (trigger=$trigger)")
                return
            }

            try {
                AccessibilityNodeDumper.dump(root, trigger)
            } finally {
                root.recycleCompat()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump active window", e)
        }
    }

    companion object {
        private const val TAG = "WorkflowA11y"

        // Which events matter, and how long to wait (ms) before dumping the tree.
        // Add types here to react to more events. Be careful with
        // TYPE_WINDOW_CONTENT_CHANGED and TYPE_VIEW_SCROLLED: they fire constantly.
        private val DUMP_DELAY_MS_BY_EVENT = mapOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED to 400L,
            AccessibilityEvent.TYPE_VIEW_CLICKED to 800L,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED to 800L
        )
    }
}