# Verification — Habit control moved to Agenda + compact setup dialog

Date: 2026-10-03 · Branch: `master` (worktree; no commits made) · Executor: Claude Code

## Scope delivered

1. **Agenda is the only place to toggle Habit.** The Files editor no longer
   offers the HABIT action: `OrgRenderer.kt` lost the per-heading `HabitChip`
   and the `onToggleHabit` plumbing (heading rendering untouched — fold,
   planning chip, tags all intact), and `FileEditorScreen.kt` /
   `FileEditorViewModel.kt` lost the habit dialog, feedback card and write
   functions. The Agenda habit consistency graph is untouched.
2. **Habit action on Agenda entries.** Every `AgendaEntryRow` with a valid
   parse-time source identity (`sourceOffset >= 0 && titleOffset >= 0`) shows
   a compact mono `HABIT` chip beside the time chip. Non-habit tap opens the
   schedule setup dialog; active-habit tap removes only `STYLE=habit`
   (SCHEDULED preserved) via `OrgHeadingStyleService.disableHabitStyle`. Row
   tap still opens the source file; the TODO keyword chip keeps its own
   action. Writes go through new `OrgHeadingStyleService` overloads
   (`enableHabit(entry, …)` / `disableHabitStyle(entry)`) that map the agenda
   entry's exact identity onto the existing verified path: fresh-read
   identity re-verification, surgical subtree edit, dual-parser validation,
   repository read-back. After each write the ViewModel refreshes the Agenda
   and surfaces a success/error notice dialog.
3. **Redesigned setup dialog** (`ui/screens/HabitScheduleDialog.kt`, compact
   M3 form): consistent 6dp vertical rhythm (no large empty gaps), aligned
   label columns (`Repeat` / `Every` at a fixed 48dp), the cadence on one
   line (`Every [count] [Day|Week|Month|Year]` segments), deadline choices as
   two equal `weight(1f)` segments in an `IntrinsicSize.Min` row with a
   36dp-min centered cell so “Deadline window” can never collapse into
   vertical text, and the whole form scrolls on short displays. Kept: the
   required explicit deadline choice (unselected start, Confirm disabled
   until chosen), date/count/window validation incl. the Org
   `/deadline > scheduled` rule, repeater `+`/`++`/.`+` semantics, live
   `SCHEDULED: <…>` preview, and schedule prefill for existing habits
   (date/cadence reversed from the parsed `OrgHabit` day counts; the deadline
   choice is never prefilled).

## Build & artifact verification

Toolchain: zulu-21 JDK (`mise`), Gradle 8.13 wrapper, SDK from
`local.properties`. Raw logs kept under the state directory, not chat.

| Check | Command | Result |
| --- | --- | --- |
| Release build | `./gradlew :app:assembleRelease` | **BUILD SUCCESSFUL** (exit 0, 1m 24s) — log: `build-release.log` |
| Kotlin compile gate | `./gradlew :app:compileReleaseKotlin` | BUILD SUCCESSFUL (exit 0) — log: `compile-kotlin.log` |
| Signature | `apksigner verify --verbose --print-certs app-release.apk` | **Verifies** · v2 scheme: true · 1 signer (RSA 2048, CN=lzn) — log: `apksigner-verify.log` |
| Alignment | `zipalign -c 4 app-release.apk` | exit 0, no output (in-alignment) — log: `zipalign-check.log` |
| Patch hygiene | `git diff --check` | exit 0, no output (no whitespace/conflict-marker errors) |
| Files-editor purge | grep -i habit on FileEditorScreen/FileEditorViewModel/OrgRenderer | 0 matches |

## Artifact

- Copied to: `/tmp/claude-supervisor.org-android-habit-agenda.3udtLW/app-release.apk`
- SHA-256: `42a42eb5375c5ef5c60a6d3c80beda92d8b805e4a0380e71dc2790ffee17e4ea`
- Size: 6,271,734 bytes

## Constraint compliance

- No unit tests written; no E2E, emulator, or device runs; APK not installed.
- No commits, pushes, resets, cleans, or checkouts; all pre-existing
  uncommitted/untracked work preserved (note: `OrgRenderer.kt`'s only
  uncommitted user change was the Files habit chip this task removes — after
  removal the file is byte-identical to HEAD, verified by an empty
  `git diff HEAD`).
- Signing secrets untouched; only public certificate fingerprints recorded.
- This file is task-specific; the existing `VERIFICATION.md` was not touched.
