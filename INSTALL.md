# Installing FlowScope

FlowScope ships two things that install separately: the **desktop app** (the recorder, editor,
inspector and run surfaces) and the **CLI** (`flowscope`, which records and replays without a UI).
Neither is required by the other. Tests run with the CLI alone, which is the point of the format —
replay never needs an IDE.

## Read this before opening the macOS app

FlowScope is **not signed with an Apple Developer ID**. It is a one-person project, and that
certificate costs money every year. macOS therefore quarantines the app and refuses to open it — not
because anything is wrong with the download, but because that is what macOS does with any
quarantined app it cannot verify. This applies to **both** install routes below.

There are two ways around it, and the first avoids the dialog entirely.

### Homebrew (recommended)

```bash
brew tap FlowScopeDev/homebrew-tap
brew trust --cask flowscopedev/tap/flowscope
brew install --cask flowscope
xattr -cr "/Applications/Flow Scope.app"
```

Four commands, and the middle two deserve an explanation.

`brew trust` is required because Homebrew refuses to load casks from third-party taps until you say
you trust them. Without it you get *"Refusing to load cask … from untrusted tap"*.

`xattr -cr` is still needed. **Homebrew does not spare you the Gatekeeper step** — Cask applies the
quarantine attribute deliberately, as a security measure, and there is no longer a flag to opt out.
An app installed by Homebrew is quarantined exactly like one you downloaded yourself.

What the cask does give you is a checksum Homebrew verifies before installing anything, and managed
upgrades and uninstalls (`brew uninstall --cask flowscope`). Those are the reasons to prefer it —
not a quieter first launch.

### The DMG by hand

Download `FlowScope-<version>.dmg` from the
[releases page](https://github.com/FlowScopeDev/flowscope/releases) and drag **Flow Scope.app** into
`/Applications`. Then do one of:

- **Right-click the app and choose Open.** Confirm once; macOS remembers the choice.
- **Clear the attribute yourself:**

  ```bash
  xattr -cr "/Applications/Flow Scope.app"
  ```

Either is enough, and either is needed whichever way you installed.

## Windows

**No Windows package is published yet.** Compose Desktop builds Windows installers only from a
Windows host, and that build has not been exercised, so shipping one would mean shipping something
untested. `./gradlew :recorder-ide:packageMsi` on a Windows machine with a JDK 21 toolchain produces
one, and the CLI zip below already works there.

When a Windows package does ship it will be unsigned for the same reason as the macOS app, so
expect:

- **SmartScreen**: "Windows protected your PC" → **More info** → **Run anyway**.
- **Antivirus**: an *Allow* prompt is normal for an unsigned installer.

## The CLI

Two ways, depending on whether you want a release or the working tree.

**From a release** — `flowscope-cli-<version>.zip` is attached to every release. Unzip it anywhere
and put its `bin/` on your `PATH`:

```bash
unzip flowscope-cli-1.0.0.zip
export PATH="$PWD/flowscope-1.0.0/bin:$PATH"
flowscope version
```

**From source** — needs a JDK 21:

```bash
./gradlew :recorder-host:installDist
export PATH="$PWD/recorder-host/build/install/flowscope/bin:$PATH"
```

The CLI needs a Java 21 runtime on the machine. The desktop app does not: its runtime is bundled.

## Verifying a download

Every release asset has a `.sha256` file beside it holding one line of hex.

```bash
shasum -a 256 FlowScope-1.0.0.dmg
cat FlowScope-1.0.0.dmg.sha256
```

The two strings must match. The release notes list both checksums as well, so you can compare
without downloading the extra file.

## What each download needs

| | Desktop app (DMG) | CLI (zip) |
| --- | --- | --- |
| Minimum OS | macOS 11 Big Sur | any OS with a JDK 21 |
| Java runtime | bundled | yours |
| Signed | no — see above | n/a |
| Approximate size | 79 MB (bundles a JDK) | 290 KB |

The macOS floor comes from the Java 21 runtime jpackage bundles into the app. The `Info.plist`
jpackage writes claims a far older minimum (`10.13`), which is its generic default rather than a
tested figure — treat Big Sur as the real floor. FlowScope has only been exercised on current macOS.

## What FlowScope expects you to install yourself

FlowScope never bundles or downloads third-party binaries.

- **Android platform tools (`adb`)** — required. From your own Android SDK install.
- **`apkanalyzer`** — only for `install-apk` / `assert-installed` steps.
- **The SDK `emulator` binary** — only for `flowscope emulators` and `emulator start`.
- **scrcpy and FFmpeg** — optional, and only to make device interaction faster and the embedded
  Live Device view available. Screenshots over ADB are the fallback.

```bash
flowscope doctor
```

`doctor` prints the version and what it found for each of these, and is the right thing to paste
into a bug report.

## Building from source instead

```bash
./gradlew :recorder-host:build :recorder-ide:build
./gradlew :recorder-ide:run
```

On macOS a local `createDistributable` build is stripped of inherited quarantine metadata and
ad-hoc signed, so Finder will open it on the machine that built it. See
[CONTRIBUTING.md](CONTRIBUTING.md).
