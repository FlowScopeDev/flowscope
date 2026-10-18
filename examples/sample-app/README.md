# FlowScope sample app

A small Android app for testing every FlowScope feature end to end. It depends on FlowScope exactly
as a client app would, through Maven Central, with no copied source:

```kotlin
debugImplementation("io.github.flowscopedev:flowscope-agent:0.1.0")
releaseImplementation("io.github.flowscopedev:flowscope-agent-no-op:0.1.0")
```

It is a standalone Gradle build (it needs the Android SDK; the root FlowScope build does not).

```bash
cd examples/sample-app
./gradlew :app:installDebug                       # resolves the agent from Maven Central
./gradlew :app:installDebug -PuseMavenLocal=true  # unreleased agent from `publishToMavenLocal`
```

## Screens

Each toolkit is covered on purpose: FlowScope must record and replay both XML views and Compose.

| Screen | Toolkit | Exercises |
|---|---|---|
| Login | XML and Compose | typing, taps, validation errors, success state |
| List and detail | XML (RecyclerView) and Compose (LazyColumn) | scrolling, repeated rows, navigation, Back |
| WebView | XML and Compose (`AndroidView`) | `web-*` steps, cookie/storage metadata, agent bridge wrappers |
| Deep link | XML | `flowscope-sample://item/<id>` and `https://sample.flowscope.dev/item/<id>` |
| Permissions | XML | camera permission prompt, `grant-permission` / `revoke-permission` |
| Network and work | Compose | simulated request and background job through `onNetwork` / `onBackgroundWork` |
| Install referrer | XML | `installReferrerOverride` with `set-install-referrer` |

Demo credentials for the login screens: `user@example.com` / `correct-horse`.

Compose screens expose `testTag` values as resource ids (`id=email`, not
`io.github.flowscopedev.sample:id/email`), so the same `id=` selectors work in zero-code mode.

## Tests

The flows live in `examples/flows/tests/sample-*.flow` and run as one suite:

```bash
flowscope suite run flows/suites/sample.suite --project examples --serial <adb-serial>
```

Login, list, deep-link, and permission flows use `zero-code` mode. The network, referrer, and WebView
flows use `instrumented` mode, because they need the agent.
