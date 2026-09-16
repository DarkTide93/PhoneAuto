package com.maxjax.automator

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LogActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 11.5f
        logView.setTextColor(Palette.TEXT_DIM)
        logView.setTextIsSelectable(true)
        logView.setPadding(dp(14), dp(14), dp(14), dp(14))
        logView.setLineSpacing(dpf(2f), 1f)

        scroll = ScrollView(this)
        scroll.addView(logView)

        val card = uiCard()
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), 0, dp(20), dp(24))
        body.addView(
            uiButtonRow(
                uiPrimaryButton("Copy") { copy() },
                uiSecondaryButton("Clear") { AppLog.clear() }
            )
        )
        body.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).also { it.topMargin = dp(14) })

        val screen = uiScreen()
        screen.addView(uiHeader("Logs", "Copy this and paste it when something misbehaves") { finish() })
        screen.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        AppLog.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        AppLog.listener = null
        super.onPause()
    }

    private fun refresh() {
        val all = AppLog.all()
        logView.text = if (all.isEmpty()) "Nothing logged yet. Run a script, or tap the magnifier on the bar." else all
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Phone Automator log", AppLog.all()))
        toastShort("Log copied")
    }
}
