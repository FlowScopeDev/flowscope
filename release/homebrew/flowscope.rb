cask "flowscope" do
  version "1.0.0"
  sha256 "REPLACE_WITH_THE_SHA256_FROM_THE_RELEASE"

  url "https://github.com/FlowScopeDev/flowscope/releases/download/v#{version}/FlowScope-#{version}.dmg"
  name "Flow Scope"
  desc "Record, inspect, and replay Android UI flows as declarative tests"
  homepage "https://github.com/FlowScopeDev/flowscope"

  # Reads GitHub's latest-release endpoint, which skips pre-releases — the same endpoint the
  # app's own update check uses, so the two agree about what counts as released.
  livecheck do
    url :url
    strategy :github_latest
  end

  # The app is unsigned, so macOS quarantines it and refuses to open it until the attribute is
  # cleared (`xattr -cr`). Homebrew does not do that for us — Cask applies quarantine deliberately
  # — so the install docs tell people to clear it whichever route they took. The cask is worth
  # recommending for the verified checksum and managed upgrades, not for a quieter first launch.
  depends_on macos: :big_sur

  app "Flow Scope.app"

  # FlowScope writes to exactly two roots: a project directory the user chose, and ~/.flowscope.
  # Only the latter belongs to the app — a project is the user's own work and is never zapped.
  zap trash: [
    "~/.flowscope",
    "~/Library/Preferences/dev.flowscope.ide.plist",
    "~/Library/Saved Application State/dev.flowscope.ide.savedState",
  ]
end
