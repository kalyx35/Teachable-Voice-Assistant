package com.example.teachablevoiceassistant.workflow.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.teachablevoiceassistant.workflow.ActionType
import com.example.teachablevoiceassistant.workflow.Workflow
import com.example.teachablevoiceassistant.workflow.WorkflowRepository
import com.example.teachablevoiceassistant.workflow.WorkflowStep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug/inspection screen (requirement 13-14): shows every step of a recorded
 * workflow - index, package, action type, target text/description, class, and
 * resource id.
 *
 * Shows the workflow named by EXTRA_WORKFLOW_ID, or the most recently recorded
 * one if no id is passed.
 */
class WorkflowInspectorActivity : Activity() {

    companion object {
        const val EXTRA_WORKFLOW_ID = "workflow_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID)
        val workflow = workflowId?.let { WorkflowRepository.findById(it) } ?: WorkflowRepository.latest()

        val root = UiUtils.verticalLayout(this)

        if (workflow == null) {
            root.addView(UiUtils.heading(this, "No workflow recorded yet"))
            root.addView(UiUtils.body(this, "Go back and tap \"Start teaching\" to record one."))
        } else {
            renderWorkflow(root, workflow)
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun renderWorkflow(root: LinearLayout, workflow: Workflow) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        root.addView(UiUtils.heading(this, workflow.originalCommand))
        root.addView(
            UiUtils.body(
                this,
                "Recorded ${timeFormat.format(Date(workflow.createdAt))}  -  ${workflow.steps.size} step(s)"
            )
        )
        root.addView(UiUtils.divider(this))

        if (workflow.steps.isEmpty()) {
            root.addView(UiUtils.body(this, "No steps were recorded in this session."))
            return
        }

        workflow.steps.forEach { step -> root.addView(stepView(step)) }
    }

    private fun stepView(step: WorkflowStep): TextView {
        val lines = mutableListOf("${step.index}. ${step.actionType}  -  ${step.packageName}")

        if (step.actionType == ActionType.SENSITIVE_BOUNDARY) {
            lines.add("   ${step.note ?: "Sensitive content skipped"}")
        } else {
            step.target?.let { target ->
                lines.add("   text/desc: ${target.label ?: "(none)"}")
                lines.add("   class: ${target.className ?: "unknown"}")
                lines.add("   id: ${target.viewIdResourceName ?: "(none)"}")
            }
            step.inputValue?.let { lines.add("   value: \"$it\"") }
        }

        return UiUtils.body(this, lines.joinToString("\n")).apply {
            setPadding(0, UiUtils.dp(context, 8), 0, UiUtils.dp(context, 8))
            if (step.actionType == ActionType.SENSITIVE_BOUNDARY) setTextColor(Color.parseColor("#B8860B"))
        }
    }
}