# Cúpla Focal

A Duolingo-style Irish app with a dialect switch. Munster is the default, and Connacht and Ulster are one tap away.

## What's in the prototype

- **Unit 1, *Na bunrudaí* (The basics):** three lessons of 5–6 phrases each (greetings, how you feel, about me), plus a review lesson.
- **Five exercise types:** new-phrase cards, multiple choice in both directions, word tiles, typing with fada buttons, and match-the-pairs.
- **The usual game parts:** XP, a daily goal, a streak, and a perfect-lesson bonus. Hearts are unlimited, so there are no energy limits.
- **Wrong answers come back** at the end of the lesson until you get them right.
- **Answer checking knows about dialects and fadas.** *Tá mé go maith* is accepted in Munster mode with a note that Munster usually says *Táim go maith*. A missing fada counts, with a "watch the fadas" hint.
- **Phrasebook** with tips, rough English pronunciation guides, the other dialects' versions, and links to native-speaker recordings on teanglann.ie.
- **Saved progress.** Inside Claude it saves to your account (the artifact `db` capability). Opened anywhere else it saves in the browser.

## Files

| File | What it is |
|---|---|
| `app.html` | The whole app: styles, course content and code. It is the page body that gets published as the Claude artifact. |
| `build.sh` | Wraps `app.html` into a standalone `index.html`. |
| `index.html` | Built output. Open it directly or host it, e.g. with GitHub Pages. Don't edit by hand. |
| `tests/play-through.cjs` | Plays every lesson in headless Chromium and checks the grading rules. |

## Editing the course

All the content is at the top of the `<script>` in `app.html`:

- `PHRASES`: each phrase's English, Irish, pronunciation guide, accepted alternatives, note, and the words to link to teanglann.ie. Any field can be a single value or `{ M, C, U }` per dialect.
- `UNIT.lessons`: which phrases go in which lesson.
- `TIPS`: the grammar tips in the phrasebook.

After editing:

```sh
./irish-app/build.sh
NODE_PATH=$(npm root -g) node irish-app/tests/play-through.cjs
```

## Caveats

- The lessons were drafted with AI help. The Munster stress patterns were checked against published pronunciations, but everything still needs a fluent speaker to review it.
- The pronunciation guides are rough English spellings. For the real sound, use teanglann.ie (native speakers in all three dialects) or abair.ie.
- There's no audio yet. Bundling clips per dialect is the obvious next step. abair.ie has Munster, Connacht and Ulster voices, but you'd need to ask the ABAIR team about using them in an app.
