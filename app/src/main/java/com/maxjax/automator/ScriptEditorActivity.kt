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
import android.widget.ScrollView

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
        title = scriptName

        editor = EditText(this)
        editor.setText(Store.loadScript(this, scriptName))
        editor.typeface = Typeface.MONOSPACE
        editor.textSize = 14f
        editor.gravity = Gravity.TOP or Gravity.START
        editor.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.setHorizontallyScrolling(false)
        editor.minLines = 16

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(8), dp(12), dp(40))
        root.addView(
            uiRow(
                uiButton("Save") {
                    save()
                    toastShort("Saved")
                },
                uiButton("Check") { check() },
                uiButton("Help") { showHelp() }
            )
        )
        root.addView(
            uiRow(
                uiButton("Run") { askParamsThen(true) },
                uiButton("Set active") { askParamsThen(false) },
                uiButton("Delete") { confirmDelete() }
            )
        )
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
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val fields = LinkedHashMap<String, EditText>()
        for ((key, def) in defaults) {
            box.addView(uiText(key, 13f))
            val f = EditText(this)
            f.setText(saved[key] ?: def)
            f.setSingleLine(true)
            box.addView(f)
            fields[key] = f
        }
        val scroll = ScrollView(this)
        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle("Parameters")
            .setView(scroll)
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
            toastShort("'$scriptName' is active. Tap ▶ on the bubble to run it.")
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
            .setPositiveButton("Delete") { _, _ ->
                deleted = true
                Store.deleteScript(this, scriptName)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
