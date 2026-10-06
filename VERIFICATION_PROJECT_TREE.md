# Verification — Agenda Projects parent/child tree

Date: 2026-10-03 · Branch: `master` (worktree, no commits made)

## Behavior

Agenda → Projects → **GTD Projects** now renders project headings as a visible
parent/child tree instead of a flat list:

- `OrgAgendaBuilder` collects **root projects only** (`collectProjectRoots`):
  every `PROJ` heading with no `PROJ` ancestor becomes a root. A `PROJ` nested
  under another `PROJ` stays inside its parent's branch and is **not** repeated
  as its own root tree; a `PROJ` under a non-PROJ heading (e.g. an `AREA`) is
  still an independent root.
- Each root renders via the recursive `AgendaTreeBranch` composable: the root
  row followed by **every descendant heading**, nested one level deeper per
  generation, indented 14 dp per level with a thin vertical guide rail. No
  flattening — children never appear as peer rows.
- Every descendant row is its own interactive entity closing over its own
  `OrgAgendaEntry` (exact `uri`/`titleOffset`/`sourceOffset` from the parser —
  identity is never inferred from titles):
  - tapping the row opens the source file at that exact heading;
  - the TODO keyword picker targets that child only;
  - the HABIT toggle targets that child only.
  This holds for any TODO state (`NEXT`, `WAIT`, `TODO`, …, done states) and
  for headings with **no** TODO keyword. Child chips consume their own taps, so
  a child action never triggers the parent row's action.
- Unchanged elsewhere: **Stuck Projects** still computed over all PROJ entries
  (flattened) with the same no-`NEXT`/`WAIT`-descendant semantics and renders
  as flat rows; Daily / Weekly / Areas sections, section collapsibility
  (`rememberSaveable` keys), and root breadcrumbs are untouched. Root-project
  rows keep their ancestor breadcrumb; descendant rows drop it (the tree shows
  the parent directly above). No branch collapse added, so no action can be
  hidden.

## Touched files (this task)

- `app/src/main/java/com/orgutil/domain/agenda/OrgAgendaBuilder.kt`
  — `collectProjectRoots` walk; `projects = projectRoots`; stuck semantics kept
  on the flattened `allProjects` list.
- `app/src/main/java/com/orgutil/ui/screens/AgendaScreen.kt`
  — `AgendaSection.hierarchical` flag (GTD Projects only), `AgendaTreeBranch`
  recursive composable, `AgendaEntryRow` `treeDepth` indent rails +
  `showBreadcrumb`, divider only between root branches.

(Both files carry pre-existing uncommitted user changes; they were preserved —
no reset/checkout/commit.)

## Checks

| Check | Result |
| --- | --- |
| `./gradlew :app:assembleRelease` | BUILD SUCCESSFUL in 1m 31s (log: `build-assembleRelease.log`) |
| `git diff --check` | clean (exit 0) |
| `apksigner verify --verbose --print-certs` | Verifies · v2 scheme: true · 1 signer (RSA 2048) · cert SHA-256 `38f1d392…f79f3be` (log: `apksigner-verify.log`) |
| `zipalign -c 4` | clean (exit 0, log: `zipalign-check.log`) |

## Artifact

- Path: `/tmp/claude-supervisor.org-android-project-tree.CcFcH8/app-release.apk`
- Size: 6,271,734 bytes
- SHA-256: `842fb7ced4b5b015addd48b8dc9a2db212b587a54a558aea5b9756dc9762f2e2`

Not installed on any device. No unit tests written, no E2E/emulator/device
runs (per task constraints).
