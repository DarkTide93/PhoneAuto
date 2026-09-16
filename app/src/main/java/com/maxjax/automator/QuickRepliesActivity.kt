package com.maxjax.automator

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

class QuickRepliesActivity : Activity() {

    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Quick replies"

        editor = EditText(this)
        editor.setText(Store.loadRepliesRaw(this))
        editor.typeface = Typeface.MONOSPACE
        editor.textSize = 14f
        editor.gravity = Gravity.TOP or Gravity.START
        editor.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        editor.setHorizontallyScrolling(false)
        editor.minLines = 12

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(8), dp(12), dp(40))
        root.addView(
            uiText(
                "One reply per line:  Name | reply text\n" +
                    "Use \\n for a new line inside a reply. Lines starting with # are ignored.\n" +
                    "On the bubble, tap 💬, then a reply, while a text box is focused. " +
                    "Scripts can use: reply \"Name\"",
                13f
            )
        )
        root.addView(uiButton("Save") {
            save()
            toastShort("Saved")
        })
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        Store.saveRepliesRaw(this, editor.text.toString())
    }
}
