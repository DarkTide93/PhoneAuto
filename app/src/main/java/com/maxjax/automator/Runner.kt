package com.maxjax.automator

class Runner(
    private val svc: AutomatorService,
    private val script: Script,
    val scriptName: String,
    private val startDelayMs: Long
) : Thread("automator-runner") {

    @Volatile
    var stopRequested = false

    @Volatile
    var paused = false

    private class StopSignal : RuntimeException()
    private class BreakSignal : RuntimeException()
    private class ScriptError(msg: String) : RuntimeException(msg)

    override fun run() {
        svc.onRunState(true)
        try {
            if (startDelayMs > 0) sleepMs(startDelayMs)
            AppLog.i("▶ Running '$scriptName'")
            exec(script.body)
            AppLog.i("✔ '$scriptName' finished")
        } catch (e: StopSignal) {
            AppLog.i("■ '$scriptName' stopped")
        } catch (e: BreakSignal) {
            AppLog.i("'break' used outside a loop ended '$scriptName'")
        } catch (e: ScriptError) {
            AppLog.i("✖ ${e.message}")
            svc.toast(e.message ?: "Script error")
        } catch (e: Throwable) {
            AppLog.i("✖ Crash: ${e.javaClass.simpleName}: ${e.message}")
            svc.toast("Script crashed — see Logs")
        } finally {
            svc.onRunState(false)
        }
    }

    private fun checkpoint() {
        while (paused && !stopRequested) Thread.sleep(100)
        if (stopRequested) throw StopSignal()
    }

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0) {
            checkpoint()
            val step = minOf(left, 50L)
            Thread.sleep(step)
            left -= step
        }
        checkpoint()
    }

    private fun fail(s: Stmt, msg: String): Nothing = throw ScriptError("Line ${s.line}: $msg")

    private fun exec(list: List<Stmt>) {
        for (s in list) execOne(s)
    }

    private fun cond(sel: Selector, negate: Boolean): Boolean =
        svc.findNodes(sel, emptyList()).isNotEmpty() != negate

    private fun execOne(s: Stmt) {
        checkpoint()
        for (sel in script.stopIfs) {
            if (svc.findNodes(sel, emptyList()).isNotEmpty()) {
                AppLog.i("stopif matched ${sel.describe()}")
                throw StopSignal()
            }
        }

        when (s) {
            is Stmt.Wait -> sleepMs(s.ms)

            is Stmt.Tap -> tap(s)

            is Stmt.SwipeDir -> {
                if (!svc.swipeDir(s.dir, s.ms)) AppLog.i("Line ${s.line}: swipe ${s.dir} didn't go through")
                sleepMs(200)
            }

            is Stmt.SwipeXY -> {
                val (x1, y1) = svc.resolve(s.x1, s.y1)
                val (x2, y2) = svc.resolve(s.x2, s.y2)
                if (!svc.swipe(x1, y1, x2, y2, s.ms)) AppLog.i("Line ${s.line}: swipe didn't go through")
                sleepMs(200)
            }

            is Stmt.ScrollUntil -> {
                var tries = 0
                while (svc.findNodes(s.sel, script.avoids).isEmpty()) {
                    if (tries >= s.max) fail(s, "${s.sel.describe()} not found after ${s.max} scrolls")
                    svc.swipeDir(s.dir, 350)
                    sleepMs(700)
                    tries++
                }
            }

            is Stmt.WaitFor -> {
                val deadline = System.currentTimeMillis() + s.timeoutMs
                while (true) {
                    val present = svc.findNodes(s.sel, emptyList()).isNotEmpty()
                    if (present != s.gone) break
                    if (System.currentTimeMillis() >= deadline) {
                        val what = if (s.gone) "to disappear" else "to appear"
                        fail(s, "timed out waiting for ${s.sel.describe()} $what")
                    }
                    sleepMs(250)
                }
            }

            is Stmt.TypeText -> {
                if (!svc.insertText(s.text, true)) fail(s, "no focused text box to type into")
            }

            is Stmt.Clear -> {
                if (!svc.insertText("", false)) fail(s, "no focused text box to clear")
            }

            is Stmt.Reply -> {
                val body = Store.findReply(svc, s.name) ?: fail(s, "no quick reply named '${s.name}'")
                if (!svc.insertText(body, true)) fail(s, "no focused text box for the reply")
            }

            is Stmt.Global -> {
                svc.performGlobalAction(s.action)
                sleepMs(300)
            }

            is Stmt.Launch -> {
                if (!svc.launchApp(s.pkg)) fail(s, "can't launch '${s.pkg}' (not installed?)")
                sleepMs(500)
            }

            is Stmt.Print -> AppLog.i("log: ${s.msg}")

            is Stmt.Dump -> svc.dumpScreen()

            is Stmt.Stop -> throw StopSignal()

            is Stmt.Break -> throw BreakSignal()

            is Stmt.Repeat -> {
                for (i in 1..s.count) {
                    try {
                        exec(s.body)
                    } catch (b: BreakSignal) {
                        break
                    }
                }
            }

            is Stmt.If -> {
                if (cond(s.sel, s.negate)) exec(s.body) else exec(s.elseBody)
            }

            is Stmt.While -> {
                var n = 0
                while (n < s.max && cond(s.sel, s.negate)) {
                    try {
                        exec(s.body)
                    } catch (b: BreakSignal) {
                        break
                    }
                    n++
                }
                if (n >= s.max) AppLog.i("Line ${s.line}: while stopped at max ${s.max}")
            }
        }
    }

    private fun tap(s: Stmt.Tap) {
        when (val t = s.target) {
            is TapTarget.Point -> {
                val (x, y) = svc.resolve(t.x, t.y)
                if (!svc.tapAt(x, y, s.long)) {
                    fail(s, "the tap at ${x.toInt()},${y.toInt()} was refused (screen off, or something else took the touch)")
                }
                AppLog.i("tap ${x.toInt()},${y.toInt()}")
            }

            is TapTarget.Node -> {
                val deadline = System.currentTimeMillis() + 2000
                var node = svc.findNodes(t.sel, script.avoids).getOrNull(t.index)
                while (node == null && System.currentTimeMillis() < deadline) {
                    sleepMs(250)
                    node = svc.findNodes(t.sel, script.avoids).getOrNull(t.index)
                }
                val label = t.sel.describe() + (if (t.index > 0) " index ${t.index}" else "")
                if (node == null) fail(s, "nothing to tap for $label")
                if (!svc.tapNode(node, s.long, s.forceGesture)) {
                    fail(s, "found $label but the tap didn't go through — try adding 'gesture' at the end of the line")
                }
                AppLog.i("tap $label")
            }
        }
        sleepMs(150)
    }
}
