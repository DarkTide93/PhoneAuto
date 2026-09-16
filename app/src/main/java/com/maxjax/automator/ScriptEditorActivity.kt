package com.maxjax.automator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

class ScriptEditorActivity : Activity() {

    private lateinit var scriptName: String
    private lateinit var editor: EditText
    private var deleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val n = intent.getStringExtra("name")
        if (n == null) {
            finish()
            return
        }
        scriptName = n

        editor = EditText(this)
        editor.setText(Store.loadScript(this, scriptName))
        editor.typeface = Typeface.MONOSPACE
        editor.textSize = 14f
        editor.setTextColor(Palette.TEXT)
        editor.setBackgroundColor(0)
        editor.gravity = Gravity.TOP or Gravity.START
        editor.setPadding(dp(14), dp(14), dp(14), dp(14))
        editor.setLineSpacing(dpf(3f), 1f)
        editor.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.setHorizontallyScrolling(false)
        editor.minLines = 14

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), 0, dp(20), dp(32))

        body.addView(
            uiButtonRow(
                uiPrimaryButton("Run") { askParamsThen(true) },
                uiSecondaryButton("Set active") { askParamsThen(false) }
            )
        )

        val card = uiCard()
        card.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        body.addView(card, matchWrap(dp(14)))

        body.addView(
            uiButtonRow(
                uiSecondaryButton("Save") { save(); toastShort("Saved") },
                uiSecondaryButton("Check") { check() },
                uiSecondaryButton("Help") { showHelp() }
            ),
            matchWrap(dp(12))
        )
        body.addView(uiDangerButton("Delete script") { confirmDelete() }, matchWrap(dp(6)))

        val screen = uiScreen()
        screen.addView(uiHeader(scriptName, "Tap Run to start in 3 seconds") { finish() })
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
        if (!deleted && ::editor.isInitialized) Store.saveScript(this, scriptName, editor.text.toString())
    }

    private fun parseOrShow(): Script? {
        return try {
            Parser.parse(editor.text.toString(), Store.paramValues(this, scriptName))
        } catch (e: ParseException) {
            AlertDialog.Builder(this)
                .setTitle("Script error")
                .setMessage(e.message)
                .setPositiveButton("OK", null)
                .show()
            null
        }
    }

    private fun check() {
        save()
        if (parseOrShow() != null) toastShort("Looks good")
    }

    private fun askParamsThen(run: Boolean) {
        save()
        val defaults = Parser.extractParams(editor.text.toString())
        if (defaults.isEmpty()) {
            Store.saveParams(this, scriptName, emptyMap())
            go(run)
            return
        }
        val saved = Store.paramValues(this, scriptName)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        val fields = LinkedHashMap<String, EditText>()
        for ((key, def) in defaults) {
            box.addView(uiCaption(key), matchWrap(dp(10)))
            val f = EditText(this)
            f.setText(saved[key] ?: def)
            f.setSingleLine(true)
            box.addView(f, matchWrap())
            fields[key] = f
        }
        AlertDialog.Builder(this)
            .setTitle("Parameters")
            .setView(uiScroll(box))
            .setPositiveButton(if (run) "Run" else "Set active") { _, _ ->
                Store.saveParams(this, scriptName, fields.mapValues { it.value.text.toString() })
                go(run)
            }
            .setNeutralButton("Defaults") { _, _ ->
                Store.saveParams(this, scriptName, defaults)
                go(run)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun go(run: Boolean) {
        if (parseOrShow() == null) return
        Store.setActive(this, scriptName)
        if (!run) {
            toastShort("'$scriptName' is active — press play on the bar")
            return
        }
        val svc = AutomatorService.instance
        if (svc == null) {
            AlertDialog.Builder(this)
                .setTitle("Service is off")
                .setMessage("Turn on Phone Automator in Accessibility settings first.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (svc.runScript(scriptName, 3000)) {
            toastShort("Starting in 3 seconds")
            moveTaskToBack(true)
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete '$scriptName'?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleted = true
                Store.deleteScript(this, scriptName)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
