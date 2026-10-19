FlowScope __VERSION__ — record Android UI flows and replay them as declarative `.flow` tests.

## Read before opening

FlowScope is not signed with an Apple Developer ID. It is a one-person project and that certificate
costs money every year, so macOS will tell you the app is damaged if you open the DMG directly.
Nothing is wrong with the download — macOS says that about any unsigned app it quarantines.

**The easy way (no warning):**

```bash
brew tap FlowScopeDev/homebrew-tap
brew install --cask flowscope
```

Homebrew clears the quarantine attribute on what it downloads, so the dialog never appears.

**Installing the DMG by hand:** drag `Flow Scope.app` to Applications, then either right-click it and
choose **Open**, or clear the attribute yourself:

```bash
xattr -cr "/Applications/Flow Scope.app"
```

Full details, including the Windows notes, are in
[INSTALL.md](https://github.com/FlowScopeDev/flowscope/blob/main/INSTALL.md).

## Downloads

| File | What it is |
| --- | --- |
| `FlowScope-__VERSION__.dmg` | macOS desktop app (Apple silicon and Intel, macOS 11 Big Sur or newer) |
| `flowscope-cli-__VERSION__.zip` | Cross-platform CLI — unzip and run `bin/flowscope` (needs a JDK 21 runtime) |

No Windows package is published yet.

## Verifying your download

Each file ships with a `.sha256` next to it:

```bash
shasum -a 256 FlowScope-__VERSION__.dmg
```

- `FlowScope-__VERSION__.dmg` — `__DMG_SHA256__`
- `flowscope-cli-__VERSION__.zip` — `__CLI_SHA256__`

## What you need

FlowScope does not bundle or download third-party binaries. Android platform tools (`adb`) come from
your own SDK install; scrcpy and FFmpeg are optional and only speed up interaction. Run
`flowscope doctor` to see what it found.
