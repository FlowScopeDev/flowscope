# Security Policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue or pull request.

Use GitHub's private reporting: open the **Security** tab of
[FlowScopeDev/flowscope](https://github.com/FlowScopeDev/flowscope/security) and choose
**Report a vulnerability**. Include what you found, the FlowScope version or commit, how to reproduce
it, and what you think the impact is.

You can expect an acknowledgement within a week. FlowScope is maintained by one person in their own
time, so fixes take as long as they take, but a confirmed issue is prioritised over feature work. We
will credit you in the release notes unless you prefer not to be named.

## Supported versions

Only the latest release receives security fixes. The Android agent (`flowscope-agent`) is versioned
separately; again only the latest version is supported.

## Scope

In scope: the host CLI, the desktop IDE, the `.flow` runner, and the debug agent. Of particular
interest:

- A debug-agent build leaking into, or emitting events from, a release variant.
- Secrets, headers, bodies, cookies, storage values or query values reaching reports, logs, the IDE,
  notifications or `events.jsonl`.
- A `.flow`, `.suite` or `flowscope.json` file that makes the runner act outside the declared
  package, read or write outside its project, or run arbitrary host commands.
- Path traversal in suite references, APK paths or project resolution.

Out of scope: problems in third-party tools FlowScope launches but does not bundle (adb, scrcpy,
FFmpeg, the Android SDK), and anything that needs an attacker who already controls your machine or
an unlocked, debuggable device you have authorised for USB debugging.

## Handling your own data

Recording sessions can contain whatever was on the device screen. They live under
`~/.flowscope/sessions/` and in a project's `reports/` directory, both of which are kept out of Git
by default. Review them before sharing a bug report, and redact anything sensitive.
