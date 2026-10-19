# FlowScope Design System

Visual and interaction specification for the FlowScope desktop application. This document describes
the interface as it currently exists and is the design contract for `recorder-ide`.

## 1. Product character

FlowScope is an instrument for observing an Android device, recording a session, inspecting evidence,
and authoring durable `.flow` tests. The interface is compact, technical, and content-first.

Three principles govern the UI:

1. Chrome stays visually quiet so the connected application remains the focus.
2. Colour communicates state: armed, live, healthy, degraded, failed, or focused.
3. Persistent regions use opaque surfaces and hairline separators; popovers are the only floating
   layers.

## 2. Colour

All structural colour uses the `ink` ramp. Signal colours have fixed semantic meanings in both
appearances.

### Dark appearance

| Token | Value | Use |
|---|---:|---|
| `ink.000` | `#000000` | Device bezel and image backdrop |
| `ink.050` | `#06070A` | Window background |
| `ink.100` | `#0B0D11` | Primary work surface |
| `ink.150` | `#11141A` | Session bar, cards, and drawer |
| `ink.200` | `#171B22` | Hover surface |
| `ink.250` | `#1E232C` | Pressed and selected surface |
| `ink.300` | `#262C36` | Default separator |
| `ink.400` | `#39414E` | Strong border and inactive status |
| `text.hi` | `#EDF0F4` | Primary labels and values |
| `text.mid` | `#99A1AD` | Secondary labels and metadata |
| `text.low` | `#5E6673` | Placeholders and disabled content |

### Light appearance

| Token | Value | Use |
|---|---:|---|
| `ink.000` | `#FFFFFF` | Device frame surface |
| `ink.050` | `#F8F9FB` | Window background |
| `ink.100` | `#F4F6F8` | Primary work surface |
| `ink.150` | `#EEF1F5` | Session bar, cards, and drawer |
| `ink.200` | `#E6EAF0` | Hover surface |
| `ink.250` | `#DAE0E8` | Pressed and selected surface |
| `ink.300` | `#C6CED8` | Default separator |
| `ink.400` | `#98A3B2` | Strong border and inactive status |
| `text.hi` | `#171B22` | Primary labels and values |
| `text.mid` | `#46505E` | Secondary labels and metadata |
| `text.low` | `#687382` | Placeholders and disabled content |

### Signal colours

| Meaning | Dark | Light | Dim surface |
|---|---:|---:|---:|
| Armed / warning | `#FFB224` | `#9A5B00` | `#2A1F08` / `#FFF0D1` |
| Live / failed | `#FF4F56` | `#C9323C` | `#2A1012` / `#FDE8EA` |
| Healthy / passed | `#3ECF8E` | `#147A50` | Uses the surrounding surface |
| Focus / reference | `#9DBCFF` | `#315FB5` | `#121A2B` / `#E6ECF8` |

Selection uses an ink surface and a high-contrast label. Every signal colour is paired with a label,
shape, glyph, or stable position so meaning never depends on colour alone.

## 3. Typography and geometry

- UI labels use the platform sans-serif family.
- Technical values, timestamps, package names, selectors, source text, telemetry, and micro-labels
  use monospace.
- Tabular numerals are used where values update in place.
- Primary body text is 12–13sp; compact metadata is 10–11sp; section labels are uppercase monospace
  with increased letter spacing.
- The base spacing unit is 4dp.
- Interactive icon targets are at least 32×32dp.
- Default corners are 4dp for controls, 8dp for cards and popovers, and 26dp for the device bezel.
- Persistent regions have no shadow. Separation comes from surface value and 1px hairlines.

## 4. Application shell

The window is composed of four persistent regions:

```text
┌ session bar ───────────────────────────────────────────────────────────────┐
├ live device ┬ active work surface ────────────────────────┬ detail drawer ┤
│             │                                             │               │
│             │                                             │               │
├─────────────┴─────────────────────────────────────────────┴───────────────┤
└ telemetry strip ──────────────────────────────────────────────────────────┘
```

On macOS, a 36dp drag region provides clearance for the native traffic lights. The controls begin
below that area. Windows retains its native frame while using the same internal shell.

### Session bar

The 44dp session bar contains, from left to right:

- five subsystem health dots for ADB, device, stream, agent, and session;
- connected-device selector;
- searchable application ID field with favourites and installed-package suggestions;
- green play button that saves and runs the open flow or suite;
- checkpoint count;
- capture-mode selector for Zero-code or Instrumented;
- camera checkpoint action;
- Settings gear;
- Record / Stop transport control.

Health dots open a menu containing the label, state, detail, and available recovery action for each
subsystem. The camera uses a conventional outlined-camera glyph and is available while recording.
The Settings gear sits immediately left of Record and has an active treatment while Settings is open.

The play button sits beside the application ID rather than in the transport cluster: which app, on
which device, and run it are one decision, and the two chips to its left are the other half of it.
It is the only run control — the Flow surface's bottom bar keeps the save state and the target
device, so the two can never disagree about whether a run is possible. It is green when runnable,
amber and square while a run is in flight, and dim otherwise; the hover hint names what is missing
rather than only disappearing, so a disabled control still explains itself.

### Switching projects

A project owns the target application ID and the open file, so switching swaps both. The ID comes
from the new project's `defaults.package`, or, when it declares none, from the `applicationId` in
the project's own Gradle build — detected and offered in Project settings, never written back
automatically, because `flowscope.json` is authored input and opening a project must not produce a
diff. The editor reopens whatever that project was last on, and opens empty otherwise; a file is
only ever remembered against the project that contains it. A buffer with unsaved edits stops the
switch with a Save / Discard / Cancel prompt, and Save only continues once the write succeeds.

### Work surfaces

Timeline, Flow, Evidence, Tree, and Perf are tabs within one session. The device remains visible while
moving among them. Settings occupies the full workspace and temporarily hides the device column,
detail drawer, and drawer toggle. Activating the Settings gear again restores the exact surface and
drawer state that preceded it.

### Detail drawer

The right drawer displays context for the selected event, step, checkpoint, tree node, or metric.
The surface bar can hide or show it without changing the current selection.

### Telemetry strip

The bottom strip presents concise bracketed state such as ADB device count, stream state, and session
state. Labels and values share a vertical centre line and use monospace text.

## 5. Live device

The device column sizes from the available height and the decoded frame aspect ratio. Its width is a
result of the device, never a fixed handset assumption. Portrait, landscape, tablets, and rotations
therefore preserve the frame without distortion.

The stage consists of:

- four restrained corner ticks outside the device;
- a 7dp bezel with a 26dp outer radius and an 18dp image radius;
- a live scrcpy frame when streaming is available;
- an ADB screenshot fallback;
- a 2px red bezel while recording.

Pointer coordinates and feedback overlays use the exact displayed-image rectangle, including any
letterboxing. Input therefore maps to the frame the user can see.

### Interaction modes

`INSPECT` resolves the touched UI node, emits a durable selector when possible, and displays semantic
feedback. A tap produces a contact ripple, resolved bounds, selector label, and stability indicator.
A drag displays a focused path with a direction arrow and then fades.

`MIRROR` is low-latency passthrough. Taps, drags, scrolls, keys, and text still reach Android and raw
interactions can appear in the timeline, but selector overlays and gesture trails are not shown and
durable steps are not generated.

### Device dock

The 44dp dock provides Back, Home, App Switch, text input, Inspect/Mirror selection, stream status,
and overflow actions. Buttons are icon-led, expose tooltips and accessibility labels, and use 32dp
hit targets.

## 6. Timeline

Timeline presents all captured activity in one ordered, virtualised lane. Channel chips filter that
lane without changing chronology:

- All
- UI
- Network
- Background
- Lifecycle
- Logs
- Session

Each row contains time, event glyph, title, compact detail, and optional duration. Severity is shown
by a leading rule and a signal colour. Selecting a row opens its structured payload and suggested
step in the detail drawer. Follow mode keeps the newest event visible until the user scrolls away.

## 7. Flow authoring

Flow is a source-oriented `.flow` editor with:

- line numbers;
- syntax colour for keywords, selectors, strings, numbers, and comments;
- live parse validation and line-level markers;
- generated-step insertion;
- save and run controls;
- an outcome summary linked to the replay report.

Lifecycle instructions are grouped as `BEFORE`, `TEST`, and `AFTER` in card view. Recent runs open
their offline HTML report from the rail and retain a separate reveal-folder action. JSON remains the
machine-readable source for the IDE, while JUnit output supports CI.

The outcome summary names the phase that broke rather than reporting a bare failure. A run whose
body passed but whose cleanup failed reads `FAILED · CLEANUP`, and each cleanup failure is posted to
the timeline as its own error entry — never folded into the test's own failure, which would send the
author looking for a bug that is not in the test.

When an IDE-started run completes, FlowScope posts one native system notification after its reports
are ready. A test notification names the test and its result. A regression notification names the
suite and summarizes passed, failed, and skipped tests. Detailed failures stay in FlowScope and the
reports; CLI and CI runs do not send desktop notifications.

Durable actions use semantic selectors. Raw coordinates and observed recording delays are evidence,
not generated test syntax.

Deep-link, install, and WebView instructions use the same lifecycle groups and step cards as other
actions. Their summaries describe intent—Open a deep link, Install an APK, Assert WebView text—while
source view retains the exact declarative arguments. WebView failures distinguish an inaccessible
native hierarchy from an unavailable debug socket, and report details show only sanitized target
URLs and artifact metadata.
Instrumented referrer setup appears as explicit Inject/Clear step cards. Reports show only whether
an override was present; the campaign value is never rendered in the IDE or report.

## 8. Evidence, tree, and performance

Evidence shows captured checkpoints as a filmstrip and uses the detail drawer for screenshot,
hierarchy, window-state, and metadata inspection.

Tree has Native and WebView modes. Native renders the latest UIAutomator hierarchy as structured
nodes. WebView lists package-owned DevTools targets and their frames, cookie metadata, Web Storage
keys, native JavaScript bridges, and security settings. Cookie and storage values always render as
`HIDDEN`. Runtime bridge discovery is labelled `HEURISTIC`; agent registrations are labelled
`EXACT`. Selecting a row opens its safe metadata in the detail drawer, and the icon-only refresh
control has a hover hint and accessibility label.

Perf presents package-scoped memory and rendering data as labelled metric tiles. Values, units, and
health states remain readable without relying on colour alone.

## 9. Floating layers

Dropdowns, package suggestions, command palette, selector details, text input, and toasts are floating
layers. They use `ink.150`, an `ink.400` border, an 8dp radius, and compact spacing. They dismiss with
Escape and do not obscure persistent state longer than necessary.

The command palette exposes navigation and common actions, including Open Settings and interaction
mode selection.

## 10. Settings and appearance

Settings is a centred surface with a readable maximum width. The Appearance section contains three
radio-style rows:

- System — follows the operating-system appearance;
- Light — pins the light palette;
- Dark — pins the dark palette.

Changes apply immediately. The selected lowercase value is stored in `~/.flowscope/theme.txt` and
invalid or missing values resolve to System.

## 11. Motion and feedback

- Hover: 80ms
- Press: 60ms
- Popover entry: 120ms fade with a short vertical translation
- Drawer transition: 180ms
- Tap ripple: 420ms
- Resolved bounds: visible for 1200ms, then a 200ms fade
- Swipe trail: visible during an Inspect drag, then a short fade
- Recording pulse: 1200ms loop

Reduced-motion mode removes translation, looping pulse, and decorative interpolation while retaining
immediate state changes and readable feedback.

## 12. Keyboard and accessibility

Global shortcuts are available for command search, recording, checkpoint capture, surface navigation,
drawer visibility, and interaction mode. Shortcuts are disabled while a text editor owns focus unless
the command is explicitly safe there.

All icon-only controls provide semantic labels and tooltips. Focus order follows the visible layout:
session bar, device controls, surface tabs, surface content, detail drawer, telemetry. Focus uses the
ice signal stroke. Primary and secondary text meet WCAG AA against their assigned work and chrome
surfaces.

## 13. Responsive behaviour

- The live-device column follows frame aspect ratio within a bounded width.
- The detail drawer collapses before the device column is removed.
- At constrained widths, labels may shorten but primary actions retain their hit targets.
- Settings remains centred and scrollable.
- Device images never stretch or crop to satisfy layout constraints.

## Android agent library

`android-agent/` publishes `io.github.flowscopedev:flowscope-agent` and `flowscope-agent-no-op` (same public API;
the noop twin has no behaviour or manifest entries). The real agent self-installs from a
`ContentProvider`, registers `ActivityLifecycleCallbacks`, and wraps each window's `Window.Callback`
once to capture touches, delegating so it composes with app-owned wrappers.
The debug artifact also exposes `installReferrerOverride(context)` for an app-owned referrer
abstraction; the no-op release twin returns `null` from the same API.

Publishing uses the Vanniktech Maven Publish plugin against the Central Portal. Signing only turns on
when `signingInMemoryKey` is supplied, so `publishToMavenLocal` needs no secrets. The Kotlin package
(`dev.flowscope.agent`) is deliberately independent of the Maven group.
