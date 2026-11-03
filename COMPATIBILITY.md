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
| Saved execution plans | `version` 1 | `flowscope schema`, `ExecutionPlan`, README |
| Execution summaries | `schemaVersion` 1, `kind: execution` | `execution-report.json` |
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

What is explicitly *not* promised: the wording of human-readable messages, the layout of the HTML
and PDF reports (`report.html`, `report.pdf`, `suite-report.html`, `suite-report.pdf`) — they are
presentation, and `report.json` is the artifact to read from a script —
timing and the dump-count instrumentation in reports, the on-disk shape of `~/.flowscope/`, the
recording session format, which is an internal capture artifact rather than a durable input, and
`progress.jsonl` and `activity.json`, the transient files a run keeps while in flight so the IDE can
show it live — the second holding only what the running step is doing right now (elapsed, budget, a
stage word, a counter). They are
deleted once the report is written; read the report instead.

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

### Replay reliability additions

`within-id` scopes selectors by ancestry; `if-visible` / `end` blocks and `screenshot-if-visible`
are additive v1 actions. Blocks nest within one lifecycle phase. `body-file=@fixtures/...` resolves
under flowsDir/fixtures, including standalone test/suite trees. Disk validation now checks literal
file references with the same existence and symlink containment rules used at runtime; expression
filenames still require runtime preflight. Invalid input keeps exit code 2.

Tap actions now fail on proven drawing-order/window coverage. Device readiness failures are run
failures (exit 1), with normal cleanup/reporting. Missing Android metadata remains best effort.
`scroll-to`'s default duration is now 1000 ms; explicit durations retain their meaning. Early end
checks use scroll-container children and fresh confirmation; no container means max-swipes only.
`--keep-hierarchy` is additive on test/suite run: optional `details.hierarchy` and
`details.hierarchyError` preserve report schema version 2. Skipped steps have no XML.

The IDE's complete step list uses internal `run-plan.json` metadata plus `progress.jsonl` hook and
step events. The plan contains no step arguments or runtime values and remains after completion.
These observer files are not public interchange formats; final JSON/HTML/JUnit report schemas are
unchanged. Older completed reports still show their recorded steps; older active runs cannot name
unobserved future steps without a saved plan.

## Cancellation and project secrets

Run-report schema 2 adds `cancelled` as a status for an explicitly stopped run and its interrupted
step or suite child. Future steps/tests remain `skipped`; cleanup outcomes and failures retain their
existing fields. Suite totals include cancelled children among skipped unless cleanup failed. JUnit
uses skipped cases for cancellation and retains genuine failures, including cleanup failures. Existing
passed/failed runs and CLI exit codes are unchanged; cancellation is not success.

Project runs now auto-load `<flowsDir>/.secrets`; explicit `--secrets-file` overrides it. Existing
dotenv syntax and environment precedence are unchanged. A missing default means no file; an invalid
or insecure default fails rather than silently ignoring it. No-project CLI runs still require an
explicit file. IDE overrides are project-specific, with its legacy global choice used only without
a project. The editable JSON view is an IDE interface, not a new secret-file format.

## Optional display labels

The v1 flow/suite grammars accept optional `label="..."` on actions (including setup/cleanup and
conditional boundaries) and suite test entries. Existing unlabeled files retain their behavior.
`name=` remains an action argument; it is never repurposed as display metadata. Labels are literal
public text, not runtime expressions. New labeled suites require a host that supports this addition.

Report schema 2 adds an optional `label` to step results, slow-step metrics, suite hooks and suite
entries. Existing `action`, `name`, `reference` and artifact filenames retain their meanings. Readers
fall back to action/test names when the field is absent. Internal progress and plans carry the same
literal label; neither receives runtime argument values. HTML/CLI prefer labels for human-readable
step descriptions, and JUnit failure details and suite case names use labels when supplied.

Transient malformed hierarchy dumps now retry within their observation budget. Per-step `uiDumps`
counts successfully parsed dumps; additive `uiDumpErrors` and `uiDumpLastError` distinguish discarded
captures and their last error. Schema version 2 is unchanged. Optional detail keys
`discardedHierarchy`, `swipeHierarchy<N>` (and `-confirm` variants), `coveringNode`, `targetBounds`,
and `targetRejection` provide redacted evidence links and coverage diagnostics; evidence failures use
corresponding `Error` details. No `.flow` syntax or exit-code changes. `scroll-to` now requires
full-area clearance and stops when a rejected target leaves the viewport.

`scroll-to` accepts a target using the same match as `assert-visible` plus full containment in the
screen and in the target's own scroll container, so an already visible element completes with zero
swipes; an unrelated scrollable pane no longer bounds the target. A target that matched but was not
accepted fails with its last reason rather than `Element not found`. Failure message text is
diagnostic, not a compatibility surface; exit codes and report schema 2 are unchanged. Pure layouts cover only through
children unless they have id/text/description/clickable/focusable semantics.

## Execution plans and summaries

The additive `plan validate`, `plan show`, and `plan run` commands preserve existing single-device
commands and .flow/.suite/flowscope.json contracts. The v1 plan keys are version, name, projects,
queues, and optional maxParallel. A queue has project, device, jobs, optional continueOnFailure.
All roles need explicit unique serial bindings. Invalid plans, bindings and live device preflight
return 2; jobs that execute and fail return 1. Device infrastructure failures can also fail a run.

Execution summaries have schemaVersion 1 and kind execution. Fields: id, name, startedAt, endedAt,
status, totalJobs, passed, failed, skipped, and jobs. Each job records project, projectRoot, deviceRole,
deviceSerial, reference, kind (test/suite), status, failureKind (nullable), report (nullable local
absolute child JSON path), and modes (distinct capture-mode names). Job statuses are passed, failed,
or skipped. Failure categories are test-or-cleanup, infrastructure or device-disconnected; details
remain in child reports. Counts and aggregate JUnit entries count complete assigned jobs; suite child
counts remain in suite reports. Child report schemaVersion 2 is unchanged. HTML/PDF are presentation.

Desktop assignment execution adds the optional aggregate `cancelled` count and the job status
`cancelled` to execution-report.json schemaVersion 1. Cancellation without real failures maps to
skipped in aggregate JUnit; failed steps, suite hooks or cleanup retain failure entries. Child
report schema 2 is unchanged. Desktop state restoration and recording directory names are internal;
recording roots now include UUID suffixes to prevent simultaneous-device collisions.
