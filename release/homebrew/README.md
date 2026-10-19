# Publishing the Homebrew cask

`flowscope.rb` here is the **template**. The cask Homebrew actually serves lives in the separate
[`FlowScopeDev/homebrew-tap`](https://github.com/FlowScopeDev/homebrew-tap) repository, as
`Casks/flowscope.rb`. This copy is version-controlled with the app so the two cannot drift apart.

## Why the sha256 is a placeholder

A cask pins the exact bytes it installs, so the checksum cannot exist until the DMG has been built.
`.github/workflows/release-macos-dmg.yml` produces it as a `FlowScope-<version>.dmg.sha256` asset on
the release. **The template is deliberately unusable until that value is filled in** — a cask with a
wrong checksum fails the install with a mismatch error rather than installing something unverified.

## First publish

After the `v<version>` tag has built and the release assets exist:

```bash
version="1.0.0"
sha="$(curl -sSL "https://github.com/FlowScopeDev/flowscope/releases/download/v${version}/FlowScope-${version}.dmg.sha256")"
echo "$sha"   # 64 hex characters, nothing else
```

Then, from a clone of this repository:

```bash
git clone git@github.com:FlowScopeDev/homebrew-tap.git /tmp/homebrew-tap
mkdir -p /tmp/homebrew-tap/Casks
sed -e "s/^  version .*/  version \"${version}\"/" \
    -e "s/^  sha256 .*/  sha256 \"${sha}\"/" \
    release/homebrew/flowscope.rb > /tmp/homebrew-tap/Casks/flowscope.rb
```

Check it before committing — `brew style` needs the file inside a tap, so run it from there:

```bash
cd /tmp/homebrew-tap && brew style --cask Casks/flowscope.rb
```

Then commit and push:

```bash
cd /tmp/homebrew-tap
git add Casks/flowscope.rb
git commit -m "flowscope ${version}"
git push origin main
```

Finally, confirm the path the README promises actually works:

```bash
brew untap FlowScopeDev/homebrew-tap 2>/dev/null
brew tap FlowScopeDev/homebrew-tap
brew install --cask flowscope
```

## Later releases

Same two substitutions against the new version and checksum. Keep this template updated in the same
commit as the version bump, so `release/homebrew/flowscope.rb` always describes the current release.

## What not to put here

No tokens and no credentials. The tap is pushed from a machine that already has the right SSH key;
nothing in this repository needs write access to it.
