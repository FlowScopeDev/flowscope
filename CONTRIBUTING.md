# Contributing to FlowScope

Thanks for helping. Bug reports, device compatibility notes, documentation and code are all welcome.
Please read the [Code of Conduct expectations](#conduct) and the [product contract](CLAUDE.md#product-contract)
before larger changes.

## Before you start

- For a bug, search the existing issues first and include the details the bug template asks for.
- For a feature, open an issue to discuss it before writing code. FlowScope's `.flow` language and
  CLI are deliberately small and treated as a stable API, so some good ideas will be declined.
- Security problems go through [SECURITY.md](SECURITY.md), never a public issue.

## Setup

You need JDK 21. The Android SDK platform-tools (`adb`) are needed to talk to a device; scrcpy and
FFmpeg are optional and never bundled. `./gradlew` downloads everything else.

```bash
./gradlew :recorder-host:build :recorder-ide:build   # compile and run all tests
./gradlew :recorder-ide:run                          # launch the IDE
./gradlew :recorder-host:installDist
./recorder-host/build/install/flowscope/bin/flowscope doctor
```

`android-agent/` is a separate Gradle build that needs the Android SDK; it is not part of the root
build. `examples/sample-app/` is a standalone Android app that depends on the published agent.

## Where things live

[CLAUDE.md](CLAUDE.md) is the authoritative engineering guide (the same file serves human and AI
contributors): repository layout, the stable CLI surface, the `.flow` language, the event protocol,
timing rules and security boundaries. [design.md](design.md) specifies the desktop interface.

## Making a change

- Keep changes focused and small. One concern per pull request.
- Match the surrounding code's style, naming and comment density.
- Add or update tests with the change.
- A change to `.flow`, `.suite` or `flowscope.json` needs, in the same pull request: parser
  validation, runner behaviour, `flowscope schema` output, a README example and tests.
- A change to a CLI command must keep output concise and scriptable and keep exit codes
  (`0` success, `1` failed test, `2` invalid input).
- Every icon-only IDE control needs a `HoverTip` and an accessibility `contentDescription`.
- Never weaken the security rules: no secrets in reports or logs, the agent stays debug-only, and the
  IDE writes only to a chosen project directory and `~/.flowscope/`.
- Do not bundle or silently download third-party binaries.
- Update `README.md`, `design.md` and `CLAUDE.md` when behaviour they describe changes. Add a line to
  `CHANGELOG.md` under **Unreleased** for anything a user would notice.

Before opening the pull request, run the build command above and, for flow changes,
`flowscope project validate examples`. If you changed device behaviour, say which device or
emulator and Android version you tried it on; if you could not, say that.

## Commits and pull requests

Write a short imperative subject line ("Add a hide-display button"), and use the body to explain
why. Fill in the pull request template. By contributing you agree that your work is licensed under
the [Apache License 2.0](LICENSE).

Once your change is merged, you are welcome to add yourself to [CONTRIBUTORS.md](CONTRIBUTORS.md).

## Conduct

Be kind and assume good faith. Critique code and ideas, not people. Harassment or discrimination of
any kind is not tolerated, and the maintainer may remove comments or contributors that cross the line.
