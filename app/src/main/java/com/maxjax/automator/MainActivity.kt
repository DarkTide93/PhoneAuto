package com.maxjax.automator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

class MainActivity : Activity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.ensureSeed(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), 0, dp(20), dp(40))

        val screen = uiScreen()
        screen.addView(uiHeader("Phone Automator", "Scripted taps, swipes and replies"))
        screen.addView(uiScroll(content), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(screen)
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
        content.removeAllViews()
        renderStatus()
        renderScripts()
        renderTools()
    }

    private fun renderStatus() {
        val connected = AutomatorService.instance != null
        val enabled = connected || enabledInSettings()

        val card = uiCard()
        val pad = LinearLayout(this)
        pad.orientation = LinearLayout.VERTICAL
        pad.setPadding(dp(16), dp(16), dp(16), dp(16))

        pad.addView(
            uiStatusChip(
                if (connected) "Active" else if (enabled) "Starting" else "Inactive",
                if (connected) Palette.OK else if (enabled) Palette.WARN else Palette.TEXT_FAINT
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val message = when {
            connected -> "The control bar is on screen. Drag it by the grip on the left; " +
                "the ✕ on the right turns everything off."
            enabled -> "Turned on, waiting for Android to start the service. If the bar doesn't " +
                "appear in a few seconds, switch it off and on again."
            else -> "Phone Automator needs the accessibility permission to read the screen and " +
                "perform taps. Nothing leaves your phone."
        }
        pad.addView(uiBody(message), matchWrap(dp(12)))

        if (!enabled) {
            pad.addView(
                uiPrimaryButton("Open Accessibility settings") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                matchWrap(dp(16))
            )
            pad.addView(
                uiQuietButton("Switch greyed out? Open App info") {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                },
                matchWrap(dp(10))
            )
            pad.addView(
                uiCaption("In App info, tap ⋮ in the top right and choose Allow restricted settings."),
                matchWrap(dp(10))
            )
        }

        card.addView(pad)
        content.addView(card, matchWrap(dp(4)))
    }

    private fun renderScripts() {
        val scripts = Store.listScripts(this)
        val active = Store.activeScript(this)

        content.addView(uiSectionTitle("Scripts"), matchWrap(dp(26)))
        content.addView(
            uiCaption(
                if (active != null) "★ $active runs when you press play on the bar."
                else "No script is set to run from the bar yet."
            ),
            matchWrap(dp(6))
        )

        val card = uiCard()
        if (scripts.isEmpty()) {
            val empty = uiBody("No scripts yet.")
            empty.setPadding(dp(16), dp(18), dp(16), dp(18))
            card.addView(empty)
        }
        scripts.forEachIndexed { i, name ->
            if (i > 0) card.addDivider(this, dp(16))
            card.addView(
                uiRow(
                    title = name,
                    subtitle = null,
                    badge = if (name == active) "Active" else null,
                    badgeColor = Palette.ACCENT
                ) { openEditor(name) }
            )
        }
        content.addView(card, matchWrap(dp(10)))
        content.addView(uiSecondaryButton("New script") { newScript() }, matchWrap(dp(10)))
    }

    private fun renderTools() {
        content.addView(uiSectionTitle("Tools"), matchWrap(dp(26)))
        val card = uiCard()
        card.addView(uiRow("Quick replies", "Saved text you can drop into any box") {
            startActivity(Intent(this, QuickRepliesActivity::class.java))
        })
        card.addDivider(this, dp(16))
        card.addView(uiRow("Logs", "Every run, line by line") {
            startActivity(Intent(this, LogActivity::class.java))
        })
        card.addDivider(this, dp(16))
        card.addView(uiRow("Command reference", "The full script language") { showHelp() })
        content.addView(card, matchWrap(dp(10)))
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
        val box = LinearLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        AlertDialog.Builder(this)
            .setTitle("New script")
            .setView(box)
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
