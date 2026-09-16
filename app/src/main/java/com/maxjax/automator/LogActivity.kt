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
        title = "Logs"

        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 12f
        logView.setTextIsSelectable(true)

        scroll = ScrollView(this)
        scroll.addView(logView)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(8), dp(12), dp(12))
        root.addView(uiRow(uiButton("Copy") { copy() }, uiButton("Clear") { AppLog.clear() }))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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
        logView.text = if (all.isEmpty()) "No log entries yet." else all
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Automator log", AppLog.all()))
        toastShort("Log copied")
    }
}
