package com.maxjax.automator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

class MainActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.ensureSeed(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(8), dp(16), dp(40))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /**
     * The switch in Accessibility settings is the source of truth. `instance` only becomes
     * non-null once Android has actually bound the service, which can lag a second behind.
     */
    private fun enabledInSettings(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "$packageName/${AutomatorService::class.java.name}"
        val meShort = "$packageName/.${AutomatorService::class.java.simpleName}"
        return flat.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
    }

    private fun render() {
        root.removeAllViews()

        val connected = AutomatorService.instance != null
        val enabled = connected || enabledInSettings()
        root.addView(uiHeader(if (enabled) "Service: ON" else "Service: OFF"))
        if (enabled) {
            root.addView(
                uiText(
                    if (connected) {
                        "The control bubble is on screen. Drag it by the ⠿ handle.\n\n" +
                            "▶ run   ⏸ pause   ■ stop   💬 quick replies   🔍 write what's on screen to Logs"
                    } else {
                        "Turned on in settings, waiting for Android to start it. " +
                            "If the bubble doesn't appear in a few seconds, toggle it off and on again."
                    }
                )
            )
        } else {
            root.addView(
                uiText(
                    "Turn on Phone Automator in Accessibility settings (usually under Downloaded apps or Installed apps).\n\n" +
                        "If the switch is greyed out: open App info, tap the ⋮ menu at the top right, choose " +
                        "Allow restricted settings, then go back and turn it on."
                )
            )
            root.addView(uiButton("Open Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            root.addView(uiButton("Open App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        root.addView(uiHeader("Scripts"))
        val active = Store.activeScript(this)
        root.addView(
            uiText(
                if (active != null) "★ Active (runs from ▶ on the bubble): $active"
                else "No active script. Open one and tap Set active.",
                13f
            )
        )
        for (name in Store.listScripts(this)) {
            root.addView(uiButton(if (name == active) "★  $name" else name) { openEditor(name) })
        }
        root.addView(uiButton("+ New script") { newScript() })

        root.addView(uiHeader("Tools"))
        root.addView(uiButton("Quick replies") {
            startActivity(Intent(this, QuickRepliesActivity::class.java))
        })
        root.addView(uiButton("Logs") {
            startActivity(Intent(this, LogActivity::class.java))
        })
        root.addView(uiButton("Command reference") { showHelp() })
    }

    private fun openEditor(name: String) {
        val i = Intent(this, ScriptEditorActivity::class.java)
        i.putExtra("name", name)
        startActivity(i)
    }

    private fun newScript() {
        val input = EditText(this)
        input.hint = "Script name"
        input.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("New script")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = Store.sanitize(input.text.toString())
                if (name.isEmpty()) {
                    toastShort("Enter a name")
                } else if (Store.exists(this, name)) {
                    toastShort("A script with that name already exists")
                } else {
                    Store.saveScript(this, name, "# $name\n\n")
                    openEditor(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
