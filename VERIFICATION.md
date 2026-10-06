# VERIFICATION — Files-tab per-heading habit STYLE control + Release APK

Date: 2026-10-03 (rev 2, post-review corrections) · Repo: `org-android` (worktree preserved;
no commits, no pushes; all pre-existing uncommitted/untracked user changes untouched)

## Feature (as delivered)

In the Files tab, opening an `.org` file renders headings through `OrgRenderer`. Every
heading whose exact source identity could be verified shows a **HABIT chip**:

- **Tap on a non-habit heading** → *Set up habit* dialog: explicit habit scheduling —
  start date (prefilled from an existing SCHEDULED or today), repeater type
  (`+` / `++` / `.+`), repeat count + unit (d/w/m/y), and a **deadline choice that starts
  unselected**: the user must tap either “Due on scheduled day” or “Deadline window”;
  **Confirm stays disabled until a choice is made** (existing schedules prefill only
  date/cadence/window-magnitude — never the deadline choice). When a window is chosen, a
  valid count/unit is required plus Org's rule *deadline interval > scheduled interval*
  (validated in the dialog and again in the service before writing). The dialog
  live-previews the exact Org timestamp. **Cancel writes nothing.**
- **Confirm** → writes only that heading's own region: `:STYLE: habit` (drawer created
  directly below the heading if missing, other properties preserved) and the heading's own
  SCHEDULED set to `SCHEDULED: <yyyy-MM-dd Eee TYPEcountUnit[/window]>` — the exact native
  Org habit grammar `OrgAgendaParser`/`OrgHabitRepeat` parse (no invented deadline format).
- **SCHEDULED replacement is planning-line anchored**: the service matches only a real
  planning line — line-start anchored, preceded solely by indentation and well-formed
  `DEADLINE:`/`CLOSED:` segments — so free text in the heading body mentioning “SCHEDULED:”
  can never match. A standalone SCHEDULED line is replaced in place (indent and trailing
  content preserved); on a combined planning line (`DEADLINE: <..> SCHEDULED: <..>`) the
  other segments are kept and the new timestamp becomes its own planning line directly
  below the heading. The prefill regex in the dialog mirrors the same anchored grammar.
- **Tap on a habit heading** → turns the habit off: removes **only** the `:STYLE:` line
  (drawer dropped only when it becomes empty); SCHEDULED/planning lines stay intact.
- Feedback banner on success/error; the rendered tree is reloaded from the persisted file
  after each change. The edit-mode buffer re-binds to the new content (documentVersion bump)
  so a later manual save can never revert the property change. The action is blocked with a
  clear message while unsaved edits exist. No actions were added to file-list cards.

### Safety properties

- Exact source identity (heading offset + title offset + level + keyword token), re-verified
  against a fresh SAF read before any edit — a same-titled sibling can never match.
- All edits spliced inside the entry's own region (heading line → next heading of any level,
  the same slice the agenda parser reads); children and unrelated bytes preserved.
- Pre-write semantic validation through **both** parsers: orgzly viewer reparse
  (`isHabitStyle` at the same offset) and `OrgAgendaParser` reparse (`habit != null` with the
  exact scheduled date, srDays and drDays requested). Any mismatch aborts before persisting.
- Writes go through `OrgFileRepository.writeOrgFile` (SAF write + read-back verification +
  search-index sync). Never round-trips through `OrgParserWrapper.writeContent`.

## Changed paths (this task)

| Path | Change |
|---|---|
| `app/src/main/java/com/orgutil/domain/model/OrgModels.kt` | `OrgNode` + `sourceOffset`, `titleOffset`, `isHabitStyle`, `hasRepeatingScheduled` (defaulted) |
| `app/src/main/java/com/orgutil/data/mapper/OrgParserWrapper.kt` | verified heading-identity resolution (headline scan ↔ orgzly node list, per-heading level/title/token validation; unverifiable ⇒ offset −1 ⇒ no chip) + own-drawer STYLE / repeating-SCHEDULED flags |
| `app/src/main/java/com/orgutil/data/repository/OrgHeadingStyleService.kt` | **new** — `enableHabit` / `disableHabitStyle`, surgical subtree edits, planning-line-anchored SCHEDULED rewrite, dual reparse validation, `writeOrgFile` persistence, shared `habitDurationDays` |
| `app/src/main/java/com/orgutil/ui/viewmodel/FileEditorViewModel.kt` | `enableHabit`/`disableHabitStyle`, `habitFeedback`, `documentVersion`, unsaved-edits guard, reload-after-write |
| `app/src/main/java/com/orgutil/ui/components/OrgRenderer.kt` | per-heading `HabitChip` (state-filled, tap-proof against fold toggle), recursive through child headings |
| `app/src/main/java/com/orgutil/ui/screens/FileEditorScreen.kt` | `HabitScheduleDialog` (date / repeater / cadence / **explicit tri-state deadline choice** with validation + live preview), feedback banner, editor re-bind |
| `app/src/main/res/values/strings.xml` | 12 habit dialog strings |

## Artifact

- Path: `/home/volis/development/fast-development/tool/org-android-workspace/org-android/app/build/outputs/apk/release/app-release.apk`
- Size: 6,270,906 bytes · built 2026-10-03 09:45 (`./gradlew assembleRelease`, BUILD SUCCESSFUL)

## Independent verification results (final artifact)

| Check | Command | Result |
|---|---|---|
| SHA-256 | `sha256sum app-release.apk` | `d4d54e12417d273ba74bdf32fea94686cfe0ef08a16eebe562056e13577a2fd8` |
| Signature | `apksigner verify --verbose` | **Verifies** — APK Signature Scheme v2, 1 signer (CN=lzn) |
| Cert SHA-256 | `apksigner verify --print-certs` | `38f1d3924fc3d8cf0ef9042983af53489ecc017390a1d8e714b52717cd79f3be` |
| Zip alignment | `zipalign -c 4` | OK (4-byte boundaries) |
| Whitespace/conflict markers | `git diff --check` | clean (exit 0) |
| Feature present in artifact | `resources.arsc` strings | 12 `habit_dialog` entries found |

Build environment: OpenJDK 21.0.12.1, AGP 8.11.2, build-tools 35.0.0.
No unit tests written (per repo instructions); no E2E/emulator/device tests run (per task constraint).
