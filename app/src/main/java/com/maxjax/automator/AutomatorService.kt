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
import android.graphics.Typeface
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
import android.widget.ImageView
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
    private var statusDot: View? = null
    private var playBtn: ImageView? = null
    private var pauseBtn: ImageView? = null
    private var stopBtn: ImageView? = null
    private var replyPanel: View? = null
    private var confirmPanel: View? = null

    private var statusFlashUntil = 0L
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
        val d = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, d))
                .build(),
            d + 3000L
        )
    }

    /**
     * Two taps in one gesture, 130ms apart. Two separate `tap` statements land 200-300ms apart,
     * which straddles the double-tap window, so apps register them as two single taps.
     */
    fun doubleTapAt(x: Float, y: Float): Boolean {
        val (w, h) = screenSize()
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val first = Path()
        first.moveTo(cx, cy)
        val second = Path()
        second.moveTo(cx, cy)
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(first, 0L, 40L))
                .addStroke(GestureDescription.StrokeDescription(second, 130L, 40L))
                .build(),
            3200L
        )
    }

    fun doubleTapNode(n: AccessibilityNodeInfo): Boolean {
        val r = bounds(n)
        if (r.isEmpty) return false
        return doubleTapAt(r.exactCenterX(), r.exactCenterY())
    }

    private fun dispatch(g: GestureDescription, timeoutMs: Long): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.i("✖ internal: gesture requested on the main thread, ignored")
            return false
        }
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
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
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

    /**
     * Writes everything on screen to the log so selectors can be picked without guessing.
     * Returns the number of items written, or -1 when the screen can't be read at all.
     */
    fun dumpScreen(): Int {
        val roots = appRoots()
        if (roots.isEmpty()) {
            AppLog.i("dump: nothing readable on screen")
            return -1
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
                // An unlabelled clickable is still worth listing: it can't be matched by a
                // selector, so its bounds are the only way to reach it.
                val interactive = n.isClickable || n.isLongClickable || n.isEditable
                val interesting = text.isNotBlank() || desc.isNotBlank() || interactive ||
                    (id.isNotBlank() && n.isScrollable)
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
                if (text.isBlank() && desc.isBlank() && id.isBlank()) {
                    sb.append("  (no label - tap it by position)")
                }
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
        return rows.size
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

    // ---------------------------------------------------------------- control bar

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

    private fun barIcon(icon: Icon, tint: Int, onClick: (() -> Unit)?): ImageView {
        val v = ImageView(this)
        v.setImageDrawable(IconDrawable(icon, tint))
        val pad = dp(9)
        v.setPadding(pad, pad, pad, pad)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        if (onClick != null) {
            v.background = circleRipple()
            v.isClickable = true
            v.setOnClickListener { onClick() }
        }
        return v
    }

    private fun iconOf(v: ImageView): IconDrawable? = v.drawable as? IconDrawable

    private fun showBubble() {
        if (bubble != null) return

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = roundedRect(Palette.BAR, dpf(24f), Palette.STROKE, dp(1))
        bar.setPadding(dp(6), dp(5), dp(10), dp(5))
        bar.elevation = dpf(8f)

        val size = dp(38)
        val handle = barIcon(Icon.GRIP, Palette.TEXT_FAINT, null)
        val play = barIcon(Icon.PLAY, Palette.OK) { runActiveFromBubble() }
        val pause = barIcon(Icon.PAUSE, Palette.TEXT) { togglePause() }
        val stop = barIcon(Icon.STOP, Palette.TEXT) { stopScript() }
        val chat = barIcon(Icon.CHAT, Palette.TEXT) { toggleReplyPanel() }
        val look = barIcon(Icon.SEARCH, Palette.TEXT) {
            closePanels()
            val n = dumpScreen()
            flashStatus(if (n < 0) "Can't read screen" else "$n items logged")
        }
        val close = barIcon(Icon.CLOSE, Palette.DANGER) { askShutdown() }

        val dot = View(this)
        dot.background = circle(Palette.TEXT_FAINT)

        val status = TextView(this)
        status.textSize = 11f
        status.setTextColor(Palette.TEXT_DIM)
        status.maxWidth = dp(96)
        status.maxLines = 1
        status.ellipsize = TextUtils.TruncateAt.END

        val divider = View(this)
        divider.setBackgroundColor(Palette.STROKE)

        bar.addView(handle, LinearLayout.LayoutParams(dp(28), size))
        bar.addView(play, LinearLayout.LayoutParams(size, size))
        bar.addView(pause, LinearLayout.LayoutParams(size, size))
        bar.addView(stop, LinearLayout.LayoutParams(size, size))
        bar.addView(chat, LinearLayout.LayoutParams(size, size))
        bar.addView(look, LinearLayout.LayoutParams(size, size))
        bar.addView(divider, LinearLayout.LayoutParams(dp(1), dp(20)).also {
            it.leftMargin = dp(4); it.rightMargin = dp(4)
        })
        bar.addView(close, LinearLayout.LayoutParams(size, size))
        bar.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).also { it.leftMargin = dp(6) })
        bar.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.leftMargin = dp(5) })

        val p = overlayParams()
        p.x = dp(10)
        p.y = dp(180)

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
                    // otherwise let the bar be dragged away for good.
                    val w = if (bar.width > 0) bar.width else dp(300)
                    val h = if (bar.height > 0) bar.height else dp(48)
                    val keep = dp(56)
                    val minX = minOf(keep - w, 0)
                    val maxX = maxOf(sw - keep, minX)
                    val maxY = maxOf(sh - h, 0)
                    p.x = (dragStartX + (e.rawX - touchStartX).toInt()).coerceIn(minX, maxX)
                    p.y = (dragStartY + (e.rawY - touchStartY).toInt()).coerceIn(0, maxY)
                    closePanels()
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
            AppLog.i("Couldn't show the control bar: ${e.message}")
            return
        }
        bubble = bar
        bubbleParams = p
        statusView = status
        statusDot = dot
        playBtn = play
        pauseBtn = pause
        stopBtn = stop
        updateBubble()
    }

    /** Briefly shows a result in the bar. Toasts are unreliable over a fullscreen app. */
    private fun flashStatus(msg: String) {
        statusFlashUntil = System.currentTimeMillis() + 2500
        statusView?.text = msg
        main.postDelayed({ updateBubble() }, 2600)
    }

    private fun updateBubble() {
        val r = runner
        val paused = running && r != null && r.paused
        if (System.currentTimeMillis() >= statusFlashUntil) {
            statusView?.text = when {
                paused -> "Paused"
                running -> "Running"
                else -> Store.activeScript(this) ?: "No script"
            }
        }
        val stateColor = when {
            paused -> Palette.WARN
            running -> Palette.OK
            else -> Palette.TEXT_FAINT
        }
        statusDot?.background = circle(stateColor)

        // Play is the live control when idle; pause and stop are live during a run.
        playBtn?.alpha = if (running) 0.3f else 1f
        playBtn?.isClickable = !running
        pauseBtn?.alpha = if (running) 1f else 0.3f
        pauseBtn?.isClickable = running
        stopBtn?.alpha = if (running) 1f else 0.3f
        stopBtn?.isClickable = running
        pauseBtn?.let { iconOf(it)?.setIconColor(if (paused) Palette.WARN else Palette.TEXT) }
    }

    private fun runActiveFromBubble() {
        val name = Store.activeScript(this)
        if (name == null) {
            toast("Open a script in the app and tap Set active")
            return
        }
        closePanels()
        runScript(name, 400)
    }

    // ---------------------------------------------------------------- panels

    private fun panelParams(width: Int): WindowManager.LayoutParams {
        val bp = bubbleParams
        val p = overlayParams()
        p.width = width
        p.x = bp?.x ?: dp(10)
        p.y = (bp?.y ?: dp(180)) + dp(56)
        return p
    }

    private fun panelShell(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = roundedRect(Palette.BAR, dpf(18f), Palette.STROKE, dp(1))
        box.elevation = dpf(10f)
        return box
    }

    private fun closePanels() {
        for (panel in listOf(replyPanel, confirmPanel)) {
            if (panel == null) continue
            try {
                wm.removeView(panel)
            } catch (e: Exception) {
                // already gone
            }
        }
        replyPanel = null
        confirmPanel = null
    }

    /** Turning the service off means a trip to Settings to undo, so it asks first. */
    private fun askShutdown() {
        if (confirmPanel != null) {
            closePanels()
            return
        }
        closePanels()

        val box = panelShell()
        box.setPadding(dp(16), dp(14), dp(16), dp(14))

        val title = TextView(this)
        title.text = "Turn off Phone Automator?"
        title.textSize = 15f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextColor(Palette.TEXT)
        box.addView(title)

        val note = TextView(this)
        note.text = "The bar disappears and scripts stop. Turn it back on in Accessibility settings."
        note.textSize = 12f
        note.setTextColor(Palette.TEXT_DIM)
        note.setLineSpacing(dpf(2f), 1f)
        box.addView(note, matchWrap(dp(8)))

        box.addView(
            uiButtonRow(
                uiSecondaryButton("Cancel") { closePanels() },
                uiPrimaryButton("Turn off") { shutdownService() }
            ),
            matchWrap(dp(14))
        )

        try {
            wm.addView(box, panelParams(dp(270)))
            confirmPanel = box
        } catch (e: Exception) {
            AppLog.i("Couldn't show the confirmation: ${e.message}")
        }
    }

    private fun shutdownService() {
        closePanels()
        stopScript()
        AppLog.i("Turned off from the control bar")
        removeOverlays()
        try {
            disableSelf()
        } catch (e: Exception) {
            AppLog.i("Couldn't turn the service off: ${e.message}")
            toast("Turn it off in Accessibility settings")
        }
    }

    private fun toggleReplyPanel() {
        if (replyPanel != null) {
            closePanels()
            return
        }
        closePanels()
        val replies = Store.parseReplies(Store.loadRepliesRaw(this))

        val list = panelShell()
        list.setPadding(dp(10), dp(12), dp(10), dp(12))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6), 0, 0, dp(8))
        val title = TextView(this)
        title.text = "Quick replies"
        title.textSize = 13f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextColor(Palette.TEXT)
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(barIcon(Icon.CLOSE, Palette.TEXT_FAINT) { closePanels() },
            LinearLayout.LayoutParams(dp(28), dp(28)))
        list.addView(header, matchWrap())

        if (replies.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No replies yet. Add them in the app."
            empty.textSize = 12f
            empty.setTextColor(Palette.TEXT_FAINT)
            empty.setPadding(dp(6), dp(4), dp(6), dp(4))
            list.addView(empty)
        }

        for ((name, body) in replies) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.setPadding(dp(11), dp(9), dp(11), dp(9))
            item.background = rectRipple(Palette.SURFACE_HI, dpf(10f))
            item.isClickable = true

            val n = TextView(this)
            n.text = name
            n.textSize = 13f
            n.setTypeface(Typeface.DEFAULT_BOLD)
            n.setTextColor(Palette.TEXT)
            item.addView(n)

            val preview = TextView(this)
            preview.text = body.replace('\n', ' ')
            preview.textSize = 11f
            preview.setTextColor(Palette.TEXT_FAINT)
            preview.maxLines = 1
            preview.ellipsize = TextUtils.TruncateAt.END
            item.addView(preview, matchWrap(dp(2)))

            item.setOnClickListener {
                if (insertText(body, true)) {
                    AppLog.i("Inserted reply '$name'")
                    closePanels()
                } else {
                    toast("Tap into a text box first")
                }
            }
            list.addView(item, matchWrap(dp(6)))
        }

        val content: View = if (replies.size > 5) {
            val sv = ScrollView(this)
            sv.addView(list)
            sv
        } else list

        val p = panelParams(dp(260))
        if (replies.size > 5) p.height = dp(340)

        try {
            wm.addView(content, p)
            replyPanel = content
        } catch (e: Exception) {
            AppLog.i("Couldn't show the replies panel: ${e.message}")
        }
    }

    private fun removeOverlays() {
        closePanels()
        val b = bubble ?: return
        try {
            wm.removeView(b)
        } catch (e: Exception) {
            // already gone
        }
        bubble = null
        statusView = null
        statusDot = null
        playBtn = null
        pauseBtn = null
        stopBtn = null
    }
}
