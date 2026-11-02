# Changelog

All notable changes to FlowScope are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project aims to follow
[Semantic Versioning](https://semver.org/). The CLI, the `.flow`/`.suite`/`flowscope.json` formats and
the instrumented event protocol are the public surface; the desktop IDE is not.

The Android agent is versioned separately (`VERSION_NAME` in `android-agent/gradle.properties`) and
released from `agent-v*` tags. This file tracks the desktop app, CLI and file formats.

## [Unreleased]

### Added

- **`continue-on-failure=true` on a step** fails the test when that step fails but keeps running
  the steps after it, so one wrong label does not hide every check behind it. It is accepted on
  `step` instructions only, not on `before`, `after`, `if-visible` or `end`.
- **Every run now writes `report.pdf`, and a suite writes `suite-report.pdf`** — the artifact that
  gets attached to a ticket. The screenshots are inside the file, the steps are set as block rows
  that paginate instead of a table squeezed off the page, and each capture gets a plate of its own.
  It is written on a plain JDK with no new dependency and uses only the base-14 fonts, so nothing is
  embedded. Writing it is best effort: if it cannot be typeset the run warns and keeps its verdict,
  because a verdict is what the device did.
- **`report.html` and `suite-report.html` now carry their screenshots inside the page**, as `data:`
  URIs, so a report can be sent to someone on its own and still show the evidence — a relative
  `<img>` went blank the moment the page left its folder. Thumbnails are always embedded; full-size
  views are embedded until a page-size budget is spent, after which the rest fall back to the
  evidence folder and the **Screenshots** section says so. The per-step `evidence` and `hierarchy`
  links still point into the run folder.
- `report.html` and `suite-report.html` are redesigned against the rules in
  [hallmark](https://github.com/Nutlope/hallmark): a masthead carrying the verdict, a rail of counts
  and run facts, and the full width of the viewport for the step table and the gallery. Gone are the
  kicker above the heading, the tracked capitals, the decorative status dots, the middle-dot
  separators, the nested cards and the sub-16px body type; added are one tokenised palette on a 4px
  scale, `:focus-visible` on every control, a reduced-motion fallback, and a stamp at the top of the
  stylesheet recording the decisions and waivers. Both still run no script and fetch nothing. Each
  opens with a labelled **Screenshots** section collecting every capture the run made — the step that
  took it, the test it belongs to in a suite, and the screenshot saved when a step fails. A
  thumbnail opens a full-size view with previous/next controls and a position counter, so a run can
  be stepped through one capture at a time; the thumbnails beside a suite's test row open the same
  view. The HTML layout is presentation only; `report.json`, `suite-report.json` and `junit.xml` are
  untouched.

- Optional literal `label="..."` display names for flow steps, suite hooks and suite test entries,
  shown in live progress, inspection tabs and reports; unlabeled tests and existing `name=` arguments
  keep their behavior.

- IDE Stop cancels tests and suites, skips remaining work, runs cleanup, and reports Cancelled.
- Settings supports masked project secrets with an editable JSON view; IDE/CLI project runs
  automatically load `<flowsDir>/.secrets`, with explicit file overrides.

- Runs started from the CLI appear live in the IDE's **Runs** rail. A run in flight keeps a
  `progress.jsonl` in its report directory (step and test positions and literal action names only),
  which is deleted once the report is written; a run whose process died reads as interrupted.
- `scroll-to from-id=<resource id>` starts every swipe at that element instead of the screen centre,
  so a pinned panel over the lower half of the screen cannot swallow the swipe.
- `scroll-to clear-of-id=<id>[,<id>...]` keeps swiping while the element overlaps any of those, so a
  tap after it is not swallowed by a panel or floating button drawn over the content; `scroll-to` also takes `distance=`
  and `duration=` like `swipe`.

### Changed

- **The reports are light only; the **Dark**/**Light** toggle is gone.** A report is a document that
  is read, printed and pasted into tickets, and with no script there was nothing to remember a
  choice with between reports.
- A run's one-line verdict reads as a sentence rather than a middle-dot list: `setup failed: …`,
  `cleanup failed: …`, and `… . Cleanup also failed (1): …`. This is the string the CLI, the
  `junit.xml` message attribute and a suite's test row all share; `report.json` is unchanged.
- A cleanup banner's list of failures now renders inside the banner. A `<p>` cannot contain a `<ul>`,
  so the parser had been closing the banner early and the failures appeared outside its own band.

### Fixed

- macOS debug/release packaging publishes the verified, signed app on fresh files at its normal
  output path, preventing stale signature state from producing a “damaged” launch error. Release
  DMGs use the same completed bundle; local builds no longer need a manual clean-copy workaround.

- Timeline, Evidence and Tree follow selected tests/suites, including live steps and saved artifacts;
  Perf separates run timing from device snapshots and reports unavailable/partial collection.
- Recording Logcat and agent streams survive app restarts through exact package UID matching,
  surface capture failures, and clean up streams when startup fails.

- The XML and Compose sample list tests scroll to item 45 and back to item 1 by selector,
  avoiding flaky assumptions that equal swipe counts return to the same position.

- A `uiautomator dump` that exits 0 without writing its file (`null root node`, `could not get idle
  state`, common just after `launch`) is retried instead of failing the step with an XML parse error.

- Cover detection ignores non-clickable containers and judges them by their children. The system
  decor is a full-screen layout holding only the status and navigation bar strips, and it made every
  target count as covered, so `scroll-to` swiped past elements it could see and taps were refused.

- Selectors skip nodes with no area. Compose reports a node scrolled out of its viewport as
  `[0,0][0,0]`, which made `assert-visible` and `scroll-to` treat an off-screen element as visible.

- `scroll-to` rejected elements `assert-visible` accepts, failing with `Element not found after N
  swipes` while swiping away from a target that was on screen the whole time. It judged visibility by
  intersecting every ancestor's bounds, so one zero-area wrapper hid the element, and it scoped the
  target to the largest scrollable on screen, which can belong to another pane. It now uses the same
  match as `assert-visible` and accepts the target — from the check before the first swipe — once it
  lies fully inside the screen and fully inside its own scrollable ancestor and is uncovered.

- A `scroll-to` target that matched but was not accepted now fails with the reason (`partly outside
  the container <bounds>`, `outside the screen`, or `covered by <node>`) and the swipes it was seen
  on, instead of claiming the element does not exist, and stops as soon as a rejected target leaves
  the viewport rather than using up `max-swipes`. Reports add the `targetRejection` detail.

## [1.0.0] - 2026-10-19

First public release: record Android UI flows from a desktop IDE or the CLI and replay them as
declarative `.flow` tests with the standalone runner, in zero-code or instrumented capture mode.
What 1.x promises not to break is written down in [COMPATIBILITY.md](COMPATIBILITY.md).

### Added

- A `flowscope-tests` agent skill (`skills/`) that teaches Claude Code, Codex and other coding agents to write, validate and run `.flow` tests.
- Flow language: `long-press`, `swipe`, `scroll-to`, `press-key`, `clear-text` and `hide-keyboard`,
  plus the selector modifiers `index` and the state filters `enabled`, `checked`, `focused`,
  `clickable` and `selected`.
- IDE: a setting, a dock button and a command-palette entry to hide the device display. Hiding stops
  the scrcpy stream and screenshot polling; recording, the Tree and test runs keep working over ADB.
- Tag-triggered macOS DMG release workflow and Homebrew install documentation.
- Emulators can be listed, started and stopped from the CLI and the IDE.
- A sample Android app and flows that exercise FlowScope through the published agent.
- `flowscope version` (also `--version`/`-v`), and `flowscope doctor` now leads with the version.
- IDE: a Settings → About section showing the version, with a manual Check for updates action.
  FlowScope contacts GitHub only when asked, sends nothing but its own version, and caches nothing.
- The CLI is published as `flowscope-cli-<version>.zip` on each release, with a SHA-256 checksum
  beside it, alongside the macOS DMG and its checksum. Release notes are generated from a template
  so the install guidance ships with every release.
- `INSTALL.md` — installing both pieces, why macOS reports the app as damaged, and how to verify a
  download. `COMPATIBILITY.md` — the frozen `.flow`/`.suite`/`flowscope.json` formats, exit codes
  and report fields, and what 1.x promises.
- `release/homebrew/` — the Homebrew cask template and the steps to publish it to the tap.
  Installing through the cask still needs `brew trust` and a `xattr -cr`: Homebrew applies the
  quarantine attribute deliberately and no longer offers a way to skip it.
- IDE: with the device display hidden, an AVD's primary start is headless. Both starts remain.

### Changed

- Every adb call is now time-bounded, and a hung one is killed.
- Test runs and recordings clean up the `uiautomator` processes they started, including on JVM
  shutdown. Processes owned by other tools (Appium, `am instrument`) are never touched.
- One version for the CLI and the desktop app, from `flowscope.version` in `gradle.properties`.
  The CLI previously reported `0.1.0-SNAPSHOT` while the app packaged `1.0.0`.
- ProGuard is disabled for the release build, so `packageReleaseDmg` produces a DMG again. The
  Compose plugin's `proguardReleaseJars` reads `ExecSpec.standardOutput` before setting it, which
  returns null on Gradle 9 — an upstream bug still present in plugin 1.12.2 and 1.13.0-alpha02, and
  unavoidable by configuration. Working, it shrank the app jars by 7.3 MiB, about 5% of a bundle
  that carries its own JDK. Re-test it with `-Pflowscope.proguard=true` (needs Gradle 8 for now).

[Unreleased]: https://github.com/FlowScopeDev/flowscope/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/FlowScopeDev/flowscope/releases/tag/v1.0.0
