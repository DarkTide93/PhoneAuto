package com.maxjax.automator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AutomatorService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutomatorService? = null

        /** Nodes listed by one `dump`, so a huge screen can't flood the log. */
        private const val DUMP_LIMIT = 200
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    @Volatile private var running = false
    @Volatile private var runner: Runner? = null

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var pauseBtn: TextView? = null
    private var stopBtn: TextView? = null
    private var replyPanel: View? = null

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Store.ensureSeed(this)
        showBubble()
        AppLog.i("Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        stopScript()
        removeOverlays()
        instance = null
    }

    // ---------------------------------------------------------------- run control

    fun runScript(name: String, startDelayMs: Long): Boolean {
        if (running) {
            toast("A script is already running")
            return false
        }
        val script = try {
            Parser.parse(Store.loadScript(this, name), Store.paramValues(this, name))
        } catch (e: ParseException) {
            AppLog.i("✖ '$name': ${e.message}")
            toast(e.message ?: "Script error")
            return false
        }
        running = true
        val r = Runner(this, script, name, startDelayMs)
        runner = r
        main.post { updateBubble() }
        r.start()
        return true
    }

    fun stopScript() {
        val r = runner
        if (r == null) {
            // Nothing is actually running; clear a stale flag so ▶ works again.
            if (running) onRunState(false)
            return
        }
        r.stopRequested = true
        r.paused = false
    }

    fun togglePause() {
        val r = runner
        if (!running || r == null) return
        r.paused = !r.paused
        AppLog.i(if (r.paused) "⏸ Paused" else "▶ Resumed")
        main.post { updateBubble() }
    }

    fun onRunState(isRunning: Boolean) {
        running = isRunning
        if (!isRunning) runner = null
        main.post { updateBubble() }
    }

    fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    /** Runs [block] on the main thread and waits for it, or inline when already there. */
    private fun onMainBlocking(timeoutMs: Long, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // ---------------------------------------------------------------- screen + gestures

    fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    fun resolve(x: Coord, y: Coord): Pair<Float, Float> {
        val (w, h) = screenSize()
        val px = if (x.percent) w * x.value / 100f else x.value
        val py = if (y.percent) h * y.value / 100f else y.value
        return Pair(px, py)
    }

    fun tapAt(x: Float, y: Float, long: Boolean): Boolean {
        val (w, h) = screenSize()
        val p = Path()
        p.moveTo(x.coerceIn(0f, (w - 1).toFloat()), y.coerceIn(0f, (h - 1).toFloat()))
        return gesture(p, if (long) 800L else 60L)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val (w, h) = screenSize()
        val maxX = (w - 1).toFloat()
        val maxY = (h - 1).toFloat()
        val p = Path()
        p.moveTo(x1.coerceIn(0f, maxX), y1.coerceIn(0f, maxY))
        p.lineTo(x2.coerceIn(0f, maxX), y2.coerceIn(0f, maxY))
        return gesture(p, ms)
    }

    /** Direction = the way the finger moves. "up" scrolls content down. */
    fun swipeDir(dir: String, ms: Long): Boolean {
        val (w, h) = screenSize()
        val cx = w / 2f
        val cy = h / 2f
        return when (dir) {
            "up" -> swipe(cx, h * 0.70f, cx, h * 0.30f, ms)
            "down" -> swipe(cx, h * 0.30f, cx, h * 0.70f, ms)
            "left" -> swipe(w * 0.80f, cy, w * 0.20f, cy, ms)
            else -> swipe(w * 0.20f, cy, w * 0.80f, cy, ms)
        }
    }

    /**
     * Blocks the calling (runner) thread until the gesture finishes. The completion callback is
     * delivered on the main thread, so calling this from the main thread would deadlock.
     */
    private fun gesture(path: Path, durationMs: Long): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.i("✖ internal: gesture requested on the main thread, ignored")
            return false
        }
        val d = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, d))
            .build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post {
            val sent = try {
                dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        ok.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }
                }, null)
            } catch (e: Exception) {
                AppLog.i("✖ gesture rejected: ${e.message}")
                false
            }
            if (!sent) latch.countDown()
        }
        latch.await(d + 3000L, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    // ---------------------------------------------------------------- node search

    private fun appRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            val ordered = windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
                .sortedByDescending {
                    (if (it.type == AccessibilityWindowInfo.TYPE_APPLICATION) 4 else 0) +
                        (if (it.isFocused) 2 else 0) +
                        (if (it.isActive) 1 else 0)
                }
            for (w in ordered) {
                val r = w.root ?: continue
                if (r.packageName?.toString() != packageName) roots.add(r)
            }
        } catch (e: Exception) {
            // fall through to rootInActiveWindow
        }
        if (roots.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null && r.packageName?.toString() != packageName) roots.add(r)
        }
        return roots
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, limit: Int, depth: Int = 0) {
        if (out.size >= limit || depth > 60) return
        out.add(n)
        for (i in 0 until n.childCount) {
            if (out.size >= limit) return
            val c = n.getChild(i) ?: continue
            collect(c, out, limit, depth + 1)
        }
    }

    private fun matches(n: AccessibilityNodeInfo, sel: Selector): Boolean {
        val text = n.text?.toString() ?: ""
        val desc = n.contentDescription?.toString() ?: ""
        return when (sel.kind) {
            SelKind.TEXT -> text.contains(sel.value, ignoreCase = true) || desc.contains(sel.value, ignoreCase = true)
            SelKind.EXACT -> text.trim().equals(sel.value, ignoreCase = true) || desc.trim().equals(sel.value, ignoreCase = true)
            SelKind.DESC -> desc.contains(sel.value, ignoreCase = true)
            SelKind.ID -> {
                val id = n.viewIdResourceName ?: ""
                id == sel.value || id.endsWith(":id/" + sel.value)
            }
        }
    }

    private fun clickableTarget(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable || cur.isLongClickable) return cur
            cur = cur.parent
            depth++
        }
        return n
    }

    private fun isAvoided(n: AccessibilityNodeInfo, avoids: List<Selector>): Boolean {
        if (avoids.isEmpty()) return false
        val target = clickableTarget(n)
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(target, nodes, 60)
        val pool: List<AccessibilityNodeInfo> = if (nodes.size >= 60) listOf(n) else nodes
        return pool.any { c -> avoids.any { matches(c, it) } }
    }

    private fun bounds(n: AccessibilityNodeInfo): Rect {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r
    }

    /**
     * Matching nodes in reading order (top to bottom, then left to right) so that `index N`
     * means the same thing every run.
     */
    fun findNodes(sel: Selector, avoids: List<Selector>): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (root in appRoots()) {
            val all = ArrayList<AccessibilityNodeInfo>()
            collect(root, all, 5000)
            for (n in all) {
                if (n.isVisibleToUser && matches(n, sel) && !isAvoided(n, avoids)) out.add(n)
            }
        }
        return out.sortedWith(compareBy({ bounds(it).top }, { bounds(it).left }))
    }

    fun tapNode(n: AccessibilityNodeInfo, long: Boolean, forceGesture: Boolean): Boolean {
        val target = clickableTarget(n)
        if (!forceGesture) {
            val can = if (long) target.isLongClickable else target.isClickable
            val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            if (can && target.performAction(action)) return true
        }
        val r = bounds(n)
        if (r.isEmpty) return false
        return tapAt(r.exactCenterX(), r.exactCenterY(), long)
    }

    // ---------------------------------------------------------------- screen dump

    private fun quote(s: String): String = s.replace("\n", "\\n").take(60)

    /** Writes everything on screen to the log so selectors can be picked without guessing. */
    fun dumpScreen() {
        val roots = appRoots()
        if (roots.isEmpty()) {
            AppLog.i("dump: nothing readable on screen")
            return
        }
        val rows = ArrayList<String>()
        for (root in roots) {
            val all = ArrayList<AccessibilityNodeInfo>()
            collect(root, all, 5000)
            for (n in all) {
                if (!n.isVisibleToUser) continue
                val text = n.text?.toString() ?: ""
                val desc = n.contentDescription?.toString() ?: ""
                val id = n.viewIdResourceName ?: ""
                val interesting = text.isNotBlank() || desc.isNotBlank() ||
                    (id.isNotBlank() && (n.isClickable || n.isLongClickable || n.isEditable))
                if (!interesting) continue
                val r = bounds(n)
                val sb = StringBuilder()
                sb.append("[${r.left},${r.top}-${r.right},${r.bottom}]")
                if (n.isClickable) sb.append(" click")
                if (n.isLongClickable) sb.append(" long")
                if (n.isEditable) sb.append(" edit")
                if (n.isScrollable) sb.append(" scroll")
                if (text.isNotBlank()) sb.append("  text=\"${quote(text)}\"")
                if (desc.isNotBlank()) sb.append("  desc=\"${quote(desc)}\"")
                if (id.isNotBlank()) sb.append("  id=\"${id.substringAfter(":id/")}\"")
                rows.add(sb.toString())
                if (rows.size >= DUMP_LIMIT) break
            }
            if (rows.size >= DUMP_LIMIT) break
        }
        val pkg = roots.firstOrNull()?.packageName?.toString() ?: "?"
        val (w, h) = screenSize()
        AppLog.i("── dump: $pkg  screen ${w}x$h  (${rows.size} items)")
        rows.forEachIndexed { i, row -> AppLog.i("  $i  $row") }
        if (rows.size >= DUMP_LIMIT) AppLog.i("  … stopped at $DUMP_LIMIT items")
        AppLog.i("── end dump")
    }

    // ---------------------------------------------------------------- text input

    private fun focusedInput(): AccessibilityNodeInfo? {
        for (root in appRoots()) {
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (f.isEditable) return f
        }
        return null
    }

    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int) {
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    /** append=true adds to what's in the box, append=false replaces it. */
    fun insertText(text: String, append: Boolean): Boolean {
        val node = focusedInput() ?: return false
        val current = node.text?.toString() ?: ""
        val hint = node.hintText?.toString()
        val existing = if (!append || node.isShowingHintText || current == hint) "" else current
        val newText = existing + text
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            setSelection(node, newText.length, newText.length)
            return true
        }
        // Some apps (several chat apps especially) refuse SET_TEXT. Paste instead.
        return pasteInto(node, text, append)
    }

    private fun pasteInto(node: AccessibilityNodeInfo, text: String, append: Boolean): Boolean {
        if (text.isEmpty()) return false
        var copied = false
        onMainBlocking(2000) {
            copied = try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Phone Automator", text))
                true
            } catch (e: Exception) {
                AppLog.i("✖ clipboard unavailable: ${e.message}")
                false
            }
        }
        if (!copied) return false
        val len = node.text?.toString()?.length ?: 0
        if (append) setSelection(node, len, len) else setSelection(node, 0, len)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (ok) AppLog.i("typed via paste (this app blocks direct text input)")
        return ok
    }

    fun launchApp(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- overlay

    private fun overlayParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        return p
    }

    private fun bubbleButton(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 20f
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(9), dp(6), dp(9), dp(6))
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun showBubble() {
        if (bubble != null) return
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xE6202124L.toInt())
        bg.cornerRadius = dp(22).toFloat()
        bar.background = bg
        bar.setPadding(dp(4), dp(2), dp(8), dp(2))

        val handle = bubbleButton("⠿") {}
        val play = bubbleButton("▶") { runActiveFromBubble() }
        val pause = bubbleButton("⏸") { togglePause() }
        val stop = bubbleButton("■") { stopScript() }
        val chat = bubbleButton("💬") { toggleReplyPanel() }
        val look = bubbleButton("🔍") {
            closeReplyPanel()
            dumpScreen()
            toast("Screen written to Logs")
        }
        val status = TextView(this)
        status.textSize = 11f
        status.setTextColor(0xFFB0B0B0L.toInt())
        status.maxWidth = dp(110)
        status.maxLines = 1
        status.ellipsize = TextUtils.TruncateAt.END
        status.setPadding(dp(4), 0, 0, 0)

        bar.addView(handle)
        bar.addView(play)
        bar.addView(pause)
        bar.addView(stop)
        bar.addView(chat)
        bar.addView(look)
        bar.addView(status)

        val p = overlayParams()
        p.x = dp(8)
        p.y = dp(200)

        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = p.x
                    dragStartY = p.y
                    touchStartX = e.rawX
                    touchStartY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val (sw, sh) = screenSize()
                    // Keep a grab-able strip on screen; FLAG_LAYOUT_NO_LIMITS would
                    // otherwise let the bubble be dragged away for good.
                    val w = if (bar.width > 0) bar.width else dp(240)
                    val h = if (bar.height > 0) bar.height else dp(44)
                    val keep = dp(56)
                    val minX = minOf(keep - w, 0)
                    val maxX = maxOf(sw - keep, minX)
                    val maxY = maxOf(sh - h, 0)
                    p.x = (dragStartX + (e.rawX - touchStartX).toInt()).coerceIn(minX, maxX)
                    p.y = (dragStartY + (e.rawY - touchStartY).toInt()).coerceIn(0, maxY)
                    try {
                        wm.updateViewLayout(bar, p)
                    } catch (ex: Exception) {
                        // view already removed
                    }
                }
            }
            true
        }

        try {
            wm.addView(bar, p)
        } catch (e: Exception) {
            AppLog.i("Couldn't show bubble: ${e.message}")
            return
        }
        bubble = bar
        bubbleParams = p
        statusView = status
        pauseBtn = pause
        stopBtn = stop
        updateBubble()
    }

    private fun updateBubble() {
        val r = runner
        statusView?.text = when {
            running && r != null && r.paused -> "paused"
            running -> "running"
            else -> Store.activeScript(this) ?: "no active script"
        }
        val a = if (running) 1f else 0.35f
        pauseBtn?.alpha = a
        stopBtn?.alpha = a
    }

    private fun runActiveFromBubble() {
        val name = Store.activeScript(this)
        if (name == null) {
            toast("Open a script in the app and tap Set active")
            return
        }
        closeReplyPanel()
        runScript(name, 400)
    }

    private fun closeReplyPanel() {
        val panel = replyPanel ?: return
        try {
            wm.removeView(panel)
        } catch (e: Exception) {
            // already gone
        }
        replyPanel = null
    }

    private fun toggleReplyPanel() {
        if (replyPanel != null) {
            closeReplyPanel()
            return
        }
        val replies = Store.parseReplies(Store.loadRepliesRaw(this))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(8), dp(6), dp(8), dp(8))

        val header = TextView(this)
        header.text = "Quick replies            ✕"
        header.textSize = 14f
        header.setTextColor(Color.WHITE)
        header.setPadding(dp(8), dp(6), dp(8), dp(10))
        header.setOnClickListener { closeReplyPanel() }
        list.addView(header)

        if (replies.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No replies yet. Add them in the app."
            empty.setTextColor(0xFFB0B0B0L.toInt())
            empty.setPadding(dp(8), dp(4), dp(8), dp(8))
            list.addView(empty)
        }

        for ((title, body) in replies) {
            val item = TextView(this)
            item.text = title + "\n" + body.replace('\n', ' ').take(40)
            item.textSize = 14f
            item.setTextColor(Color.WHITE)
            item.setPadding(dp(8), dp(8), dp(8), dp(8))
            val itemBg = GradientDrawable()
            itemBg.setColor(0xFF303134L.toInt())
            itemBg.cornerRadius = dp(8).toFloat()
            item.background = itemBg
            item.setOnClickListener {
                if (insertText(body, true)) AppLog.i("Inserted reply '$title'") else toast("Tap into a text box first")
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            list.addView(item, lp)
        }

        val scroll = ScrollView(this)
        scroll.addView(list)
        val bg = GradientDrawable()
        bg.setColor(0xF0202124L.toInt())
        bg.cornerRadius = dp(14).toFloat()
        scroll.background = bg

        val bp = bubbleParams
        val p = overlayParams()
        p.width = dp(250)
        p.height = if (replies.size > 5) dp(340) else WindowManager.LayoutParams.WRAP_CONTENT
        p.x = bp?.x ?: dp(8)
        p.y = (bp?.y ?: dp(200)) + dp(52)

        try {
            wm.addView(scroll, p)
            replyPanel = scroll
        } catch (e: Exception) {
            AppLog.i("Couldn't show reply panel: ${e.message}")
        }
    }

    private fun removeOverlays() {
        closeReplyPanel()
        val b = bubble ?: return
        try {
            wm.removeView(b)
        } catch (e: Exception) {
            // already gone
        }
        bubble = null
        statusView = null
        pauseBtn = null
        stopBtn = null
    }
}
