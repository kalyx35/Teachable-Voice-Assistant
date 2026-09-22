package com.example.teachablevoiceassistant.workflow

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.teachablevoiceassistant.accessibility.recycleCompat

/**
 * Best-effort detector for screens that likely involve passwords, OTP/PIN codes,
 * payment card details, or a login boundary.
 *
 * This is a heuristic, not a guarantee. When it cannot decide, callers should
 * treat that as sensitive: pausing on a couple of ordinary screens is a far
 * smaller problem than recording something that shouldn't be recorded.
 */
object SensitiveContentDetector {

    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 30

    private val SENSITIVE_FIELD_KEYWORDS = listOf(
        "otp", "one-time password", "one time password", "pin", "cvv", "cvc",
        "card number", "security code", "passcode", "verification code"
    )
    private val LOGIN_FIELD_KEYWORDS = listOf("username", "user id", "user name", "email", "phone number")
    private val LOGIN_ACTION_KEYWORDS = listOf("log in", "login", "sign in")

    /**
     * @return true if the screen looks sensitive, false if it looks safe,
     * null if it could not be determined. Treat null as sensitive.
     */
    fun isSensitive(root: AccessibilityNodeInfo): Boolean? {
        return try {
            val state = ScanState()
            scan(root, depth = 0, state = state)
            state.hasPasswordField || state.hasSensitiveKeyword || (state.hasLoginField && state.hasLoginAction)
        } catch (e: Exception) {
            null
        }
    }

    private class ScanState {
        var nodeCount = 0
        var hasPasswordField = false
        var hasSensitiveKeyword = false
        var hasLoginField = false
        var hasLoginAction = false
    }

    private fun scan(node: AccessibilityNodeInfo, depth: Int, state: ScanState) {
        if (state.nodeCount >= MAX_NODES || depth > MAX_DEPTH) return
        state.nodeCount++

        if (node.isPassword) state.hasPasswordField = true

        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: hintOf(node))
            ?.lowercase()

        if (label != null) {
            if (SENSITIVE_FIELD_KEYWORDS.any { label.contains(it) }) {
                state.hasSensitiveKeyword = true
            }
            if (node.isEditable && LOGIN_FIELD_KEYWORDS.any { label.contains(it) }) {
                state.hasLoginField = true
            }
            if (node.isClickable && LOGIN_ACTION_KEYWORDS.any { label.contains(it) }) {
                state.hasLoginAction = true
            }
        }

        for (i in 0 until node.childCount) {
            if (state.nodeCount >= MAX_NODES) return
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            try {
                scan(child, depth + 1, state)
            } finally {
                child.recycleCompat()
            }
        }
    }

    private fun hintOf(node: AccessibilityNodeInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            node.hintText?.toString()
        } catch (e: Exception) {
            null
        }
    }
}