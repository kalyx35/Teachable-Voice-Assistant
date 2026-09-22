package com.example.teachablevoiceassistant.workflow

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.teachablevoiceassistant.accessibility.recycleCompat
import java.util.UUID

/**
 * Records a "Teach" session as an inspectable [Workflow].
 *
 * Singleton so the accessibility service (which receives events) and the app's
 * UI (which starts/stops sessions and shows step counts) can share one recorder
 * without any manual wiring. All access happens on the main thread.
 *
 * Pipeline for one accessibility event:
 *   1. Only run while recording.
 *   2. Only allow six event types through (see [allowedEventTypes]); everything else is noise.
 *   3. Drop events from our own app, the keyboard, the launcher, and system UI.
 *   4. Route to a handler, which coalesces bursts (text typed, scroll) and drops
 *      immediate duplicates (double-delivered taps, redundant focus after a click).
 *   5. Before recording a new screen, check it for sensitive content; if found,
 *      record a boundary marker instead of the screen, and pause step recording
 *      until the app moves to a different screen.
 */
object WorkflowRecorder {

    private const val TAG = "WorkflowRecorder"

    private const val TEXT_COMMIT_DELAY_MS = 1200L
    private const val SCROLL_COMMIT_DELAY_MS = 1200L
    private const val SCREEN_TRANSITION_DELAY_MS = 400L
    private const val DUPLICATE_ACTION_WINDOW_MS = 150L
    private const val FOCUS_CLICK_MERGE_WINDOW_MS = 600L
    private const val MAX_STEPS = 300
    private const val MAX_DURATION_MS = 10 * 60 * 1000L

    private val allowedEventTypes = setOf(
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    )

    private val ignoredSystemPackages = setOf("com.android.systemui")

    private val handler = Handler(Looper.getMainLooper())

    interface Listener {
        fun onStateChanged(recording: Boolean)
        fun onStepCountChanged(count: Int)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener?) {
        listener = l
    }

    // --- live service reference, for reading the screen during sensitivity checks ---
    // Simplification for this stage: the service instance effectively lives as long as
    // the recording session does, so a plain reference (not a weak one) is fine here.
    @Volatile
    private var currentServiceRef: AccessibilityService? = null

    fun attachService(service: AccessibilityService) {
        currentServiceRef = service
    }

    fun detachService(service: AccessibilityService) {
        if (currentServiceRef === service) currentServiceRef = null
    }

    // --- session state ---
    @Volatile
    var isRecording = false
        private set

    private var workflowId: String = ""
    private var originalCommand: String = ""
    private var createdAt: Long = 0L
    private val steps = mutableListOf<WorkflowStep>()

    private var hostPackageName: String? = null
    private var keyboardPackageName: String? = null
    private var launcherPackageName: String? = null

    private var currentScreen: ScreenInfo? = null
    private var currentPackage: String? = null
    private var sensitivePausedForCurrentScreen = false

    // pending coalesced steps
    private var pendingTextTarget: NodeSnapshot? = null
    private var pendingTextValue: String? = null
    private var pendingTextPackage: String? = null
    private val commitTextRunnable = Runnable { commitPendingText() }

    private var pendingScrollTarget: NodeSnapshot? = null
    private var pendingScrollDirection: String? = null
    private var pendingScrollPackage: String? = null
    private val commitScrollRunnable = Runnable { commitPendingScroll() }

    private var pendingScreen: ScreenInfo? = null
    private var pendingScreenPackage: String? = null
    private val commitScreenRunnable = Runnable { commitPendingScreen() }

    private var timeoutRunnable: Runnable? = null

    // ------------------------------------------------------------------
    // Session control
    // ------------------------------------------------------------------

    fun start(context: Context, originalCommand: String) {
        if (isRecording) return

        workflowId = UUID.randomUUID().toString()
        this.originalCommand = originalCommand
        createdAt = System.currentTimeMillis()
        steps.clear()

        hostPackageName = context.packageName
        keyboardPackageName = resolveKeyboardPackage(context)
        launcherPackageName = resolveLauncherPackage(context)

        currentScreen = null
        currentPackage = null
        sensitivePausedForCurrentScreen = false
        clearPending()

        isRecording = true
        listener?.onStateChanged(true)
        listener?.onStepCountChanged(0)

        val timeout = Runnable {
            Log.w(TAG, "Max recording duration reached, stopping automatically")
            stop()
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, MAX_DURATION_MS)

        Log.i(TAG, "Recording started: id=$workflowId command=$originalCommand")
    }

    fun stop(): Workflow? {
        if (!isRecording) return null
        isRecording = false

        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null

        // Flush pending coalesced steps in the order they were opened.
        handler.removeCallbacks(commitScreenRunnable)
        commitPendingScreen()
        handler.removeCallbacks(commitTextRunnable)
        commitPendingText()
        handler.removeCallbacks(commitScrollRunnable)
        commitPendingScroll()

        val workflow = Workflow(workflowId, originalCommand, createdAt, steps.toList())
        WorkflowRepository.save(workflow)
        listener?.onStateChanged(false)

        Log.i(TAG, "Recording stopped: ${workflow.steps.size} step(s)")
        return workflow
    }

    // ------------------------------------------------------------------
    // Event intake
    // ------------------------------------------------------------------

    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (!isRecording) return
        if (event.eventType !in allowedEventTypes) return

        currentServiceRef = service

        val pkg = event.packageName?.toString()
        if (pkg.isNullOrBlank() || isIgnoredPackage(pkg)) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChanged(event, pkg)
                AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event, pkg, longClick = false)
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> handleClick(event, pkg, longClick = true)
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event, pkg)
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocus(event, pkg)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event, pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling event during recording", e)
        }

        if (steps.size >= MAX_STEPS) {
            Log.w(TAG, "Max step count ($MAX_STEPS) reached, stopping automatically")
            handler.post { stop() }
        }
    }

    private fun isIgnoredPackage(pkg: String): Boolean =
        pkg == hostPackageName ||
                pkg == keyboardPackageName ||
                pkg == launcherPackageName ||
                pkg in ignoredSystemPackages

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private fun handleWindowChanged(event: AccessibilityEvent, pkg: String) {
        // The field/list mid-interaction is going away with this screen - flush now
        // rather than waiting for the debounce delay.
        handler.removeCallbacks(commitTextRunnable)
        commitPendingText()
        handler.removeCallbacks(commitScrollRunnable)
        commitPendingScroll()

        val className = event.className?.toString()
        // For TYPE_WINDOW_STATE_CHANGED, Android commonly puts the window title in event.text.
        val title = event.text?.joinToString(" ")?.takeIf { it.isNotBlank() }

        pendingScreen = ScreenInfo(className, title)
        pendingScreenPackage = pkg
        handler.removeCallbacks(commitScreenRunnable)
        handler.postDelayed(commitScreenRunnable, SCREEN_TRANSITION_DELAY_MS)
    }

    private fun handleClick(event: AccessibilityEvent, pkg: String, longClick: Boolean) {
        if (sensitivePausedForCurrentScreen) return
        val sourceNode = event.source ?: return
        try {
            val snapshot = safeSnapshot(sourceNode) ?: return
            if (snapshot.isPassword) return
            if (snapshot.label == null && snapshot.viewIdResourceName == null) return // nothing to identify it by

            val actionType = if (longClick) ActionType.LONG_CLICK else ActionType.CLICK

            // A focus event for this same element right before this click is redundant - drop it.
            val last = steps.lastOrNull()
            if (last?.actionType == ActionType.FOCUS && isSameRecentTarget(last, snapshot.identityKey, FOCUS_CLICK_MERGE_WINDOW_MS)) {
                steps.removeAt(steps.size - 1)
            }

            if (isDuplicateOfLast(actionType, snapshot.identityKey)) return

            addStep(actionType, pkg, currentScreen, snapshot, inputValue = null, note = null)
        } finally {
            sourceNode.recycleCompat()
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent, pkg: String) {
        if (sensitivePausedForCurrentScreen) return
        val sourceNode = event.source ?: return
        val snapshot = try {
            safeSnapshot(sourceNode)
        } finally {
            sourceNode.recycleCompat()
        } ?: return

        if (snapshot.isPassword) return // never buffer text from a password field

        val latestText = event.text?.joinToString("") ?: ""

        // A different field started typing before the previous one committed - flush it first.
        if (pendingTextTarget != null && pendingTextTarget?.identityKey != snapshot.identityKey) {
            commitPendingText()
        }

        pendingTextTarget = snapshot
        pendingTextValue = latestText
        pendingTextPackage = pkg
        handler.removeCallbacks(commitTextRunnable)
        handler.postDelayed(commitTextRunnable, TEXT_COMMIT_DELAY_MS)
    }

    private fun handleFocus(event: AccessibilityEvent, pkg: String) {
        if (sensitivePausedForCurrentScreen) return
        val sourceNode = event.source ?: return
        try {
            if (!sourceNode.isEditable) return // focus is only useful for input fields here
            val snapshot = safeSnapshot(sourceNode) ?: return
            if (snapshot.isPassword) return

            // A click on this same element just happened - the click already covers it.
            val last = steps.lastOrNull()
            if (last != null &&
                (last.actionType == ActionType.CLICK || last.actionType == ActionType.LONG_CLICK) &&
                isSameRecentTarget(last, snapshot.identityKey, FOCUS_CLICK_MERGE_WINDOW_MS)
            ) {
                return
            }

            if (isDuplicateOfLast(ActionType.FOCUS, snapshot.identityKey)) return

            addStep(ActionType.FOCUS, pkg, currentScreen, snapshot, inputValue = null, note = null)
        } finally {
            sourceNode.recycleCompat()
        }
    }

    private fun handleScroll(event: AccessibilityEvent, pkg: String) {
        if (sensitivePausedForCurrentScreen) return
        val sourceNode = event.source ?: return
        val snapshot = try {
            safeSnapshot(sourceNode)
        } finally {
            sourceNode.recycleCompat()
        } ?: return

        val direction = scrollDirection(event)

        if (pendingScrollTarget != null && pendingScrollTarget?.identityKey != snapshot.identityKey) {
            commitPendingScroll()
        }

        pendingScrollTarget = snapshot
        pendingScrollDirection = direction
        pendingScrollPackage = pkg
        handler.removeCallbacks(commitScrollRunnable)
        handler.postDelayed(commitScrollRunnable, SCROLL_COMMIT_DELAY_MS)
    }

    // ------------------------------------------------------------------
    // Coalesced-step commits
    // ------------------------------------------------------------------

    private fun commitPendingScreen() {
        val screen = pendingScreen ?: return
        val pkg = pendingScreenPackage ?: return
        pendingScreen = null
        pendingScreenPackage = null

        // Only a genuine change of screen counts - not a re-delivery of the same one.
        if (pkg == currentPackage && screen.windowClassName == currentScreen?.windowClassName) return

        currentPackage = pkg
        currentScreen = screen

        val sensitive = checkSensitivity(pkg)
        if (sensitive != false) { // true, or null/unknown - both mean "pause"
            sensitivePausedForCurrentScreen = true
            val last = steps.lastOrNull()
            if (last?.actionType != ActionType.SENSITIVE_BOUNDARY) {
                val note = if (sensitive == null) {
                    "Screen could not be verified as safe, so recording paused here"
                } else {
                    "Sensitive screen detected (login/OTP/payment); recording paused here"
                }
                addStep(ActionType.SENSITIVE_BOUNDARY, pkg, screen, target = null, inputValue = null, note = note)
            }
            return
        }

        sensitivePausedForCurrentScreen = false
        addStep(ActionType.SCREEN_TRANSITION, pkg, screen, target = null, inputValue = null, note = null)
    }

    private fun commitPendingText() {
        val target = pendingTextTarget ?: return
        val value = pendingTextValue
        val pkg = pendingTextPackage ?: currentPackage ?: hostPackageName
        pendingTextTarget = null
        pendingTextValue = null
        pendingTextPackage = null

        if (pkg == null || sensitivePausedForCurrentScreen || target.isPassword) return
        addStep(ActionType.TEXT_INPUT, pkg, currentScreen, target, inputValue = value, note = null)
    }

    private fun commitPendingScroll() {
        val target = pendingScrollTarget ?: return
        val direction = pendingScrollDirection
        val pkg = pendingScrollPackage ?: currentPackage ?: hostPackageName
        pendingScrollTarget = null
        pendingScrollDirection = null
        pendingScrollPackage = null

        if (pkg == null || sensitivePausedForCurrentScreen) return
        addStep(ActionType.SCROLL, pkg, currentScreen, target, inputValue = direction, note = null)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun addStep(
        actionType: ActionType,
        pkg: String,
        screen: ScreenInfo?,
        target: NodeSnapshot?,
        inputValue: String?,
        note: String?
    ) {
        val step = WorkflowStep(
            index = steps.size + 1,
            actionType = actionType,
            packageName = pkg,
            screen = screen,
            target = target,
            inputValue = inputValue,
            note = note,
            timestamp = System.currentTimeMillis()
        )
        steps.add(step)
        Log.d(TAG, "Step ${step.index}: ${step.actionType} pkg=${step.packageName} target=${target?.label}")
        listener?.onStepCountChanged(steps.size)
    }

    private var lastActionKey: String? = null
    private var lastActionAtMs: Long = 0L

    /** True if this exact action on this exact element was just recorded a moment ago. */
    private fun isDuplicateOfLast(actionType: ActionType, identityKey: String): Boolean {
        val key = "$actionType|$identityKey"
        val now = System.currentTimeMillis()
        val duplicate = key == lastActionKey && now - lastActionAtMs < DUPLICATE_ACTION_WINDOW_MS
        lastActionKey = key
        lastActionAtMs = now
        return duplicate
    }

    private fun isSameRecentTarget(step: WorkflowStep, identityKey: String, windowMs: Long): Boolean {
        val target = step.target ?: return false
        return target.identityKey == identityKey && (System.currentTimeMillis() - step.timestamp) < windowMs
    }

    private fun safeSnapshot(node: android.view.accessibility.AccessibilityNodeInfo): NodeSnapshot? =
        try {
            NodeSnapshotFactory.from(node)
        } catch (e: Exception) {
            Log.w(TAG, "Could not snapshot node: ${e.message}")
            null
        }

    private fun scrollDirection(event: AccessibilityEvent): String =
        try {
            val dy = event.scrollDeltaY
            val dx = event.scrollDeltaX
            when {
                dy > 0 -> "down"
                dy < 0 -> "up"
                dx > 0 -> "right"
                dx < 0 -> "left"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }

    /** @return true if the screen looks sensitive, false if safe, null if it could not be checked. */
    private fun checkSensitivity(expectedPackage: String): Boolean? {
        val service = currentServiceRef ?: return null
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            if (root.packageName?.toString() != expectedPackage) return null // stale root, don't trust it
            SensitiveContentDetector.isSensitive(root)
        } finally {
            root.recycleCompat()
        }
    }

    private fun clearPending() {
        pendingTextTarget = null; pendingTextValue = null; pendingTextPackage = null
        pendingScrollTarget = null; pendingScrollDirection = null; pendingScrollPackage = null
        pendingScreen = null; pendingScreenPackage = null
        lastActionKey = null; lastActionAtMs = 0L
        handler.removeCallbacks(commitTextRunnable)
        handler.removeCallbacks(commitScrollRunnable)
        handler.removeCallbacks(commitScreenRunnable)
    }

    private fun resolveKeyboardPackage(context: Context): String? =
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    private fun resolveLauncherPackage(context: Context): String? =
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
}