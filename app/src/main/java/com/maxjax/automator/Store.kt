package com.maxjax.automator

import android.content.Context
import java.io.File

object Store {
    private const val PREFS = "automator"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun scriptsDir(ctx: Context): File = File(ctx.filesDir, "scripts").also { it.mkdirs() }
    private fun scriptFile(ctx: Context, name: String): File = File(scriptsDir(ctx), "$name.txt")
    private fun repliesFile(ctx: Context): File = File(ctx.filesDir, "replies.txt")
    private fun paramPrefsName(script: String): String = "params_" + script.replace(' ', '_')

    fun sanitize(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_")

    fun listScripts(ctx: Context): List<String> =
        scriptsDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.map { it.name.removeSuffix(".txt") }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()

    fun exists(ctx: Context, name: String): Boolean = scriptFile(ctx, name).exists()

    fun loadScript(ctx: Context, name: String): String {
        val f = scriptFile(ctx, name)
        return if (f.exists()) f.readText() else ""
    }

    fun saveScript(ctx: Context, name: String, text: String) {
        scriptFile(ctx, name).writeText(text)
    }

    fun deleteScript(ctx: Context, name: String) {
        scriptFile(ctx, name).delete()
        ctx.getSharedPreferences(paramPrefsName(name), Context.MODE_PRIVATE).edit().clear().apply()
        if (prefs(ctx).getString("active", null) == name) prefs(ctx).edit().remove("active").apply()
    }

    fun activeScript(ctx: Context): String? =
        prefs(ctx).getString("active", null)?.takeIf { exists(ctx, it) }

    fun setActive(ctx: Context, name: String) {
        prefs(ctx).edit().putString("active", name).apply()
    }

    fun paramValues(ctx: Context, script: String): Map<String, String> =
        ctx.getSharedPreferences(paramPrefsName(script), Context.MODE_PRIVATE).all
            .mapValues { it.value.toString() }

    fun saveParams(ctx: Context, script: String, values: Map<String, String>) {
        val e = ctx.getSharedPreferences(paramPrefsName(script), Context.MODE_PRIVATE).edit()
        e.clear()
        for ((k, v) in values) e.putString(k, v)
        e.apply()
    }

    fun loadRepliesRaw(ctx: Context): String {
        val f = repliesFile(ctx)
        return if (f.exists()) f.readText() else ""
    }

    fun saveRepliesRaw(ctx: Context, text: String) {
        repliesFile(ctx).writeText(text)
    }

    /** Each line: Name | reply text   (\n inside the text = new line, # = comment) */
    fun parseReplies(raw: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in raw.lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val bar = l.indexOf('|')
            if (bar <= 0) continue
            val name = l.substring(0, bar).trim()
            val body = l.substring(bar + 1).trim().replace("\\n", "\n")
            if (name.isNotEmpty()) out.add(Pair(name, body))
        }
        return out
    }

    fun findReply(ctx: Context, name: String): String? =
        parseReplies(loadRepliesRaw(ctx)).firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun ensureSeed(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("seeded", false)) return
        if (!exists(ctx, SMOKE_NAME)) saveScript(ctx, SMOKE_NAME, SMOKE_SCRIPT)
        if (!exists(ctx, DEMO_NAME)) saveScript(ctx, DEMO_NAME, DEMO_SCRIPT)
        if (!repliesFile(ctx).exists()) saveRepliesRaw(ctx, DEMO_REPLIES)
        if (p.getString("active", null) == null) setActive(ctx, SMOKE_NAME)
        p.edit().putBoolean("seeded", true).apply()
    }

    private const val SMOKE_NAME = "Smoke test"

    private val SMOKE_SCRIPT = """
# Start here. This taps nothing, so it is safe to run
# anywhere. It checks that the service can drive the
# phone and can read the screen.
#
# Run it, then open Logs in the app and tap Copy.

log "smoke test starting"
home
wait 1s
dump
notifications
wait 1s
dump
back
wait 500ms
log "smoke test finished - open Logs and tap Copy"
""".trimStart()

    private const val DEMO_NAME = "Demo About phone"

    private val DEMO_SCRIPT = """
# Demo: opens Settings, scrolls to a row and taps it.
# Tap Run, then keep your hands off the screen.
param target = About phone

launch com.android.settings
wait 2s
scroll until text "{target}" max 15
tap text "{target}"
wait 1500ms
back
log "Demo finished"
""".trimStart()

    private val DEMO_REPLIES = """
# One reply per line:   Name | reply text
# Use \n for a new line inside a reply.
Thanks | Thanks so much, really appreciate it!
On it | On it, I'll get back to you shortly.
Later | Can't talk right now, I'll message you later.
""".trimStart()
}
