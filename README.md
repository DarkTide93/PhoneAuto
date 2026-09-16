# Phone Automator

An Android accessibility-service app that runs your own scripts — tap, swipe, scroll, type —
with a floating control bubble and quick replies. GitHub Actions builds the APK, so you never
need Android Studio or a computer.

---

## Get the APK on your phone

Every push to this repo builds an APK and publishes it as a release. Nothing to install first.

1. Open the repo's **Actions** tab and wait for **Build APK** to go green (about 3–5 minutes).
2. Open **Releases** (right-hand side of the repo home page) and pick the newest **Build N**.
3. Tap `PhoneAutomator-N.apk` to download it, then open it to install.
   Android will ask you to allow installs from your browser or file manager — say yes.

The signing key is committed with the project (`app/automator.keystore`), so every build installs
over the last one as a normal update. Your scripts, quick replies and settings survive updates.

> The key is public and is only here so the phone accepts the update. It is not a secret and
> should not be reused for anything that matters.

## Turn it on

1. **Settings → Accessibility → Phone Automator → On.**
2. If the switch is greyed out: **Settings → Apps → Phone Automator → ⋮ (top right) →
   Allow restricted settings**, then go back and turn it on. Android does this to every
   sideloaded app that asks for accessibility.

When it's on, the control bubble appears on screen and the app's main page says **Service: ON**.

## First run

The app ships with a **Smoke test** script already set as active. It taps nothing, so it's safe
to run anywhere — it goes Home, opens and closes the notification shade, and writes what it can
see to the log.

1. Open the app, tap **Smoke test**, tap **Run**. It starts after 3 seconds; keep your hands off
   the screen.
2. Open **Logs** from the main screen. You should see the run, plus two screen dumps.
3. Tap **Copy** and paste the log into the chat — that's how we debug together.

There's also a **Demo About phone** script that opens Settings and taps a row, to check that
finding and tapping real UI works on your phone.

## The bubble

| Button | What it does |
| ------ | ------------ |
| ⠿ | drag the bubble around (it can't be dragged off screen) |
| ▶ | run the active script |
| ⏸ | pause / resume |
| ■ | stop |
| 💬 | quick replies — tap one to drop it into the focused text box |
| 🔍 | write everything on screen to the log |

## Writing scripts

Scripts live inside the app — open one, edit it, tap **Save**. Changing a script never needs a
rebuild. **Help** in the editor (or **Command reference** on the main screen) has the full
language. The short version:

```
param target = About phone      # asked for when you tap Run

launch com.android.settings
wait 2s
scroll until text "{target}" max 15
tap text "{target}"
back
```

- **Targets:** `text "Save"` (contains), `exact "Save"`, `id "send"`, `desc "Menu"`.
  A bare `"Save"` means `text "Save"`. Matching ignores case.
- **Flow:** `repeat N … end`, `if … else … end`, `while … max N … end`, `break`, `stop`.
- **Rules:** `avoid text "Delete"` makes taps and scrolls skip anything containing it;
  `stopif text "Try again"` stops the run the moment that appears.
- A tap or wait that can't find its target stops the script and logs the line number.

### Finding what to tap

Don't guess selectors. Open the app you want to automate, tap **🔍** on the bubble, then open
**Logs** and tap **Copy**. Every visible item is listed with its text, description, view id and
position, in top-to-bottom order — so `index 1` always means the second one down.

You can also put `dump` on a line in a script to capture the screen mid-run.

## How we iterate

1. You install the newest APK and run a script.
2. Something goes wrong → **Logs → Copy** → paste it into the chat, with a 🔍 dump if the problem
   is "it can't find the button".
3. I push a fix to this branch; Actions builds a new **Build N**; you install it over the old one.

Because scripts are stored on the phone, most changes you'll want are script edits with no
rebuild at all. Only changes to the app itself need a new APK.

## If a build fails

Open **Actions → the red run → Build** and read the last lines, or paste them into the chat.
The run also uploads a test report artifact when the parser tests fail.

## What's in here

```
app/src/main/java/com/maxjax/automator/
  AutomatorService.kt   the accessibility service: gestures, node search, bubble, screen dump
  Runner.kt             executes a parsed script on a background thread
  Parser.kt             turns script text into statements
  ScriptModel.kt        statement and selector types
  Store.kt              scripts, parameters and quick replies on disk
  MainActivity.kt       script list and service status
  ScriptEditorActivity.kt / QuickRepliesActivity.kt / LogActivity.kt
  Help.kt               the in-app command reference
app/src/test/           parser tests, run on every push
.github/workflows/      the APK build
```

## Building locally (optional)

Only if you're on a computer with the Android SDK:

```
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # parser tests
```
