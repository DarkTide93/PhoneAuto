package com.maxjax.automator

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

class QuickRepliesActivity : Activity() {

    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        editor = EditText(this)
        editor.setText(Store.loadRepliesRaw(this))
        editor.typeface = Typeface.MONOSPACE
        editor.textSize = 14f
        editor.setTextColor(Palette.TEXT)
        editor.setBackgroundColor(0)
        editor.gravity = Gravity.TOP or Gravity.START
        editor.setPadding(dp(14), dp(14), dp(14), dp(14))
        editor.setLineSpacing(dpf(3f), 1f)
        editor.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        editor.setHorizontallyScrolling(false)
        editor.minLines = 12

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), 0, dp(20), dp(32))

        val info = uiCard()
        val infoPad = LinearLayout(this)
        infoPad.orientation = LinearLayout.VERTICAL
        infoPad.setPadding(dp(16), dp(14), dp(16), dp(14))
        infoPad.addView(uiBody("One reply per line:   Name | reply text"))
        infoPad.addView(
            uiCaption(
                "Use \\n for a line break inside a reply. Lines starting with # are ignored.\n" +
                    "Tap the speech-bubble icon on the bar while a text box is focused, or use " +
                    "reply \"Name\" in a script."
            ),
            matchWrap(dp(8))
        )
        info.addView(infoPad)
        body.addView(info)

        val card = uiCard()
        card.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        body.addView(card, matchWrap(dp(14)))
        body.addView(uiPrimaryButton("Save") { save(); toastShort("Saved") }, matchWrap(dp(12)))

        val screen = uiScreen()
        screen.addView(uiHeader("Quick replies", "Text you can drop into any app") { finish() })
        screen.addView(uiScroll(body), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(screen)
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        Store.saveRepliesRaw(this, editor.text.toString())
    }
}
