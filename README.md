# DreamLog

An offline-first Android dream journal built for the half-awake 3 AM capture problem. It is a
single-purpose, monochrome personal tool: flat surfaces, text controls, plain Markdown, and no
dashboard.

## What it does

- **Hostage Alarm.** A scheduled alarm (`AlarmManager` + foreground service) rings at full volume.
  It has no dismiss and no snooze. The only ways out are recording at least the configured seconds
  of audio, typing at least the configured word count, or holding the no-dream escape for 15
  seconds.
- **Voice capture.** `MediaRecorder` writes an app-private `.m4a` and an Android
  `SpeechRecognizer` runs alongside it. The alarm is paused at 0% while the microphone is open and
  haptic ticks mark every four seconds. If recording stops below the unlock threshold, a 10-second
  countdown runs with the alarm ducked; reaching the end restores full volume.
- **Typing capture.** The alarm stays at the configured ducked volume, a 12-second inactivity
  watchdog restarts on every keystroke, and `8 / 15 words` is shown as plain text. Meeting the word
  threshold unlocks saving.
- **Obsidian-style links.** Manual `[[WikiLinks]]` and automatic whole-word entity matching share
  the same SQLite graph. Saving `my brother went to China` after `Brother` and `China` exist becomes
  `my [[Brother]] went to [[China]]` without double-wrapping existing links.
- **Markdown transfer.** Import `.md` files with the Storage Access Framework; export every note to
  a chosen folder as `.md`, preserving wiki links.
- **Graph.** A force-directed Compose Canvas graph with spring, repulsion, gravity, damping, a
  180-frame cool-down, and three hard bounding-box separation passes per tick. Nodes and labels are
  kept apart at `135.dp × 70.dp` minimum clearance. Pan, pinch zoom, node drag, and tap are
  supported. Concepts open a flat bottom sheet of linked notes.
- **No sample data.** The database starts empty. Every note in the app comes from the user.

## Requirements

- Android 8.0 (API 26) or newer.
- Microphone for voice capture; speech recognition availability depends on the device's on-device
  recognizer. The raw recording is independent of transcription, so a failed transcript never
  discards the audio.
- Notification permission and full-screen alarm permission for the most reliable lock-screen
  behavior on Android 13+ and 14+.
- Exact-alarm access. DreamLog declares the alarm-app exact-alarm capability and also surfaces the
  system exact-alarm setting if a device requires it.

## Build and release

GitHub Actions is the authoritative build and release pipeline (`.github/workflows/android.yml`).
Every push to `main` builds and runs unit tests. A tag beginning with `v` publishes a GitHub Release
with release and debug APKs.

To cut a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The optional release-signing secrets are:

| Secret | Purpose |
| --- | --- |
| `DREAMLOG_KEYSTORE_BASE64` | Base64-encoded JKS keystore |
| `DREAMLOG_KEYSTORE_PASSWORD` | Keystore password |
| `DREAMLOG_KEY_ALIAS` | Key alias |
| `DREAMLOG_KEY_PASSWORD` | Key password |

Without those secrets, CI still builds a release APK signed with the standard debug key so the
artifact remains installable for personal testing.

Local build, if desired:

```bash
./gradlew assembleDebug
```

## Project layout

```
app/src/main/java/com/lemomation/dreamlog/
  MainActivity.kt                 single activity, navigation, runtime permissions
  DreamLogApplication.kt          application-scoped Room repository
  DreamLogViewModel.kt            database flows and graph projection
  data/
    Database.kt                   Room entities, DAOs, WikiLinkEngine, repository
    MarkdownTransfer.kt           Storage Access Framework importer/exporter
  alarm/
    AlarmController.kt            scheduler, receivers, foreground service, capture coordinator
  ui/
    App.kt                        navigation shell and plain-text bottom bar
    theme/Theme.kt                strict monochrome palette, zero-radius shapes
    components/Controls.kt        flat buttons, hairlines, labels
    screens/
      NotesScreen.kt
      NoteEditorScreen.kt
      AlarmSetupScreen.kt
      HostageAlarmScreen.kt
      GraphScreen.kt
```

## Design contract

Only these colors are used: `#111111` background, `#000000` alarm background, `#181818` surface,
`#262626` hairline, `#E5E5E5` primary text, `#737373` secondary text, `#8C8C8C` default node,
`#E5E5E5` selected node, `#3D3D3D` edge, and `#9E9E9E` graph label. There are no gradients, glows,
shadows, rounded cards, category pills, scores, streaks, seeded notes, or decorative icons.

## Privacy

The database and voice recordings live in app-private storage. The app has no network permission
and no analytics. Markdown import/export happens only through user-selected Storage Access Framework
URIs. Android cloud backup is disabled.
