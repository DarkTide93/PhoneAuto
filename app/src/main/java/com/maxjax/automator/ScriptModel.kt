package com.maxjax.automator

enum class SelKind { TEXT, EXACT, ID, DESC }

data class Selector(val kind: SelKind, val value: String) {
    fun describe(): String = "${kind.name.lowercase()} \"$value\""
}

data class Coord(val value: Float, val percent: Boolean)

sealed class TapTarget {
    class Point(val x: Coord, val y: Coord) : TapTarget()
    class Node(val sel: Selector, val index: Int) : TapTarget()
}

sealed class Stmt(val line: Int) {
    class Wait(line: Int, val minMs: Long, val maxMs: Long) : Stmt(line)
    class Tap(line: Int, val target: TapTarget, val long: Boolean, val forceGesture: Boolean) : Stmt(line)
    class SwipeDir(line: Int, val dir: String, val ms: Long) : Stmt(line)
    class SwipeXY(line: Int, val x1: Coord, val y1: Coord, val x2: Coord, val y2: Coord, val ms: Long) : Stmt(line)
    class ScrollUntil(line: Int, val sel: Selector, val max: Int, val dir: String) : Stmt(line)
    class WaitFor(line: Int, val sel: Selector, val gone: Boolean, val timeoutMs: Long) : Stmt(line)
    class TypeText(line: Int, val text: String) : Stmt(line)
    class Clear(line: Int) : Stmt(line)
    class Reply(line: Int, val name: String) : Stmt(line)
    class Global(line: Int, val action: Int) : Stmt(line)
    class Launch(line: Int, val pkg: String) : Stmt(line)
    class Print(line: Int, val msg: String) : Stmt(line)
    class Dump(line: Int) : Stmt(line)
    class Stop(line: Int) : Stmt(line)
    class Break(line: Int) : Stmt(line)
    class Repeat(line: Int, val count: Int, val body: List<Stmt>) : Stmt(line)
    class If(line: Int, val sel: Selector, val negate: Boolean, val body: List<Stmt>, val elseBody: List<Stmt>) : Stmt(line)
    class While(line: Int, val sel: Selector, val negate: Boolean, val max: Int, val body: List<Stmt>) : Stmt(line)
}

class Script(val body: List<Stmt>, val avoids: List<Selector>, val stopIfs: List<Selector>)

class ParseException(val lineNo: Int, msg: String) : Exception("Line $lineNo: $msg")
