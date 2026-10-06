# Verification — Fix "enable Habit from Agenda" aborting with 习惯属性修改后解析结果与预期不一致

Date: 2026-10-03 · Branch: `master` (dirty user worktree; no commits made) ·
Executor: Claude Code

## Confirmed failing check

`OrgHeadingStyleService.enableHabit` maps four pre-write checks onto
`STYLE_MISMATCH`. Reproduced on a JVM harness (`harness.Main`) that runs the
**real, unmodified production classes** (`OrgAgendaParser`, `OrgParserWrapper`
incl. orgzly `org-java:1.2.2`, `OrgHeadingStyleService`) over in-memory sample
inputs matching the screenshot — heading
`** NEXT english chunk : https://www.youtube.com/watch?v=dQw4w9WgXcQ`,
schedule `<2026-10-03 Sat +1d/3m>` (i.e. `repeaterType=+`, count=1, unit=d,
deadline 3m → repeaterDays=1, deadlineDays=91).

Observed branch (all four scenarios — bare heading, existing `:ID:` drawer,
existing SCHEDULED line, VIBING sibling):

- **Check 1 (viewer parser / STYLE) — FAILED: `viewerNode.isHabitStyle == false`**
  while `sourceOffset=11`, `level=2`, `title` all matched (identity fine).
- Check 2 (agenda identity/title) — passed.
- Check 3 (`habit != null`) — passed (`srType=+`).
- Check 4 (exact fields) — passed (`scheduled=2026-10-03`, `srDays=1`,
  `drDays=91` — all equal to the schedule).

So the transformed subtree was correct and both app parsers read it back as
exactly the requested habit; only the viewer-side flag was false, the write
aborted before persistence, and the generic message surfaced.

## Root cause

orgzly's parser consumes the heading's own `:PROPERTIES:` drawer and the
SCHEDULED planning line **out of `OrgHead.content`** — observed for the edited
subtree:

- `head.content` = `plain body line` (no drawer, no SCHEDULED line)
- `head.properties` = `STYLE=habit`
- `head.scheduled` = `<2026-10-03 Sat +1d/3m>`

`OrgParserWrapper.mapOrgNodeInListToOrgNode` computed
`isHabitStyle = content.hasProperty("STYLE", "habit")` by regexing
`head.content` — which never contains the drawer for well-formed org. The flag
was therefore always false, check 1 could never pass, and **no habit enable
could ever write** (`hasRepeatingScheduled` had the identical latent bug over
the consumed SCHEDULED line; `disableHabitStyle`'s `!isHabitStyle` check was
vacuously true). A second mismatch of the same check: the agenda parser's
keyword set grew to include `VIBING`/`SANDBAGGING` but the viewer's
`setTodoKeywords` list did not, so for those headings orgzly's title includes
the keyword while the agenda's does not → check 1 title comparison fails.

## Fix (validation untouched — all four checks still gate the write)

1. `OrgParserWrapper.kt` — derive the two viewer flags from orgzly's parsed
   fields first, keeping the content regexes as fallback for anything orgzly
   leaves unconsumed:
   `isHabitStyle = head.properties["STYLE"] == habit || content-hasProperty`,
   `hasRepeatingScheduled = head.scheduled.startTime.hasRepeater() ||
   content-regex`.
2. `OrgAgendaParser.kt` — expose `NOT_DONE_KEYWORDS` next to the existing
   `DONE_KEYWORDS`; `OrgParserWrapper` now seeds orgzly's todo/done keywords
   from that single source of truth instead of a diverged inline array (fixes
   VIBING/SANDBAGGING headings in check 1).
3. `OrgHeadingStyleService.kt` — the four checks keep aborting before any
   write, now with distinct concise Chinese reasons:
   `VIEWER_PARSE_MISMATCH` (viewer identity/title/STYLE),
   `AGENDA_IDENTITY_MISMATCH`, `HABIT_NOT_PARSED` (repeater invalid),
   `SCHEDULE_FIELD_MISMATCH` (date/srDays/drDays drift).
   `STYLE_MISMATCH` (generic) remains for `disableHabitStyle`.

## Evidence

Harness over the real classes, logs under the state directory
(`logs/harness-run1.log` pre-fix, `logs/harness-run2.log` head dump,
`logs/harness-verify.log` post-fix):

| Case | Result |
| --- | --- |
| Screenshot `+1d/3m` on `NEXT english chunk : https://…` (pre-fix) | ABORTED — check 1 `isHabitStyle=false` |
| Same inputs (post-fix) | **written**: `SCHEDULED: <2026-10-03 Sat +1d/3m>` + `:STYLE: habit`, both parsers re-read it as the exact habit |
| `++3w` (no deadline), `.+2d/1w`, `+1y/2y` | written; fields round-trip exactly |
| VIBING-keyword target (pre-fix failed check 1 via title) | written post-fix |
| enable → disable round-trip | disable removes only `:STYLE:`, repeating SCHEDULED preserved (`scheduled=2026-10-03`, `hasRepeatingScheduled=true`) |
| Guard: `/3d` window not longer than `+1w` repeat | still ABORTED (无效截止窗口 message), nothing written |
| Guard: heading shifted (new heading inserted above) | still ABORTED (HEADING_MOVED message), nothing written |

Build & artifact checks (raw logs in state dir `logs/`):

| Check | Command | Result |
| --- | --- | --- |
| Release build | `./gradlew :app:assembleRelease` (zulu-21 via mise) | **BUILD SUCCESSFUL** exit 0, 1m 32s |
| Patch hygiene | `git diff --check` | exit 0, no output |
| Signature | `apksigner verify --verbose --print-certs` | **Verifies** · v2 true · 1 signer (RSA 2048, CN=lzn) |
| Alignment | `zipalign -c 4` | exit 0, no output |

## Artifact

- Copied to: `/tmp/claude-supervisor.org-android-habit-parse.dRY6mA/app-release.apk`
- SHA-256: `9130e46d3d4064a12c1bdb89e3e18894e8dfd5ff1aeda9bab04cd98a8a943e18`
- Size: 6,271,734 bytes

## Constraint compliance

- Source files edited: `OrgParserWrapper.kt`, `OrgAgendaParser.kt`,
  `OrgHeadingStyleService.kt` (all pre-existing dirty/untracked feature
  files); every other uncommitted/untracked change untouched (82 status
  entries before and after); no reset/clean/checkout/commit/push.
- No unit tests written (harness is a throwaway JVM diagnostic under the state
  directory, not a repo test); no E2E/emulator/device runs; APK not installed.
- User's live vault never read; samples are synthetic in-memory strings.
- Signing secrets untouched; only public certificate fingerprints recorded.
- Pre-write safety behavior preserved and re-proven (both guards above).
