package com.maxjax.automator

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.uiText(s: String, sizeSp: Float = 15f): TextView {
    val tv = TextView(this)
    tv.text = s
    tv.textSize = sizeSp
    tv.setPadding(0, dp(4), 0, dp(4))
    return tv
}

fun Context.uiHeader(s: String): TextView {
    val tv = uiText(s, 18f)
    tv.setTypeface(tv.typeface, Typeface.BOLD)
    tv.setPadding(0, dp(18), 0, dp(6))
    return tv
}

fun Context.uiButton(label: String, onClick: () -> Unit): Button {
    val b = Button(this)
    b.text = label
    b.isAllCaps = false
    b.setOnClickListener { onClick() }
    b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    return b
}

fun Context.uiRow(vararg views: View): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    for (v in views) {
        row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    return row
}

fun Context.toastShort(s: String) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
