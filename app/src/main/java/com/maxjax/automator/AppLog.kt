package com.maxjax.automator

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object AppLog {
    private const val MAX_LINES = 2500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean(false)

    @Volatile
    var listener: (() -> Unit)? = null

    fun i(msg: String) {
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        android.util.Log.i("Automator", msg)
        notifyListener()
    }

    /**
     * A screen dump writes hundreds of lines at once; redrawing the log view for each one
     * would lock up the UI, so repaints are coalesced.
     */
    private fun notifyListener() {
        if (listener == null) return
        if (!notifyPending.compareAndSet(false, true)) return
        main.postDelayed({
            notifyPending.set(false)
            listener?.invoke()
        }, 100)
    }

    fun all(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        main.post { listener?.invoke() }
    }
}
