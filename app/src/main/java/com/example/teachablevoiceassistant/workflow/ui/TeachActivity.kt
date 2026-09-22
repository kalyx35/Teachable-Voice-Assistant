package com.example.teachablevoiceassistant.workflow.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.teachablevoiceassistant.accessibility.WorkflowAccessibilityService
import com.example.teachablevoiceassistant.workflow.WorkflowRecorder

/**
 * Debug/teaching screen for Day 3.
 *
 * Lets the user start Teach mode, perform actions in another app, then stop.
 * Shows a live step count while recording; the full step-by-step breakdown is
 * shown afterwards in [WorkflowInspectorActivity].
 */
class TeachActivity : Activity(), WorkflowRecorder.Listener {

    private lateinit var statusText: TextView
    private lateinit var stepCountText: TextView
    private lateinit var warningText: TextView
    private lateinit var commandInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var viewLastButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiUtils.verticalLayout(this)

        root.addView(UiUtils.heading(this, "Teach a workflow"))

        warningText = UiUtils.body(this, "").apply { setTextColor(Color.RED) }
        root.addView(warningText)

        root.addView(UiUtils.body(this, "Name this workflow (optional):"))
        commandInput = EditText(this).apply { hint = "e.g. \"Turn on Wi-Fi\"" }
        root.addView(commandInput)

        statusText = UiUtils.heading(this, "Teaching mode: OFF")
        root.addView(statusText)

        stepCountText = UiUtils.body(this, "Steps recorded: 0")
        root.addView(stepCountText)

        startButton = UiUtils.button(this, "Start teaching") { startTeaching() }
        stopButton = UiUtils.button(this, "Stop teaching") { stopTeaching() }
        root.addView(startButton)
        root.addView(stopButton)

        root.addView(UiUtils.divider(this))
        viewLastButton = UiUtils.button(this, "View last workflow") { openInspector() }
        root.addView(viewLastButton)

        setContentView(ScrollView(this).apply { addView(root) })

        WorkflowRecorder.setListener(this)
        refreshUiState()
    }

    override fun onResume() {
        super.onResume()
        refreshUiState()
    }

    override fun onDestroy() {
        WorkflowRecorder.setListener(null)
        super.onDestroy()
    }

    private fun startTeaching() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Turn on the accessibility service first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val label = commandInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "Untitled workflow"
        WorkflowRecorder.start(applicationContext, label)
        Toast.makeText(
            this,
            "Teaching mode ON. Switch to the app you want to teach, then come back here to stop.",
            Toast.LENGTH_LONG
        ).show()
        refreshUiState()
    }

    private fun stopTeaching() {
        val workflow = WorkflowRecorder.stop()
        refreshUiState()
        if (workflow != null) {
            Toast.makeText(this, "Recorded ${workflow.steps.size} step(s)", Toast.LENGTH_SHORT).show()
            openInspector()
        }
    }

    private fun openInspector() {
        startActivity(Intent(this, WorkflowInspectorActivity::class.java))
    }

    override fun onStateChanged(recording: Boolean) {
        runOnUiThread { refreshUiState() }
    }

    override fun onStepCountChanged(count: Int) {
        runOnUiThread { stepCountText.text = "Steps recorded: $count" }
    }

    private fun refreshUiState() {
        val recording = WorkflowRecorder.isRecording
        statusText.text = if (recording) "Teaching mode: ON" else "Teaching mode: OFF"
        startButton.isEnabled = !recording
        stopButton.isEnabled = recording
        commandInput.isEnabled = !recording
        warningText.text = if (isAccessibilityServiceEnabled()) {
            ""
        } else {
            "Accessibility service is OFF. \"Start teaching\" will open Settings so you can enable it."
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${WorkflowAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}