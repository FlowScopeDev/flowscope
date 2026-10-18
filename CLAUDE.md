# FlowScope Engineering Guide

This is the authoritative repository guide for Claude, Codex, and any other coding agent. Keep
`AGENTS.md` as a pointer to this file instead of duplicating these instructions.

## Product contract

FlowScope records Android UI flows and turns them into durable, declarative tests. Test execution
must never require an AI model, an IDE, or app source code. AI agents may author or update `.flow`
files and may operate the CLI, but the checked-in test plus the FlowScope runner is the complete
runtime input.

FlowScope supports two explicit capture modes:

1. `zero-code` works with any debuggable APK through ADB, UIAutomator, screenshots, window state,
   Logcat, and best-effort accessibility events. It must not require source changes.
2. `instrumented` consumes structured JSON events from an optional debug-only app agent. It adds
   exact gestures, semantic actions, navigation/lifecycle, and extension points for network and
   background-work events. Release builds must contain a no-op implementation or no agent at all.

Do not silently fall back between modes. Record the selected mode in every session and test report.

## Repository layout

- `recorder-host/`: cross-platform Java 21 CLI, ADB integration, recording, `.flow` parser,
  standalone runner, and optional scrcpy process lifecycle.
- `recorder-ide/`: Compose Desktop UI for macOS and Windows; it delegates device work to
  `recorder-host`. Its recorder, test editor/runner, evidence browser, hierarchy viewer, performance
  snapshot, and inspector tabs are real workflows—not mock navigation.
- `android-agent/`: standalone Gradle build (needs the Android SDK, not part of the root build)
  publishing `io.github.flowscopedev:flowscope-agent` (real, self-installing via ContentProvider) and
  `flowscope-agent-no-op` (same API, empty) for client apps. Keep both public APIs identical.
- `examples/`: a reference FlowScope project. It must pass `flowscope project validate examples`.
  `examples/sample-app/` is a standalone Android app (XML views and Compose) that depends only on the
  published agent artifacts; its flows are `examples/flows/tests/sample-*.flow` in `sample.suite`.

## Stable CLI surface

Treat these commands as an API used by humans, CI, and coding agents:

```text
flowscope doctor
flowscope devices
flowscope emulators
flowscope emulator start <avd> [--headless] [--wait] [--timeout <ms>]
flowscope emulator stop <serial>
flowscope mirror [--serial <id>] [--read-only]
flowscope record --package <id> --mode zero-code|instrumented [--serial <id>]
flowscope snapshot --package <id> --mode zero-code|instrumented [--serial <id>]
flowscope web inspect --package <id> [--serial <id>] [--page <match>]
                      [--mode zero-code|instrumented] [--format text|json]
flowscope schema
flowscope project init [<dir>] [--name <name>] [--package <id>]
                       [--mode zero-code|instrumented] [--timeout <ms>] [--serial <id>]
                       [--suite <file.suite>]
flowscope project info|tests|suites|validate [<dir>]
flowscope test validate <file.flow> [--project <dir>]
flowscope test run <file.flow> [--project <dir>] [--serial <id>] [--output <dir>]
flowscope suite validate <file.suite> [--project <dir>]
flowscope suite run [<file.suite>] [--project <dir>] [--serial <id>] [--output <dir>]
```

Keep output concise, deterministic, and scriptable. A successful command exits `0`; invalid input
exits `2`; a failed test exits `1`. Never require interactive input for validation or replay.

Every command parses its options through `CliOptions`. Do not add a fourth hand-rolled option loop:
the three it replaced disagreed with each other, and one silently ignored trailing arguments.

A file argument that names an existing path is always honoured first, with or without a project, so
an invocation that works today keeps working. Only then does a project's resolution ladder apply.

Emulators are launched through the SDK `emulator` binary, never bundled or downloaded. Resolution
order is `EMULATOR`, then `ANDROID_HOME` / `ANDROID_SDK_ROOT`, the usual SDK homes, then `PATH`;
`flowscope doctor` reports the result. `emulator start` launches the AVD detached and returns unless
`--wait` is given (it then prints the serial once `sys.boot_completed` is `1`). Each launch is
recorded in `~/.flowscope/emulators.json` (strict `Json.java` subset, dead pids pruned), and
`emulator stop` refuses, with exit `2`, any serial FlowScope did not launch. The IDE never stops
emulators on exit: they are long-lived.

scrcpy is an optional low-latency interaction surface, not a capture source of truth. FlowScope
speaks scrcpy's control protocol directly for input (taps, drags, keys, text, clipboard), which is
roughly 150x faster than `adb shell input` and is the only way to produce a real continuous drag.
That protocol is version-pinned: `ScrcpyControl.SUPPORTED_MAJOR` records the verified major
version, `ScrcpyControl.supports` gates its use, and anything else falls back to `adb shell input`.
`flowscope doctor` reports which path is active. When bumping the supported version, re-verify the
message ordinals and field layouts against the new server before changing the constant. The IDE uses
the matching `scrcpy-server` raw H.264 stream plus FFmpeg for the embedded Live Device view; ADB input
provides click/drag, mouse-wheel scroll, safe text, and Back/Home/App Switch control. Host clipboard
text is only read after an explicit UI click and is not persisted by the clipboard control itself.
The external scrcpy client remains a fallback. Target one ADB
serial and own only the processes and forwards FlowScope launches. Keep screenshots as the evidence
and stream fallback. Resolve explicit `SCRCPY`, `SCRCPY_SERVER_PATH`, and `FFMPEG` values first, then
`PATH` and common platform install locations; do not bundle or silently download third-party binaries.

## Declarative tests

The v1 `.flow` language is line-oriented and intentionally small. `before`, `step`, and `after`
define setup, test, and always-run cleanup phases. It supports `launch`,
`wait-visible`, `tap`, `tap-if-visible`, `type`, `scroll`, `back`, `assert-visible`, `assert-not-visible`, and
`screenshot`, plus the gestures and input actions `long-press`, `swipe`, `scroll-to`, `press-key`,
`clear-text`, and `hide-keyboard`, host `api-request`, and target-app data/permission actions. Selectors
can combine `id`, `text`, `text-contains`, `desc`, and `class`, and narrow with `index` and the state
filters `enabled`, `checked`, `focused`, `clickable`, `selected` (filters never select on their own).
`direction` always means where the finger moves. `press-key` accepts a fixed key list, never raw
keycodes. `double-tap` is not offered: `adb` cannot land two taps inside the double-tap window.
`text-contains` targets an inline substring when UIAutomator merges clickable text into a larger
single-line accessibility node.

Prefer stable resource IDs or semantic descriptions. Text selectors are acceptable for copy that is
part of the assertion but are weaker across localization. Do not encode raw coordinates in durable
tests. Do not generate fixed sleeps: mutation steps wait briefly for UI stability and condition steps
poll until their timeout.

API requests execute on the host with Java's platform TLS. Secrets come from `${env.NAME}` and
captured scalar JSON fields use `${var.name}` within the current test only. Reports retain sanitized
request metadata but never environment values, headers, bodies, or captured values. Device lifecycle
actions are always restricted to the test's declared package; never use a global permission reset.

Deep-link intents must be explicitly scoped to the declared package. Reports may retain scheme,
host, and path but never query values. APK install/uninstall actions are also package-scoped: files
are relative to the `.flow` file, resolved before mutation, and inspected with SDK `apkanalyzer` so
their application ID must equal the test package. Do not accept caller-supplied ADB install flags.

WebView tests are accessibility-first. Restricted `web-*` actions may use Chrome DevTools only for
a debugging socket owned by the declared package PID. Tests must never provide raw JavaScript or
arbitrary CDP methods. Cookie/storage values may be compared in memory but must never reach the IDE,
CLI output, errors, reports, timeline, evidence, or notifications. Persist only names, scope, flags,
sizes, counts, sanitized origins, and bridge method names. Zero-code bridge inventory is heuristic;
the debug agent wrapper is authoritative and its release twin must delegate with no recording. Do
not persist headers, bodies, full DOM dumps, query values, or page titles. Own and remove only the ADB
forward FlowScope creates. Keep CDP JSON parsing separate from the strict `flowscope.json` parser
because protocol payloads contain fractional/exponent numbers.

Install-referrer simulation is an explicit instrumented debug hook, not Play Store impersonation.
`set-install-referrer` writes only inside the declared debuggable package's sandbox, reports presence
but never the value, and is consumed only when app code opts into
`FlowScopeAgent.installReferrerOverride(context)`. The release no-op twin must always return null.

### Suites and projects

A `.suite` file is an ordered list of `.flow` tests (`flowscope-suite 1`, `name`,
`continue-on-failure`, `test <path>`), sharing the `.flow` tokenizer and reporting errors as
`FlowTestParser.ParseError`. `test` paths are relative to the project's flows directory, must end in
`.flow`, and must not be absolute or contain `..` — a suite is shared, checked-in input, and a
reference that only resolves on the author's machine is a broken suite.

A project is a directory marked by `flowscope.json`, holding `flows/tests/*.flow`,
`flows/suites/*.suite`, and `reports/`. **`flowscope.json` supplies authoring and run defaults only.**
Every `.flow` keeps its own full `package`/`mode`/`timeout` header, and
`flowscope test run login.flow` must keep working with no project present. Never make a project a
runtime dependency of a test: that would break the product contract above.

`flowscope.json` is read by the small strict parser in `Json.java`, not a library — the host CLI
stays runnable from a plain JDK. Keep its accepted subset documented and its rejections tested.

Any change to `.flow`, `.suite`, or `flowscope.json` requires parser validation, standalone runner
behavior, `flowscope schema` output, a README example, and parser or runner tests in the same change.

## Instrumented event protocol

The Android agent writes one compact JSON object per Logcat entry using tag `FlowScopeAgent` at
`INFO`. Required envelope fields are `schemaVersion`, `elapsedRealtimeNanos`, `wallTime`, `source`,
`kind`, and `payload`. Never emit headers, cookies, authorization values, tokens, screen text, phone
numbers, or financial/KYC data. Network events should use redacted URL templates and metadata, not
bodies. Background events should use stable logical work names, not arbitrary arguments.

Instrumented test runs also stream these events (matched by the package's UID, so they survive
process restarts) into the report's `agentEvents` section, keeping only action/component names, route
templates, status codes, and work names.
The host filters Logcat framing lines so `events.jsonl` contains JSON objects only. Keep individual
events comfortably below Logcat's entry limit.

Client apps depend on `debugImplementation(io.github.flowscopedev:flowscope-agent)` and
`releaseImplementation(io.github.flowscopedev:flowscope-agent-no-op)`; never ask them to copy agent source files. The
agent emits `agent.hello` with its version at startup. Java/Kotlin packages are `dev.flowscope.*`; the Maven group is
`io.github.flowscopedev`. Releases publish from the `agent-v*` tag workflow; never commit signing keys
or Central credentials.

## Interface accessibility

Every icon-only IDE control must have both a visible hover hint through `HoverTip` and a concise
accessibility `contentDescription` describing its action. The hint must remain available when the
control is disabled so the user can still understand it. Prefer the established 32×32 toolbar hit
target and use action labels such as “Move up”, “Duplicate”, “Capture checkpoint”, or “Settings”;
do not rely on an icon's shape or colour to communicate purpose.

Runs started from the IDE post one best-effort operating-system notification after reports have been
written. Distinguish a single test from a regression suite, include aggregate suite counts, and keep
failure details inside FlowScope rather than exposing them in notification text. Notification delivery
must never alter a run result. CLI and CI execution must not produce desktop notifications.

## Timing and reliability

- UI polling interval: 200 ms.
- `uiautomator dump` starts a process each time (about 2 s on a real device), so dumps dominate a
  run. `settle()` remembers its final hierarchy and the next lookup may reuse it for 3 s; only the
  first poll of a lookup may do so. A single dump that outlasts the 1.5 s stability window counts as
  settled. Reports record per-step dump counts and times; keep that instrumentation when changing waits.
- Every adb call is bounded (`AdbClient.run`; 30 s for `uiautomator dump`, 5 min otherwise) and a hung one
  is killed. A run and a recording each snapshot the device's `uiautomator` pids and, on exit or JVM
  shutdown, kill only the pids that appeared after the snapshot. Never a blanket `pkill`: Appium or
  `am instrument` may be using uiautomator on the same device.
- Default selector timeout: 10 seconds, overridable by the test or step.
- After a mutation, compare consecutive hierarchies for up to 1.5 seconds; do not turn observed
  recording delays into replay sleeps.
- Capture a screenshot and hierarchy when a replay step fails.
- Use monotonic time for durations and UTC wall time for correlation.

## Security and release boundaries

- The instrumented agent is debug-only. Production/release variants must be no-op or absent.
- Zero-code sessions can contain visible screen content; keep sessions ignored by Git.
- Never persist secrets from Logcat or agent payloads.
- Do not weaken certificate pinning, authentication, tapjacking protection, or app security to make
  capture easier.

The IDE never writes data relative to the application bundle or the process working directory. It
writes to exactly two roots: a project directory the user explicitly chose, and `~/.flowscope/`.

`~/.flowscope/` keeps raw recording sessions (`sessions/`), host preferences, the recent-projects
list, and exports; it is also the complete fallback when no project is open (`tests/`, `test-runs/`).
Recording sessions never go into a project: they are bulky, they contain visible screen content, and
they must stay out of Git.

A project directory holds only authored inputs and run output: `flowscope.json`,
`flows/tests/*.flow`, `flows/suites/*.suite`, and `reports/`. `flowscope project init` gitignores
`reports/` because failure evidence and `step screenshot` output contain screen content, and it only
ever appends to an existing `.gitignore`.

## Build and verification

```bash
./gradlew :recorder-host:build :recorder-ide:build
./gradlew :recorder-host:installDist
./recorder-host/build/install/flowscope/bin/flowscope schema
./recorder-host/build/install/flowscope/bin/flowscope project validate examples
./recorder-host/build/install/flowscope/bin/flowscope test validate \
  examples/flows/tests/example-launch.flow
```

Before finishing a behavior change, compile both modules. For recorder changes, make a short session
against a connected device when available and validate gzip/JSONL output. For runner changes, validate
the examples and execute an appropriate smoke test.

macOS desktop releases are tagged `v<version>` matching `packageVersion` in
`recorder-ide/build.gradle.kts`, which triggers `.github/workflows/release-macos-dmg.yml` to build
`packageReleaseDmg`, checksum it, and attach both to a GitHub Release. The Homebrew cask that
downloads from that release lives in the separate `FlowScopeDev/homebrew-tap` repo, not here.

## Documentation maintenance

Keep `README.md` and `design.md` aligned with behavior in the same change. Update this file when
architecture, stable CLI commands, formats, security rules, or verification commands change. Do not
add a second agent guide; update this one.
