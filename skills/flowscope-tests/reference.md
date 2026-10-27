# FlowScope `.flow` reference

Authoritative source: `flowscope schema` on the installed version. If this file and the CLI disagree,
trust the CLI.

## File shape

```text
flowscope 1
name "Human readable name"
package com.example.debug
mode zero-code            # or instrumented
timeout 10000             # default selector timeout in ms

before <action>           # setup, runs in order
step <action>             # the test
after <action>            # cleanup, always runs; failures reported separately
```

Blank lines and `#` comments are ignored. Double quotes keep spaces. A trailing `\` continues a line.

## Selectors

`id=`, `text=`, `text-contains=`, `desc=`, `class=` combine; every given field must match the same
node. `text-contains` targets an inline substring (for example a link inside a sentence).

Narrowing: `index=N` (zero-based nth match) and state filters `enabled=`, `checked=`, `focused=`,
`clickable=`, `selected=` with `true` or `false`. Filters never select on their own. `within-id=<resource id>` requires an ancestor with that ID,
before applying `index`. Use it when a sheet repeats IDs from the underlying screen.

Tap, tap-if-visible, field focus, clear-text and long-press reject tap points covered by later
drawing-order branches or windows, naming the covering ID. This is best effort when accessibility
metadata is missing. `scroll-to` also avoids covered targets. End detection compares scrollable
container children across fresh dumps, ignoring unrelated clocks; without a container, max-swipes
is the only stopping limit. `from-id` helps identify the intended container.

## UI actions

| Action | Arguments |
|---|---|
| `launch` | none |
| `wait-visible` | selector, `timeout` |
| `tap` | selector, `timeout` |
| `tap-if-visible` | selector, `timeout` (no-op if it stays absent) |
| `type` | `value`, optional selector (taps the field first, appends text) |
| `clear-text` | selector |
| `hide-keyboard` | none (Back only while the keyboard is showing) |
| `press-key` | `key=enter|search|delete|tab|home|app-switch|volume-up|volume-down` |
| `back` | none |
| `scroll` | `direction=up|down|left|right` |
| `scroll-to` | selector, `direction` (default up), `max-swipes` 1..50 (default 10), `from-id` (resource id each swipe starts on; default the screen centre), `clear-of-id` (comma-separated ids; keep swiping while the element overlaps any of them, e.g. a pinned panel or floating button), `distance` 10..90 (default 50), `duration` 100..5000 (default 1000) |
| `long-press` | selector, `duration` 500..10000 (default 800) |
| `swipe` | `direction`, optional selector, `distance` 10..90 percent (default 50), `duration` 100..5000 (default 300) |
| `assert-visible` | selector, `timeout` |
| `assert-not-visible` | selector, `timeout` |
| `screenshot` | `name` |
| `screenshot-if-visible` | selector, `name`, optional `timeout` (one check by default) |
| `if-visible` / `end` | `step if-visible <selector> [timeout=ms]` opens a nested block; `step end` closes it in the same phase. One check by default; unmatched contents are skipped. |

`direction` always means where the finger moves. There is no `double-tap` and no fixed `wait`.

## Device, install and deep-link actions

- `clear-app-data`, `force-stop`, `grant-permission permission=...`, `revoke-permission permission=...`,
  `reset-app-permissions` (all scoped to the declared package).
- `open-deep-link uri=... [activity=...] [cold-start=true] [action=...] [category=...]`,
  `assert-deep-link-handler uri=... [action=...] [category=...]`, `assert-app-link-verified host=...`.
  `action=` defaults to `android.intent.action.VIEW`; `category=` defaults to
  `android.intent.category.BROWSABLE` and takes a comma-separated list — `category=` with no value
  sends none.
- `install-apk file=...` (relative to the `.flow`), `uninstall-app`, `assert-installed` with
  `version-code=` or `version-name=`.
- `set-install-referrer value=...`, `clear-install-referrer` (instrumented debug builds only).

Deep-link URIs keep their full query string, including `&`, apostrophes, and `$`, during handler
lookup and launch. Use normal URI encoding for spaces and supply literal values in the `.flow`;
FlowScope handles device-shell quoting. Native `type` retains its documented character limit.

## Host API calls

```text
require env.API_BASE_URL
require secret.API_JWT
before secret name=jwt source=API_JWT
before set-date name=start value=now offset=-7d start-of=day zone=UTC format=yyyy-MM-dd
before set-date name=loanStart value=${var.start} offset=-P6M zone=UTC format=yyyy-MM-dd
before set-value name=requestId generate=uuid
before api-request method=GET url="${env.API_BASE_URL}/health" \
  expect-status=200 expect-json.status=UP timeout=30000 retry-interval=1000
```

`body-file` is relative to the flow or suite; `@fixtures/x.json` reads `flowsDir/fixtures/x.json`.
The same layout works standalone under `flows/tests` or `flows/suites`. Validate and runtime share
no-escape checks (including symlinks and existence); runtime-expression filenames wait for preflight.
The fixtures root itself must stay inside flowsDir. Validate all files before lengthy API setup.

Supports encoded `query.<name>`, headers, `body` / `body-file`, status/header/duration assertions,
exact/existence/length/numeric/prefix/suffix JSON assertions, JSON and response-header captures, and
GET/HEAD polling. Read normal captures as `${var.name}`; secret captures remain tainted.
`set-value` generates UUID/integer/string/email values; `set-date` handles offsets, boundaries,
zones and ISO/epoch/custom formats. `offset=` takes `±Nd/h/m/s` shorthand or an ISO-8601
Period/Duration (e.g. `-P6M`, `PT30M`), and a `set-date`'s formatted output can feed `value=` on the
next one to chain dates, as above. `transform` allows trim, case, URL/base64, substring, prefix and
suffix only. Guards support `if/unless` presence and equality for variables and environment inputs.
Reports never keep runtime values, headers, bodies, or query values. Load local secrets explicitly
with `--secrets-file <path>`; the file must be owner-only on POSIX.
Diagnostic redaction covers environment/file values selected by references, requirements, secret
bindings, or environment equality guards, including one-character secrets. Unrelated environment
or file entries do not rewrite selectors such as `id=item_1`. Runtime values remain protected
after reassignment; suite hooks and child tests share redaction history through cleanup while
test variables remain local.

## WebView actions

`web-wait`, `web-tap`, `web-type`, `web-assert-visible`, `web-assert-text`, `web-assert-url`,
`web-screenshot`, `web-assert-cookie`, `web-clear-cookies`, `web-assert-storage`, `web-clear-storage`,
`web-assert-interface`, `web-assert-secure-context`, `web-snapshot`. They use `css=` for DOM targets and
need a debuggable WebView owned by the declared package. Never write raw JavaScript. Prefer the plain
accessibility actions above when the page exposes them.

## Suites and projects

```text
flowscope-suite 1
name "Nightly regression"
continue-on-failure true

before api-request method=POST url="${env.API_BASE_URL}/fixtures" capture.customerId=data.id
test tests/login.flow
test tests/checkout.flow
after api-request method=DELETE url="${env.API_BASE_URL}/fixtures/${var.customerId}" if-var=customerId
```

Suite hooks are host-only. Tests read suite setup values as `${suite.var.customerId}`; test-local
captures are never shared with other tests or suite cleanup.

`test` paths are relative to the project's flows directory, end in `.flow`, and are never absolute or
contain `..`. A project is a directory with `flowscope.json`, `flows/tests/`, `flows/suites/` and
`reports/` (gitignored). Create one with `flowscope project init <dir> --package <id>`.

`flows/tests/` and `flows/suites/` may hold subdirectories — group tests by feature area, e.g.
`flows/tests/checkout/pay.flow`, referenced from a suite as `test tests/checkout/pay.flow` — up to
8 levels deep. Deeper than that is invisible to `flowscope project tests` and every other listing;
`flowscope project validate` reports any file that deep rather than silently dropping it.

## Commands you will use

```text
flowscope version
flowscope doctor
flowscope devices
flowscope snapshot --package <id> --mode zero-code --output <dir>
flowscope web inspect --package <id> --format json
flowscope project info|tests|suites|validate [<dir>]
flowscope test validate <file.flow> [--project <dir>]
flowscope test run <file|name> [--project <dir>] [--serial <id>] [--output <dir>] [--secrets-file <path>] [--keep-hierarchy]
flowscope suite validate <file.suite>
flowscope suite run [<file.suite>] [--project <dir>] [--serial <id>] [--secrets-file <path>] [--keep-hierarchy]
```

Exit codes: `0` success, `1` a test failed, `2` invalid input. Every run writes `report.json`,
`report.html` and `junit.xml`. A suite run's `suite-report.html` also embeds each test's
`screenshot`/`web-screenshot` evidence as a thumbnail gallery under that test's row, linked to the
full-size file, and `suite-report.json` carries the same list as a `screenshots` array per test.
While a run is in flight its directory also holds a transient `progress.jsonl` (the IDE shows it
live); it is deleted when the report is written, so never parse it — read the report.
`flowscope version` prints the build, and `flowscope doctor` leads
with it — include both in a bug report. What these formats and exit codes promise not to break is
recorded in `COMPATIBILITY.md`.

A failed run names the phase that broke, so fix the right thing. `report.json` splits
`primaryFailure` (a `before` or `step` action) from `teardownFailures` (an `after` action), and the
CLI prints `FAILED: setup failed · …`, `FAILED: cleanup failed · …`, or the test failure on its own.
A cleanup failure also exits `1`: the device or the fixtures are dirty even though the test body
passed, so repair the `after` action rather than the assertions.

Runs check lock/screen state before setup and the focused package before UI work (after an initial
launch/deep-link when present). Unknown Android state fields are best effort. `--keep-hierarchy`
saves `evidence/step-NN.xml` for executed steps, linked by `details.hierarchy`; skipped steps have
none. Failed captures set `details.hierarchyError` without changing the test verdict.
