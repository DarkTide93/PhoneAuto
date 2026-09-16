package com.maxjax.automator

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView

object Help {
    val TEXT = """
BASICS
One command per line. # starts a comment.
Put text with spaces in "quotes".
Durations: 500ms  2s  1.5s  1m  (bare number = ms)
Positions: pixels (540 1200) or percent (50% 80%)
Indentation is optional.

PARAMETERS
param count = 10
param name = "About phone"
Use anywhere as {count} / {name}.
You're asked for values when you tap Run or Set active.

TARGETS
text "Save"     text or description contains Save
exact "Save"    text or description is exactly Save
id "send"       view ID (com.app:id/send or just send)
desc "Menu"     description contains Menu
"Save"          short for text "Save"
Matching ignores upper/lower case.

ACTIONS
tap text "OK"           tap it (waits up to 2s to appear)
tap text "OK" index 1   tap the 2nd match (starts at 0)
tap 50% 80%             tap a screen position
longpress text "Photo"  long-press, same options as tap
tap text "OK" gesture   force a real touch, not a click
swipe up                finger moves up (list scrolls down)
swipe down|left|right [duration]
swipe 50% 80% 50% 20% [duration]
scroll until text "About" [max 10] [up|down|left|right]
waitfor text "Done" [timeout 10s]
waitfor text "Loading" gone [timeout 10s]
type "hello"            add text to the focused box
clear                   empty the focused box
reply "Thanks"          add a saved quick reply
back  home  recents  notifications
launch com.android.settings
wait 2s
log "a message"
dump                    write everything on screen to Logs
stop

FLOW
repeat 10
  ...
end

if text "Error"         (or: if not text "Error")
  ...
else
  ...
end

while text "Next" [max 50]   (or: while not ...)
  ...
end

break                   leave the current repeat/while

RULES (whole script, any line)
avoid text "Delete"
  tap and scroll-until skip anything containing it
stopif text "Try again"
  stop the run as soon as it's on screen

A tap/scroll/waitfor that can't find its target
stops the script and logs the line number. So does
a tap that is found but refused — add `gesture` to
the end of that line to touch it for real instead.
Use if / waitfor for things that might not show.

BUBBLE
⠿ drag   ▶ run active   ⏸ pause/resume   ■ stop
💬 quick replies
🔍 write what's on screen to Logs
Your own app's screens are ignored by scripts.

FINDING TARGETS
Open the app you want to automate, tap 🔍 on the
bubble, then open Logs and tap Copy. Each line
shows the text, description, id and position of
one thing on screen — pick your selectors from it.
Nodes are listed top to bottom, so `index 1` is
always the second one down.
""".trim()
}

fun Activity.showHelp() {
    val tv = TextView(this)
    tv.text = Help.TEXT
    tv.typeface = Typeface.MONOSPACE
    tv.textSize = 12f
    tv.setTextIsSelectable(true)
    tv.setPadding(dp(16), dp(12), dp(16), dp(12))
    val h = HorizontalScrollView(this)
    h.addView(tv)
    val v = ScrollView(this)
    v.addView(h)
    AlertDialog.Builder(this)
        .setTitle("Command reference")
        .setView(v)
        .setPositiveButton("Close", null)
        .show()
}
