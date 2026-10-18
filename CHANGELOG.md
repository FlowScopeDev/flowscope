# Changelog

All notable changes to FlowScope are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project aims to follow
[Semantic Versioning](https://semver.org/). The CLI, the `.flow`/`.suite`/`flowscope.json` formats and
the instrumented event protocol are the public surface; the desktop IDE is not.

The Android agent is versioned separately (`VERSION_NAME` in `android-agent/gradle.properties`) and
released from `agent-v*` tags. This file tracks the desktop app, CLI and file formats.

## [Unreleased]

### Added

- Flow language: `long-press`, `swipe`, `scroll-to`, `press-key`, `clear-text` and `hide-keyboard`,
  plus the selector modifiers `index` and the state filters `enabled`, `checked`, `focused`,
  `clickable` and `selected`.
- IDE: a setting, a dock button and a command-palette entry to hide the device display. Hiding stops
  the scrcpy stream and screenshot polling; recording, the Tree and test runs keep working over ADB.
- Tag-triggered macOS DMG release workflow and Homebrew install documentation.
- Emulators can be listed, started and stopped from the CLI and the IDE.
- A sample Android app and flows that exercise FlowScope through the published agent.

### Changed

- Every adb call is now time-bounded, and a hung one is killed.
- Test runs and recordings clean up the `uiautomator` processes they started, including on JVM
  shutdown. Processes owned by other tools (Appium, `am instrument`) are never touched.

## [1.0.0] - not yet released

First public release: record Android UI flows from a desktop IDE or the CLI and replay them as
declarative `.flow` tests with the standalone runner, in zero-code or instrumented capture mode.

[Unreleased]: https://github.com/FlowScopeDev/flowscope/commits/main
