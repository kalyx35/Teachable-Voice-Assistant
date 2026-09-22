package com.example.teachablevoiceassistant.workflow.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers for building simple debug screens without XML layouts. */
object UiUtils {

    fun verticalLayout(context: Context, paddingDp: Int = 16): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, paddingDp)
            setPadding(p, p, p, p)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    fun heading(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 20f
            setPadding(0, dp(context, 12), 0, dp(context, 8))
        }

    fun body(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

    fun button(context: Context, text: String, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    fun divider(context: Context): View =
        View(context).apply {
            setBackgroundColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
            ).apply {
                topMargin = dp(context, 8)
                bottomMargin = dp(context, 8)
            }
        }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}