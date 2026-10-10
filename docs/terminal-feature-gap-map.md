# Feature gaps

Open work and deliberate limitations. Implemented features belong in the
[feature catalog](terminal-feature-map.md); completed fixes belong in Git history
and the [changelog](../CHANGELOG.md). Entries here are not release commitments.

## Product and release work

| Owner | Remaining work |
| --- | --- |
| `TODO(host/profile)` | Restore completion settings controls and learning-reset actions in both products, with defaults, persistence, and tests updated together. The master switch remains off by default. |
| `TODO(host/profile)` | Verify host-owned completion popup triggers, placement, cancellation, and disposal in real embedding integrations. Add library controls only for a demonstrated limitation. |
| `TODO(host/profile)` | Gate binary/plugin delivery on checks of the exact release revision: native PTY tests, package checks, supported-IDE Plugin Verifier runs, and installed-product smoke tests. Current CI does not provide this complete gate. |
| `TODO(policy)` | Establish reproducible allocation and latency budgets for startup, warmed operation, changing content, history growth, and platform painting. Include retained heap and disposal; short local measurements are not a release baseline. |

## Transports and execution environments

| Owner | Remaining work |
| --- | --- |
| `TODO(transport)` | A built-in SSH connector, remote-session lifecycle, permissions, and appropriate paste defaults. Running `ssh` inside a local shell does not provide this integration. |
| `TODO(host/profile)` | WSL-aware directory, JDK, and startup-command mapping, plus Dev Container launch contexts. Current WSL profiles launch local executables and support only the documented [shell hooks](features/shells.md). |

<a id="input-module-gaps"></a>
<a id="deferred-kitty-keyboard-protocol-scope"></a>

## Input

| Owner | Remaining work |
| --- | --- |
| `TODO(host/profile)` | Native rich-input adapters with layout-aware key identity, IME text, and complete lifecycle metadata. Portable Swing must not advertise Kitty flags 2, 4, or 16 before these inputs are reliable. |
| `TODO(input)` | Layout-aware modifyOtherKeys modifier masks, including `CSI > 4 : 1 m`. |
| `TODO(input/policy)` | Additional Delete/Meta encoding policies when a concrete compatibility need is established. |
| `TODO(parser/core/input)` | Highlight mouse tracking (mode 1001), if required by a supported application. |
| `TODO(host)` | Mouse-report policy callbacks if product or embedding feedback requires them. |

## Optional protocol work

| Owner | Remaining work |
| --- | --- |
| `TODO(parser/core/render/ui/policy)` | Sixel and Kitty graphics: decoding, bounded transfer/decompression, retained-image budgets, render contracts, and painting. Kitty graphics requires APC dispatch. |
| `TODO(parser/core/policy)` | xterm XTCHECKSUM extensions beyond the implemented base VT420 checksum. |
| `TODO(parser)` | Additional ISO 2022 national replacement sets: UK, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish, Swedish, Swiss, and Portuguese. |
| `TODO(host/ui/policy)` | OSC 52 writes to primary/secondary selections and cut buffers; reads from secondary selections and cut buffers. |

## Accepted limitations

- Streaming grapheme updates do not roll back published wraps, scrolling,
  overwrites, or insert shifts. Placement can depend on chunk boundaries; see the
  [placement contract](reference/protocol.md#streaming-grapheme-placement)
  and [verification disposition](development/conformance-testing.md#streaming-placement).
- One session has one viewport. Independently scrolling views of one process
  and standalone reuse of the Swing renderer are outside the current API.
- Column spacing uses integer logical pixels; fractional cell advances are unavailable.
- Portable Swing exposes only the key metadata available through AWT.
- Color-scheme reporting is query-only; mode 2031 notifications are unavailable.
- No supported generation-specific completed-paint observer is exposed by Swing.
  Frame publication does not prove screen presentation.

## Intentional exclusions

- Literal parity with every historical xterm extension.
- Tektronix 4014, printer passthrough, and X11 font-loading protocols.
- ReGIS/vector graphics and raw 8-bit C1 mode without a demonstrated modern use case.
- Unbounded OSC/DCS responses or clipboard access that bypasses host policy.
- DA3 unique identity replies and window-position reports.
- XTSETTCAP mutation of the advertised capability identity.

## Status Labels

- `TODO(parser)`: byte/protocol recognition or semantic dispatch is missing.
- `TODO(core)`: terminal state, grid physics, pen storage, or public API is missing.
- `TODO(host)`: parser and core both have enough shape, but the integration bridge is incomplete.
- `TODO(session)`: runtime synchronization, host-side state, or session-owned metadata is missing.
- `TODO(transport)`: a connector implementation or transport integration is missing.
- `TODO(render)`: render contracts or copied render data are missing.
- `TODO(ui)`: reusable UI presentation, interaction, or rendering behavior is missing.
- `TODO(input)`: host-bound keyboard/mouse/paste encoding is missing.
- `TODO(completion)`: completion evaluation, ranking, learning, or source-work lifecycle is missing or incorrect.
- `TODO(host/profile)`: product host integration, profile, or settings behavior is missing.
- `TODO(policy)`: feature needs an explicit security or compatibility policy before implementation.

Combined labels name every owner needed to close one gap; `host/profile` means product-host integration rather than the parser-to-core adapter. A policy or test limitation alone does not imply missing parser behavior.
