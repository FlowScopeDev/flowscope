# Changelog

All notable changes to FlowScope are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project aims to follow
[Semantic Versioning](https://semver.org/). The CLI, the `.flow`/`.suite`/`flowscope.json` formats and
the instrumented event protocol are the public surface; the desktop IDE is not.

The Android agent is versioned separately (`VERSION_NAME` in `android-agent/gradle.properties`) and
released from `agent-v*` tags. This file tracks the desktop app, CLI and file formats.

## [Unreleased]

Nothing yet.

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
