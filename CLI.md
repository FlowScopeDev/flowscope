# CLI commands and the agent skill

Every `flowscope` command on one page, and how to let a coding agent (Claude Code, Codex or any agent
that reads files) write and run FlowScope tests for your app. Install the CLI first: see
[INSTALL.md](INSTALL.md).

The full `.flow` language is printed by `flowscope schema` and described in the README under
[Write once, run later](README.md#write-once-run-laterwithout-an-ai-agent); projects are described
under [Projects](README.md#projects).

## Exit codes

Every command uses the same three, so scripts and CI can rely on them:

| Code | Meaning |
| --- | --- |
| `0` | Success: the command worked, the file is valid, the test passed. |
| `1` | A test, suite or plan ran and failed. |
| `2` | Invalid input: a syntax error, a missing file, a bad flag. Nothing ran. |

No command asks for interactive input during validation or replay. `record` is the only interactive
one.

## Setup and diagnostics

```bash
flowscope version                  # the installed version
flowscope doctor                   # version, adb, scrcpy, emulator, project and attached devices
flowscope devices                  # attached adb serials
flowscope emulators                # AVDs found through the Android SDK
flowscope emulator start <avd> [--headless] [--wait] [--timeout <millis>]
flowscope emulator stop <serial>   # only emulators FlowScope started
flowscope mirror [--serial <adb-serial>] [--read-only]   # open the device in scrcpy
```

`emulator start` returns at once; add `--wait` to block until the device has booted and print its
serial. Run `flowscope doctor` first whenever something cannot find a device or a binary.

## Look at a screen

```bash
flowscope snapshot --package <application-id> [--mode zero-code|instrumented]
                   [--serial <adb-serial>] [--output <directory>]
flowscope web inspect --package <application-id> [--serial <adb-serial>] [--page <match>]
                      [--mode zero-code|instrumented] [--format text|json]
flowscope schema                   # every action, selector and argument the .flow language accepts
```

`snapshot` saves one screenshot and UI hierarchy of whatever is on screen (default output
`./sessions`). Read `checkpoints/0000/hierarchy.xml` in the printed session directory for real
`resource-id`, `text` and `content-desc` values to use as selectors. `web inspect` does the same for a
WebView. `--mode` defaults to `zero-code`.

## Record a flow

```bash
flowscope record --package <application-id> [--mode zero-code|instrumented]
                 [--serial <adb-serial>] [--output <directory>]
```

Use the app on the device while it records. Press Enter to capture a checkpoint; type `q` then Enter
to stop. `instrumented` needs the debug agent in the app; it is never chosen for you.

## Projects

```bash
flowscope project init [<directory>] [--name <name>] [--package <id>]
                       [--mode zero-code|instrumented] [--timeout <millis>]
                       [--serial <adb-serial>] [--suite <file.suite>]
flowscope project info [<directory>]       # name, defaults and paths
flowscope project tests [<directory>]      # every .flow in flows/tests
flowscope project suites [<directory>]     # every .suite in flows/suites
flowscope project validate [<directory>]   # validate every test and suite
```

A project is a folder with `flowscope.json`, `flows/tests/`, `flows/suites/` and `reports/`. It only
supplies defaults: every `.flow` still runs on its own.

## Tests and suites

```bash
flowscope test validate <file.flow> [--project <directory>]
flowscope test run <file.flow> [--project <directory>] [--serial <adb-serial>]
                   [--output <directory>] [--secrets-file <path>] [--keep-hierarchy]

flowscope suite validate <file.suite> [--project <directory>]
flowscope suite run [<file.suite>] [--project <directory>] [--serial <adb-serial>]
                    [--output <directory>] [--secrets-file <path>] [--keep-hierarchy]
```

Inside a project, `flowscope test run login` finds `flows/tests/login.flow` and writes to `reports/`;
`flowscope suite run` with no file runs the project's default suite.

Every run writes `report.json`, `report.html`, `report.pdf` and `junit.xml` (a suite writes
`suite-report.*`). On a failure, `report.json` names the step and keeps a screenshot and the UI
hierarchy. `--keep-hierarchy` keeps the hierarchy of every executed step, not just the failing one.

Secrets: inside a project, runs load `flows/.secrets` (a dotenv file, owner-only) automatically.
`--secrets-file` points at a different one. Environment variables override file entries. Never commit
secret files.

## Several devices at once

```bash
flowscope plan validate <file.runplan.json>
flowscope plan show <file.runplan.json>
flowscope plan run <file.runplan.json> --device <role>=<serial> [--device ...]
                   [--max-parallel <count>] [--output <directory>]
                   [--secrets-file <project>=<path>] [--keep-hierarchy]
```

A `.runplan.json` gives each device role an ordered queue of tests and suites. `plan run` binds every
role to a serial and runs the queues in parallel. See
[examples/flows/plans/devices.runplan.json](examples/flows/plans/devices.runplan.json).

## Common recipes

```bash
# Start a project for an app and check it
flowscope project init my-app-tests --package com.example.debug
flowscope project validate my-app-tests

# Boot an emulator headless and run the regression suite on it
serial=$(flowscope emulator start Pixel_8_API_35 --headless --wait)
flowscope suite run --serial "$serial"

# The same in CI: validate everything, then run, and keep junit.xml
flowscope project validate && flowscope suite run --serial emulator-5554 --output build/flowscope
```

## The agent skill

`flowscope-tests` is a skill that teaches a coding agent the FlowScope workflow: find real selectors
with `snapshot`, write a `.flow`, validate it, run it, read the report on failure, and fix the
selector or wait instead of adding a sleep. It is two Markdown files,
[SKILL.md](skills/flowscope-tests/SKILL.md) and [reference.md](skills/flowscope-tests/reference.md).

Download the copy that matches your installed CLI, so the agent never learns syntax your runner does
not have. You do not need a clone of this repository.

### Claude Code

For one project (commit it so everyone on the project gets it):

```bash
v=$(flowscope version | awk '{print $2}')
mkdir -p .claude/skills/flowscope-tests
for f in SKILL.md reference.md; do
  curl -fsSL -o ".claude/skills/flowscope-tests/$f" \
    "https://raw.githubusercontent.com/FlowScopeDev/flowscope/v$v/skills/flowscope-tests/$f"
done
```

For every project on your machine, use `~/.claude/skills/flowscope-tests` instead of
`.claude/skills/flowscope-tests`.

Claude loads it on its own when you ask for a UI test, or run it explicitly with `/flowscope-tests`.

### Codex and other agents

Download the same two files into the repository, for example `docs/flowscope-tests/`:

```bash
v=$(flowscope version | awk '{print $2}')
mkdir -p docs/flowscope-tests
for f in SKILL.md reference.md; do
  curl -fsSL -o "docs/flowscope-tests/$f" \
    "https://raw.githubusercontent.com/FlowScopeDev/flowscope/v$v/skills/flowscope-tests/$f"
done
```

Then point the agent at them from `AGENTS.md`:

```markdown
## UI tests

Android UI tests are FlowScope `.flow` files in `flows/tests/`. Before writing or changing one, read
`docs/flowscope-tests/SKILL.md` and `docs/flowscope-tests/reference.md`, and follow the workflow there.
```

### What the agent needs

- `flowscope` on `PATH` (check with `flowscope doctor`).
- A project created with `flowscope project init`.
- A device or emulator attached over `adb` to find selectors and run tests. Without one, the agent
  can still write and validate tests, but it will ask you for the screen's ids rather than guess them.

### Things to ask it

- "Add a test for the login screen: enter the test user, submit, and check the home screen appears."
- "The checkout test fails on the payment step. Run it, read the report, and fix it."
- "Add the new settings tests to the regression suite and validate the project."

When you upgrade FlowScope, download the skill again so it matches the new version.
