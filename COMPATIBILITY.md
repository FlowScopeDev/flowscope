# Compatibility and frozen contracts

FlowScope's value depends on a `.flow` file written today still running next year, with no AI model,
no IDE, and no app source code. That only holds if the formats stop moving. This page records what
is frozen as of **1.0.0** and what "compatible" means afterwards.

## What is public

| Surface | Version | Where it is defined |
| --- | --- | --- |
| `.flow` test format | v1 (`flowscope 1`) | `flowscope schema`, [README](README.md), `FlowTestParser` |
| `.suite` suite format | v1 (`flowscope-suite 1`) | `flowscope schema`, `FlowSuiteParser` |
| `flowscope.json` project manifest | `schemaVersion` 1 | `flowscope schema`, `ProjectManifest` |
| Run reports | `schemaVersion` 2 | `report.json`, `suite-report.json` |
| CLI commands and exit codes | 1.0 | `flowscope help`, `flowscope schema` |
| Instrumented event envelope | `schemaVersion` 1 | `FlowScopeAgent`, [README](README.md) |

The **desktop IDE is not public surface**. Its layout, panels and keyboard shortcuts may change in
any release. Nothing it does is required to author or run a test.

`flowscope schema` prints the authoritative grammar for the build you have. When this page and that
command disagree, the command is right and this page is a bug.

## The promise for 1.x

1. **A `.flow` or `.suite` file valid in 1.0.0 stays valid for all of 1.x**, and means the same
   thing. New actions, new selector keys and new step options may be added; existing ones do not
   change behaviour, get stricter, or disappear.
2. **A `flowscope.json` with `schemaVersion: 1` keeps working.** New optional keys may appear.
   Unknown keys are rejected rather than ignored, so a manifest written for a later 1.x may be
   refused by an earlier build — with a message that says so, never silently.
3. **Report fields are added, never repurposed.** A field present in `schemaVersion` 2 keeps its
   name, type and meaning. A consumer that reads `status`, `primaryFailure` and the step array today
   keeps working.
4. **Exit codes do not move.** See the table below.
5. **A breaking change bumps the format version** — `flowscope 2`, `schemaVersion: 3` — and the old
   version keeps being accepted for as long as the major version allows. Redefining a field in place
   is the one thing that is never done, because a checked-in test cannot be re-reviewed.

What is explicitly *not* promised: the wording of human-readable messages, the HTML report's layout,
timing and the dump-count instrumentation in reports, the on-disk shape of `~/.flowscope/`, and the
recording session format, which is an internal capture artifact rather than a durable input.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success. The command did what it was asked, or every test passed. |
| `1` | A run failed — a failed assertion, an unreachable element, or a failed `after` cleanup. |
| `2` | Invalid input — a bad option, a malformed file, a missing path, an unknown command. |

The split matters in CI: `2` means the test or the invocation is wrong and re-running will not help,
while `1` means FlowScope ran correctly and the app under test did not behave. A failed `after`
cleanup exits `1` even when every assertion passed, because it leaves the device or a fixture dirty
for the next run — the report separates it from the test failure so the distinction survives.

No command ever requires interactive input, and validation never touches a device.

## `.flow` v1 — the frozen vocabulary

Phases: `before` (setup), `step` (test), `after` (cleanup, always runs).

**UI**: `launch`, `wait-visible`, `tap`, `tap-if-visible`, `type`, `scroll`, `back`,
`assert-visible`, `assert-not-visible`, `screenshot`.
**Gestures and input**: `long-press`, `swipe`, `scroll-to`, `press-key`, `clear-text`,
`hide-keyboard`.
**Device and app state**: `clear-app-data`, `force-stop`, `grant-permission`, `revoke-permission`,
`reset-app-permissions`, `install-apk`, `uninstall-app`, `assert-installed`.
**Deep links**: `open-deep-link`, `assert-deep-link-handler`, `assert-app-link-verified`.
**WebView**: the `web-*` family.
**Host-side**: `api-request`, `set-date`, `set-value`, `transform`, `secret`.
**Instrumented debug only**: `set-install-referrer`, `clear-install-referrer`.

Selectors combine `id`, `text`, `text-contains`, `desc`, `class`, narrowed by `index` and the state
filters `enabled`, `checked`, `focused`, `clickable`, `selected`. Filters never select on their own.

`direction` always means the direction the finger moves. `press-key` takes a fixed key list, never a
raw keycode. There is no `double-tap`: ADB cannot land two taps inside the double-tap window, so
offering one would mean offering a step that fails unpredictably.

Deliberately absent, and staying absent: raw coordinates in durable tests, fixed sleeps, arbitrary
JavaScript, arbitrary CDP methods, caller-supplied ADB flags, and any scripting or expression
escape hatch. `set-value`, `set-date`, `transform` and `secret` are fixed declarative actions, not a
small language.

## `flowscope.json` — what the parser accepts

Read by a deliberately small strict parser (`Json.java`), not a library, so the CLI stays runnable
from a plain JDK.

**Accepted**: objects, arrays, strings, integers, `true`, `false`, `null`, arbitrary nesting.
**Rejected, with a located message**: trailing commas, comments, single quotes, unquoted keys,
duplicate keys, fractional or exponent numbers, raw control characters in strings, and any content
after the top-level value.

Numbers are integers only. The sole numeric field in any FlowScope JSON is a millisecond timeout,
and accepting a fractional value would mean truncating it silently somewhere later.

Strictness is affordable precisely because the format is versioned: a typo must surface rather than
be dropped on the floor.

## Versioning of the pieces

- **The CLI and the desktop app** share one version, from `flowscope.version` in
  `gradle.properties`. `flowscope version` and the IDE's Settings → About both report it.
- **The Android agent is versioned separately** (`VERSION_NAME` in `android-agent/gradle.properties`,
  released from `agent-v*` tags). `flowscope-agent` and `flowscope-agent-no-op` always publish
  together with identical public APIs, so swapping one for the other across build variants cannot
  fail to compile.
- Agent and host are matched by the event envelope's `schemaVersion`, not by their version numbers.
  A newer agent may emit event kinds an older host ignores.

## If you depend on this

Pin a version. Read `report.json` rather than scraping console output or the HTML report. Treat an
unknown report field as data you do not use yet, not as an error. And run
`flowscope project validate` in CI, which catches a format problem at the point where it is cheap to
fix rather than mid-run on a device.
