# Feature gaps

Open work and deliberate limitations. Implemented features belong in the
[feature catalog](terminal-feature-map.md); completed fixes belong in Git history
and the [changelog](../CHANGELOG.md). Entries here are not release commitments.

## Product and release work

| Owner | Remaining work |
| --- | --- |
| `TODO(host/profile)` | Restore completion settings controls and learning-reset actions in both products, with defaults, persistence, and tests updated together. The master switch remains off by default. |
| `TODO(host/profile)` | Gate binary/plugin delivery on checks of the exact release revision: opt-in native PTY tests (`-Dterminal.pty.host=true`), package checks, supported-IDE Plugin Verifier runs, and installed-product smoke tests. Ordinary CI runs tests and ABI checks, and Maven publication runs publication checks; binary release jobs do not depend on the full verification set. |
| `TODO(policy)` | Establish reproducible allocation and latency budgets for startup, warmed operation, changing content, history growth, and platform painting. Include retained heap and disposal; short local measurements are not a release baseline. |

## Transports and execution environments

| Owner | Remaining work |
| --- | --- |
| `TODO(transport)` | A built-in SSH connector, remote-session lifecycle, permissions, and appropriate paste defaults. Running `ssh` inside a local shell does not provide this integration. |
| `TODO(host/profile)` | WSL-aware directory, JDK, and startup-command mapping. Existing explicit-shell hooks do not provide guest filesystem completion or remote process metadata; see [shell support](features/shells.md). |
| `TODO(host/profile)` | Dev Container launch contexts and container-aware host services; no built-in container-session integration exists. |

<a id="input-module-gaps"></a>
<a id="deferred-kitty-keyboard-protocol-scope"></a>

## Input

| Owner | Remaining work |
| --- | --- |
| `TODO(host/profile)` | Native rich-input adapters with layout-aware key identity, IME text, and complete lifecycle metadata. Portable Swing must not advertise Kitty flags 2, 4, or 16 before these inputs are reliable. |
| `TODO(parser/core/input)` | Layout-aware modifyOtherKeys modifier masks, including `CSI > 4 : 1 m`. The parser rejects colon subparameters for this command; no corresponding negotiated mask is stored or applied by the encoder. |

## Optional protocol work

| Owner | Remaining work |
| --- | --- |
| `TODO(parser/core/render/ui/policy)` | Sixel and Kitty graphics: decoding, bounded transfer/decompression, retained-image budgets, render contracts, and painting. Kitty graphics requires APC dispatch. |
| `TODO(parser/core/policy)` | xterm XTCHECKSUM extensions beyond the implemented base VT420 checksum. |
| `TODO(parser)` | Additional ISO 2022 national replacement sets: UK, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish, Swedish, Swiss, and Portuguese. |
| `TODO(host/profile)` | Built-in desktop OSC 52 writes to primary/secondary selections and cut buffers, and reads from secondary selections and cut buffers. Library callbacks already expose selection-aware requests; custom hosts can implement these destinations. |

## Accepted limitations

- Streaming grapheme updates do not roll back published wraps, scrolling,
  overwrites, or insert shifts. Placement can depend on chunk boundaries; see the
  [placement contract](reference/protocol.md#streaming-grapheme-placement)
  and [verification disposition](development/conformance-testing.md#streaming-placement).
- The streaming parser retains at most 32 codepoints per grapheme; further
  continuations affect segmentation but are not emitted. Direct core cluster
  storage does not impose this parser limit.
- One session has one viewport. Independently scrolling views of one process
  and standalone reuse of the Swing renderer are outside the current API.
- Column spacing uses integer logical pixels; fractional cell advances are unavailable.
- Portable Swing exposes only the key metadata available through AWT.
- Color-scheme reporting is query-only; mode 2031 notifications are unavailable.
- Highlight mouse tracking (mode 1001) is unsupported.
- Native color-emoji rasterization is Windows-specific. Other platforms use
  Java2D/font fallback; the library has no equivalent native color-emoji backend.
- The screen-size query reports terminal-grid dimensions, not the monitor's
  capacity in cells; there is no separate host-supplied screen-size value.
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
