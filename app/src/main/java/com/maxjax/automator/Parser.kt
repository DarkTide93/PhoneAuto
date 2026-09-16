package com.maxjax.automator

import android.accessibilityservice.AccessibilityService

object Parser {
    private class Tok(val s: String, val quoted: Boolean)
    private class Line(val no: Int, val toks: List<Tok>)
    private class Cursor(val lines: List<Line>) {
        var pos = 0
    }
    private class Ctx {
        val avoids = ArrayList<Selector>()
        val stopIfs = ArrayList<Selector>()
    }

    private val paramRe = Regex("""^\s*param\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val placeholderRe = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    private val durRe = Regex("""(\d+(?:\.\d+)?)(ms|s|m)?""", RegexOption.IGNORE_CASE)
    private val coordRe = Regex("""(\d+(?:\.\d+)?)(%)?""")
    private val directions = setOf("up", "down", "left", "right")

    /** Parameters in declaration order with their default values. */
    fun extractParams(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val m = paramRe.find(raw) ?: continue
            out[m.groupValues[1]] = unquote(m.groupValues[2].trim())
        }
        return out
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

    fun parse(text: String, overrides: Map<String, String>): Script {
        val params = extractParams(text)
        for ((k, v) in overrides) {
            if (params.containsKey(k)) params[k] = v
        }

        val lines = ArrayList<Line>()
        text.lines().forEachIndexed { i, raw ->
            val no = i + 1
            if (paramRe.find(raw) != null) return@forEachIndexed
            val toks = tokenize(raw, no).map { t ->
                var s = t.s
                for ((k, v) in params) s = s.replace("{$k}", v)
                val left = placeholderRe.find(s)
                if (left != null) throw ParseException(no, "unknown parameter {${left.groupValues[1]}}")
                Tok(s, t.quoted)
            }
            if (toks.isNotEmpty()) lines.add(Line(no, toks))
        }

        val ctx = Ctx()
        val body = parseBlock(Cursor(lines), ctx, emptySet()).first
        return Script(body, ctx.avoids, ctx.stopIfs)
    }

    private fun tokenize(line: String, no: Int): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c.isWhitespace()) {
                i++
            } else if (c == '#') {
                break
            } else if (c == '"') {
                val sb = StringBuilder()
                i++
                var closed = false
                while (i < n) {
                    val ch = line[i]
                    if (ch == '\\' && i + 1 < n) {
                        sb.append(line[i + 1])
                        i += 2
                    } else if (ch == '"') {
                        closed = true
                        i++
                        break
                    } else {
                        sb.append(ch)
                        i++
                    }
                }
                if (!closed) throw ParseException(no, "missing closing quote")
                out.add(Tok(sb.toString(), true))
            } else {
                val start = i
                while (i < n && !line[i].isWhitespace()) i++
                out.add(Tok(line.substring(start, i), false))
            }
        }
        return out
    }

    private fun parseBlock(cur: Cursor, ctx: Ctx, terminators: Set<String>): Pair<List<Stmt>, String?> {
        val out = ArrayList<Stmt>()
        while (cur.pos < cur.lines.size) {
            val line = cur.lines[cur.pos]
            val first = line.toks[0]
            val kw = first.s.lowercase()
            cur.pos++
            if (!first.quoted && kw in terminators) return Pair(out, kw)
            val st = parseStmt(line, cur, ctx)
            if (st != null) out.add(st)
        }
        return Pair(out, null)
    }

    private fun parseStmt(line: Line, cur: Cursor, ctx: Ctx): Stmt? {
        val t = line.toks
        val no = line.no
        val kw = if (t[0].quoted) "" else t[0].s.lowercase()

        fun need(i: Int): Tok = t.getOrNull(i) ?: throw ParseException(no, "'$kw' is missing something after it")
        fun unexpected(i: Int): Nothing = throw ParseException(no, "unexpected '${t[i].s}'")
        fun word(i: Int, w: String): Boolean = t.getOrNull(i)?.let { !it.quoted && it.s.equals(w, ignoreCase = true) } ?: false

        return when (kw) {
            "wait" -> {
                val (lo, hi) = durRange(no, need(1).s)
                Stmt.Wait(no, lo, hi)
            }

            "tap", "longpress", "doubletap" -> {
                val (target, next) = parseTarget(no, t, 1)
                var force = false
                var i = next
                while (i < t.size) {
                    if (word(i, "gesture")) {
                        force = true
                        i++
                    } else {
                        unexpected(i)
                    }
                }
                Stmt.Tap(no, target, kw == "longpress", force, kw == "doubletap")
            }

            "swipe" -> {
                val a = need(1)
                val dirWord = a.s.lowercase()
                if (!a.quoted && dirWord in directions) {
                    val ms = t.getOrNull(2)?.let { dur(no, it.s) } ?: 300L
                    Stmt.SwipeDir(no, dirWord, ms)
                } else {
                    if (t.size < 5) throw ParseException(no, "use: swipe up|down|left|right  or  swipe x1 y1 x2 y2")
                    val ms = t.getOrNull(5)?.let { dur(no, it.s) } ?: 300L
                    Stmt.SwipeXY(no, coord(no, t[1].s), coord(no, t[2].s), coord(no, t[3].s), coord(no, t[4].s), ms)
                }
            }

            "scroll" -> {
                if (!word(1, "until")) throw ParseException(no, "use: scroll until text \"...\"")
                val (sel, next) = parseSelector(no, t, 2)
                var max = 10
                var dir = "up"
                var i = next
                while (i < t.size) {
                    val o = t[i].s.lowercase()
                    if (o == "max") {
                        max = int(no, t.getOrNull(i + 1)?.s)
                        i += 2
                    } else if (o in directions) {
                        dir = o
                        i++
                    } else {
                        unexpected(i)
                    }
                }
                Stmt.ScrollUntil(no, sel, max, dir)
            }

            "waitfor" -> {
                val (sel, next) = parseSelector(no, t, 1)
                var gone = false
                var timeout = 10_000L
                var i = next
                while (i < t.size) {
                    if (word(i, "gone")) {
                        gone = true
                        i++
                    } else if (word(i, "timeout")) {
                        timeout = dur(no, t.getOrNull(i + 1)?.s ?: "")
                        i += 2
                    } else {
                        unexpected(i)
                    }
                }
                Stmt.WaitFor(no, sel, gone, timeout)
            }

            "type" -> Stmt.TypeText(no, need(1).s)
            "clear" -> Stmt.Clear(no)
            "reply" -> Stmt.Reply(no, need(1).s)
            "back" -> Stmt.Global(no, AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> Stmt.Global(no, AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> Stmt.Global(no, AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> Stmt.Global(no, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "launch" -> Stmt.Launch(no, need(1).s)
            "log" -> Stmt.Print(no, t.drop(1).joinToString(" ") { it.s })
            "dump" -> Stmt.Dump(no)
            "stop" -> Stmt.Stop(no)
            "break" -> Stmt.Break(no)

            "repeat" -> {
                val count = int(no, need(1).s)
                val (body, term) = parseBlock(cur, ctx, setOf("end"))
                if (term == null) throw ParseException(no, "'repeat' has no matching 'end'")
                Stmt.Repeat(no, count, body)
            }

            "if", "while" -> {
                var i = 1
                var negate = false
                if (word(i, "not")) {
                    negate = true
                    i++
                }
                if (word(i, "exists")) i++
                val (sel, next) = parseSelector(no, t, i)
                if (kw == "if") {
                    if (next < t.size) unexpected(next)
                    val (body, term) = parseBlock(cur, ctx, setOf("else", "end"))
                    val elseBody: List<Stmt> = when (term) {
                        "end" -> emptyList()
                        "else" -> {
                            val (eb, term2) = parseBlock(cur, ctx, setOf("end"))
                            if (term2 == null) throw ParseException(no, "'if' has no matching 'end'")
                            eb
                        }
                        else -> throw ParseException(no, "'if' has no matching 'end'")
                    }
                    Stmt.If(no, sel, negate, body, elseBody)
                } else {
                    var max = 1000
                    var j = next
                    while (j < t.size) {
                        if (word(j, "max")) {
                            max = int(no, t.getOrNull(j + 1)?.s)
                            j += 2
                        } else {
                            unexpected(j)
                        }
                    }
                    val (body, term) = parseBlock(cur, ctx, setOf("end"))
                    if (term == null) throw ParseException(no, "'while' has no matching 'end'")
                    Stmt.While(no, sel, negate, max, body)
                }
            }

            "avoid" -> {
                ctx.avoids.add(parseSelector(no, t, 1).first)
                null
            }

            "stopif" -> {
                ctx.stopIfs.add(parseSelector(no, t, 1).first)
                null
            }

            "end", "else" -> throw ParseException(no, "'$kw' without a matching repeat/if/while")
            else -> throw ParseException(no, "unknown command '${t[0].s}'")
        }
    }

    private fun parseTarget(no: Int, t: List<Tok>, start: Int): Pair<TapTarget, Int> {
        val a = t.getOrNull(start)
        val b = t.getOrNull(start + 1)
        if (a != null && b != null && !a.quoted && !b.quoted && coordRe.matches(a.s) && coordRe.matches(b.s)) {
            return Pair(TapTarget.Point(coord(no, a.s), coord(no, b.s)), start + 2)
        }
        val (sel, next) = parseSelector(no, t, start)
        var i = next
        var index = 0
        val maybeIndex = t.getOrNull(i)
        if (maybeIndex != null && !maybeIndex.quoted && maybeIndex.s.equals("index", ignoreCase = true)) {
            index = int(no, t.getOrNull(i + 1)?.s)
            i += 2
        }
        return Pair(TapTarget.Node(sel, index), i)
    }

    private fun parseSelector(no: Int, t: List<Tok>, start: Int): Pair<Selector, Int> {
        val a = t.getOrNull(start) ?: throw ParseException(no, "expected a target like text \"Save\"")
        if (a.quoted) return Pair(Selector(SelKind.TEXT, nonEmpty(no, a.s)), start + 1)
        val kind = when (a.s.lowercase()) {
            "text" -> SelKind.TEXT
            "exact" -> SelKind.EXACT
            "id" -> SelKind.ID
            "desc" -> SelKind.DESC
            else -> throw ParseException(no, "expected text, exact, id or desc but found '${a.s}'")
        }
        val v = t.getOrNull(start + 1) ?: throw ParseException(no, "missing value after '${a.s}'")
        return Pair(Selector(kind, nonEmpty(no, v.s)), start + 2)
    }

    private fun nonEmpty(no: Int, s: String): String {
        if (s.isEmpty()) throw ParseException(no, "empty target text")
        return s
    }

    private fun int(no: Int, s: String?): Int {
        val v = s?.toIntOrNull()
        if (v == null || v < 0) throw ParseException(no, "expected a whole number but found '${s ?: "nothing"}'")
        return v
    }

    /** "2s" is a fixed wait; "1s-3s" is a random wait between the two. */
    private fun durRange(no: Int, s: String): Pair<Long, Long> {
        val dash = s.indexOf('-')
        if (dash <= 0) {
            val d = dur(no, s)
            return Pair(d, d)
        }
        val lo = dur(no, s.substring(0, dash))
        val hi = dur(no, s.substring(dash + 1))
        if (hi < lo) throw ParseException(no, "'$s' counts backwards - put the shorter time first")
        return Pair(lo, hi)
    }

    private fun dur(no: Int, s: String): Long {
        val m = durRe.matchEntire(s) ?: throw ParseException(no, "bad duration '$s' (use 500ms, 2s or 1m)")
        val v = m.groupValues[1].toDouble()
        return when (m.groupValues[2].lowercase()) {
            "s" -> (v * 1000).toLong()
            "m" -> (v * 60_000).toLong()
            else -> v.toLong()
        }
    }

    private fun coord(no: Int, s: String): Coord {
        val m = coordRe.matchEntire(s) ?: throw ParseException(no, "bad coordinate '$s' (use 540 or 50%)")
        return Coord(m.groupValues[1].toFloat(), m.groupValues[2] == "%")
    }
}
