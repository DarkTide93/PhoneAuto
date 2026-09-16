package com.maxjax.automator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parser is plain Kotlin, so it can be exercised on the desktop JVM. These tests are the
 * safety net for the script language: if a command in the in-app reference stops parsing, or an
 * error stops pointing at the right line, this fails before an APK is ever built.
 */
class ParserTest {

    private fun parse(text: String, overrides: Map<String, String> = emptyMap()): Script =
        Parser.parse(text, overrides)

    private fun expectError(text: String, onLine: Int) {
        try {
            parse(text)
            fail("expected a parse error on line $onLine, but the script parsed")
        } catch (e: ParseException) {
            assertEquals("error reported on the wrong line (${e.message})", onLine, e.lineNo)
        }
    }

    // ------------------------------------------------------------ seeded scripts

    @Test
    fun `the seeded smoke test parses`() {
        val body = parse(
            """
            log "smoke test starting"
            home
            wait 1s
            dump
            notifications
            wait 1s
            dump
            back
            wait 500ms
            log "smoke test finished"
            """.trimIndent()
        ).body
        assertEquals(10, body.size)
    }

    @Test
    fun `the seeded demo parses and fills in its parameter`() {
        val script = parse(
            """
            param target = About phone

            launch com.android.settings
            wait 2s
            scroll until text "{target}" max 15
            tap text "{target}" 
            back
            """.trimIndent()
        )
        val scroll = script.body[2] as Stmt.ScrollUntil
        assertEquals("About phone", scroll.sel.value)
        assertEquals(15, scroll.max)
    }

    // ------------------------------------------------------------ the whole reference

    @Test
    fun `every command in the in-app reference parses`() {
        val script = parse(
            """
            param count = 10
            param name = "About phone"

            tap text "OK"
            tap text "OK" index 1
            tap 50% 80%
            tap 540 1200
            longpress text "Photo"
            tap text "OK" gesture
            tap id "send"
            tap desc "Menu"
            tap exact "Save"
            tap "Save"
            swipe up
            swipe down 500ms
            swipe left
            swipe right 1s
            swipe 50% 80% 50% 20%
            swipe 50% 80% 50% 20% 600ms
            scroll until text "{name}"
            scroll until text "About" max 10
            scroll until text "About" max 10 down
            waitfor text "Done"
            waitfor text "Done" timeout 10s
            waitfor text "Loading" gone timeout 3s
            type "hello"
            clear
            reply "Thanks"
            back
            home
            recents
            notifications
            launch com.android.settings
            wait 2s
            log "a message"
            dump
            avoid text "Delete"
            stopif text "Try again"

            repeat {count}
              tap text "Next"
              break
            end

            if text "Error"
              log "err"
            else
              log "fine"
            end

            if not exists text "Error"
              log "clean"
            end

            while text "Next" max 50
              tap text "Next"
            end

            while not text "Done"
              wait 1s
            end

            stop
            """.trimIndent()
        )
        assertEquals(1, script.avoids.size)
        assertEquals(1, script.stopIfs.size)
        assertTrue(script.body.isNotEmpty())
    }

    // ------------------------------------------------------------ behaviour

    @Test
    fun `avoid and stopif are rules, not statements`() {
        val script = parse("avoid text \"X\"\nstopif text \"Y\"\nback")
        assertEquals(1, script.avoids.size)
        assertEquals(1, script.stopIfs.size)
        assertEquals(1, script.body.size)
    }

    @Test
    fun `dump becomes a dump statement`() {
        assertTrue(parse("dump").body[0] is Stmt.Dump)
    }

    @Test
    fun `a parameter falls back to its default`() {
        assertEquals("Hi", (parse("param a = Hi\nlog {a}").body[0] as Stmt.Print).msg)
    }

    @Test
    fun `a saved value overrides the default`() {
        assertEquals("Yo", (parse("param a = Hi\nlog {a}", mapOf("a" to "Yo")).body[0] as Stmt.Print).msg)
    }

    @Test
    fun `parameter defaults may be quoted`() {
        assertEquals("About phone", Parser.extractParams("param a = \"About phone\"")["a"])
    }

    @Test
    fun `a script of only comments does nothing`() {
        assertTrue(parse("# just a note\n\n").body.isEmpty())
    }

    @Test
    fun `line numbers survive blank lines and comments`() {
        assertEquals(4, parse("\n# note\n\nback").body[0].line)
    }

    @Test
    fun `blocks keep their bodies`() {
        assertEquals(2, (parse("repeat 2\n back\n back\nend").body[0] as Stmt.Repeat).body.size)
        val branch = parse("if text \"A\"\n back\nelse\n home\n home\nend").body[0] as Stmt.If
        assertEquals(1, branch.body.size)
        assertEquals(2, branch.elseBody.size)
        assertEquals(1, (parse("repeat 2\n while text \"A\"\n  back\n end\nend").body[0] as Stmt.Repeat).body.size)
    }

    @Test
    fun `durations convert to milliseconds`() {
        assertEquals(2000L, (parse("wait 2s").body[0] as Stmt.Wait).minMs)
        assertEquals(1500L, (parse("wait 1.5s").body[0] as Stmt.Wait).minMs)
        assertEquals(60_000L, (parse("wait 1m").body[0] as Stmt.Wait).minMs)
        assertEquals(250L, (parse("wait 250").body[0] as Stmt.Wait).minMs)
    }

    @Test
    fun `a plain wait has an empty range`() {
        val w = parse("wait 2s").body[0] as Stmt.Wait
        assertEquals(w.minMs, w.maxMs)
    }

    @Test
    fun `a random wait keeps both ends`() {
        val w = parse("wait 1s-3s").body[0] as Stmt.Wait
        assertEquals(1000L, w.minMs)
        assertEquals(3000L, w.maxMs)
    }

    @Test
    fun `a random wait may mix units`() {
        val w = parse("wait 750ms-2s").body[0] as Stmt.Wait
        assertEquals(750L, w.minMs)
        assertEquals(2000L, w.maxMs)
    }

    @Test fun `a backwards range is rejected`() = expectError("wait 3s-1s", 1)
    @Test fun `a range with a bad end is rejected`() = expectError("wait 1s-zz", 1)

    @Test
    fun `a quoted keyword is text, not a keyword`() {
        assertEquals(1, parse("tap \"end\"").body.size)
    }

    @Test
    fun `a hash inside quotes is not a comment`() {
        assertEquals("a # b", (parse("log \"a # b\"").body[0] as Stmt.Print).msg)
    }

    @Test
    fun `doubletap parses and is marked as a double`() {
        val t = parse("doubletap 50% 45%").body[0] as Stmt.Tap
        assertTrue(t.double)
        assertTrue(!t.long)
        val n = parse("doubletap text \"Heart\"").body[0] as Stmt.Tap
        assertTrue(n.double)
    }

    @Test
    fun `a plain tap is not a double`() {
        assertTrue(!(parse("tap 50% 45%").body[0] as Stmt.Tap).double)
        assertTrue(!(parse("longpress text \"X\"").body[0] as Stmt.Tap).double)
    }

    @Test
    fun `a bare quoted target means text`() {
        val tap = parse("tap \"Save\"").body[0] as Stmt.Tap
        assertEquals(SelKind.TEXT, (tap.target as TapTarget.Node).sel.kind)
    }

    @Test
    fun `percent and pixel taps are told apart`() {
        val pct = (parse("tap 50% 80%").body[0] as Stmt.Tap).target as TapTarget.Point
        assertTrue(pct.x.percent)
        val px = (parse("tap 540 1200").body[0] as Stmt.Tap).target as TapTarget.Point
        assertTrue(!px.x.percent)
        assertEquals(540f, px.x.value, 0.001f)
    }

    // ------------------------------------------------------------ errors

    @Test fun `unknown command`() = expectError("back\nfrobnicate\nhome", 2)
    @Test fun `unclosed quote`() = expectError("back\ntap \"Save\nhome", 2)
    @Test fun `repeat without end`() = expectError("repeat 3\n back", 1)
    @Test fun `if without end`() = expectError("if text \"A\"\n back", 1)
    @Test fun `while without end`() = expectError("while text \"A\"\n back", 1)
    @Test fun `stray end`() = expectError("back\nend", 2)
    @Test fun `stray else`() = expectError("back\nelse", 2)
    @Test fun `unknown parameter`() = expectError("back\nlog {nope}", 2)
    @Test fun `bad duration`() = expectError("wait 3x", 1)
    @Test fun `bad selector kind`() = expectError("tap nonsense \"x\"", 1)
    @Test fun `trailing junk after if`() = expectError("if text \"A\" junk\n back\nend", 1)
    @Test fun `scroll without until`() = expectError("scroll text \"A\"", 1)
    @Test fun `swipe with too few coordinates`() = expectError("swipe 10 20 30", 1)
    @Test fun `empty target text`() = expectError("tap text \"\"", 1)
}
