---
name: flowscope-tests
description: Write, validate and run FlowScope Android UI tests (.flow files). Use when asked to add, update, fix or debug an Android UI test, a regression suite, or a test for a user flow in an app that uses FlowScope.
---

# Writing FlowScope tests

FlowScope tests are plain-text `.flow` files. The runner executes them with no AI model, IDE or app
source code, so your job is to produce a correct file, not to drive the device yourself at test time.
Never invent syntax: if an action or argument is not in `reference.md` or `flowscope schema`, it does
not exist.

## Workflow

1. **Orient.** Run `flowscope project info` (inside the project) and `flowscope doctor`. Read the
   project's `flowscope.json` for the package, mode and timeout, and look at one or two existing
   tests in `flows/tests/` so you match their style.
2. **Check the language.** Run `flowscope schema` if you need an action you have not used here.
3. **Find real selectors.** Do not guess ids or copy. With a device attached
   (`flowscope devices`), put the app on the screen you are testing and run:

   ```bash
   flowscope snapshot --package <id> --mode zero-code --output <scratch-dir>
   ```

   It prints a session directory. Read `checkpoints/0000/hierarchy.xml` for `resource-id`, `text`,
   `content-desc` and `class` values, and look at `screenshot.png`. For WebViews use
   `flowscope web inspect --package <id> --format json`. If there is no device, ask the user for a
   hierarchy dump or the screen's resource ids rather than inventing them.
4. **Write the flow** at `flows/tests/<name>.flow` using the rules below.
5. **Validate.** `flowscope test validate flows/tests/<name>.flow`. Exit `0` is valid, `2` is a syntax
   error with a line number. Fix and repeat. Validate the whole project with `flowscope project validate`.
6. **Run it when a device is available:** `flowscope test run <name> --serial <serial>`. Add
   `--secrets-file <owner-only-path>` only when the flow declares local secret inputs. Exit `0`
   passed, `1` failed. On failure read `reports/<run>/report.json`; it records the failing step, a
   screenshot and the last UI hierarchy. Fix the selector or the wait, never add a sleep.
7. **Add it to a suite** (`flows/suites/*.suite`, `test tests/<name>.flow`) when it belongs in the
   regression run, then `flowscope suite validate`.
8. **Report honestly.** Say whether you only validated or also ran it on a device. Do not claim a
   test passes if you could not run it.

## Rules that keep tests durable

- Selector preference: `id` (full `package:id/name`), then `desc`, then `text` for copy that is part of
  the assertion. Combine fields only when one is ambiguous; `index=N` is a last resort.
- Never use raw coordinates and never add fixed sleeps. Mutating steps wait for the UI to settle;
  `wait-visible` and the assert steps poll until their timeout. Raise `timeout=` on slow steps.
- One behaviour per test, short and independent. Start from a known state: `before clear-app-data`,
  `before grant-permission ...`, and end with `after force-stop`. Cleanup in `after` always runs,
  and a failed cleanup is reported as its own failure — read the phase before editing the test.
- Use `tap-if-visible` for optional dialogs (permission prompts, banners, onboarding).
- Assert outcomes the user can see: `assert-visible`, `assert-not-visible`, state filters such as
  `checked=true`. Add a `screenshot` at the end of important flows.
- Every `.flow` keeps its own `package`, `mode` and `timeout` header. Do not rely on `flowscope.json`
  at run time.
- `mode` is explicit: `zero-code` works on any debuggable APK; `instrumented` needs the debug agent
  and also records network and background-work events. Do not switch modes to make a test pass.
- Secrets never go in a flow. Use `${env.NAME}` or an explicitly loaded `${secret.NAME}` for tokens
  and passwords; use `capture-secret.<name>` for API tokens. Do not put real customer
  data, phone numbers or financial identifiers in `type value=`.
- Device actions are scoped to the declared package. Never ask for global permission resets.

## Minimal template

```text
flowscope 1
name "Login rejects a bad email"
package com.example.debug
mode zero-code
timeout 10000

before clear-app-data
step launch
step tap-if-visible text="Allow" timeout=3000
step tap id=com.example.debug:id/open_login
step type id=com.example.debug:id/email value="not-an-email"
step tap id=com.example.debug:id/sign_in
step assert-visible text="Enter a valid email address"
step screenshot name=bad-email
after force-stop
```

See `reference.md` for every action, selector filter and gesture, and for suites and projects.

## Updating an existing test

Read the whole file first. Change the smallest number of lines, keep the header and the author's
naming, re-validate, and re-run if you can. If a test fails because the app genuinely changed, update
the selector from a fresh snapshot; if it fails because the app is broken, report the failure and do
not edit the test to hide it.
