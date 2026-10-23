# Feature gaps

Open work and deliberate limitations. Implemented features belong in the
[feature catalog](terminal-feature-map.md); completed fixes belong in Git history
and the [changelog](../CHANGELOG.md). Entries here are not release commitments.

## Product and release work

| Owner | Remaining work |
| --- | --- |
| `TODO(host/profile)` | Restore completion settings controls and learning-reset actions in both products, with defaults, persistence, and tests updated together. The master switch remains off by default. |
| `TODO(host/profile)` | Persist and restore standalone window, tab, split, profile, and working-directory layouts. IntelliJ already restores tab metadata; neither product restores running processes or historical output. |
| `TODO(host/profile)` | User-configurable standalone key sequences, modal key tables, and chained actions. Library shortcut maps and IntelliJ keymap integration already exist; they do not provide this standalone configuration surface. |
| `TODO(host/profile)` | A standalone command palette for discovering and invoking terminal and workspace actions. IntelliJ's action system is host-owned and already available there. |
| `TODO(host/profile)` | A standalone quick/drop-down terminal with platform-appropriate activation and focus handling. Ordinary terminal windows do not provide this workflow. |
| `TODO(host/profile)` | A product read-only session toggle that blocks outbound user input while retaining output, selection, and search. Read-only render frames and closed-session presentation are different capabilities. |
| `TODO(host/profile)` | Rearrange existing standalone split panes without restarting their sessions, and provide bounded undo of pane closure with explicit process-lifetime semantics. Creating and resizing splits already works. |
| `TODO(host/profile)` | Gate binary/plugin delivery on checks of the exact release revision: opt-in native PTY tests (`-Dterminal.pty.host=true`), package checks, supported-IDE Plugin Verifier runs, and installed-product smoke tests. Ordinary CI runs tests and ABI checks, and Maven publication runs publication checks; binary release jobs do not depend on the full verification set. |
| `TODO(policy)` | Establish reproducible allocation and latency budgets for startup, warmed operation, changing content, history growth, and platform painting. Include retained heap and disposal; short local measurements are not a release baseline. |

## Transports and execution environments

| Owner | Remaining work |
| --- | --- |
| `TODO(transport)` | A built-in SSH connector, remote-session lifecycle, permissions, and appropriate paste defaults. Running `ssh` inside a local shell does not provide this integration. |
| `TODO(session/transport)` | Propagate viewport pixels through session/connector resize to native PTYs once backend support is available. In-band size queries work; ioctl-only clients can still lack pixels. The [backend reference](../ketraterm-pty/docs/pty4j-backend-limitations.md#native-pixel-dimensions) records pty4j's discarded pixel arguments, Windows/WSL constraints, and the explicit-size workaround. |
| `TODO(transport)` | Update the bundled Windows ConPTY backend to include Microsoft's graphics cursor synchronization fix. pty4j 0.14.0's bundled backend can leave PowerShell input above its visible prompt after Kitty graphics and resize. The [native reproduction and upstream fix](../ketraterm-pty/docs/pty4j-backend-limitations.md#windows-graphics-cursor-desynchronization) are recorded for the deferred upstream change; no backend update is shipped yet. |
| `TODO(host/profile)` | Opt-in SSH shell helpers for terminal-environment forwarding and remote terminfo setup. These helpers are separate from a built-in SSH connector and must handle unavailable remote tools and rejected environment forwarding. |
| `TODO(host/profile)` | WSL-aware directory, JDK, and startup-command mapping. Existing explicit-shell hooks do not provide guest filesystem completion or remote process metadata; see [shell support](features/shells.md). |
| `TODO(host/profile)` | Dev Container launch contexts and container-aware host services; no built-in container-session integration exists. |

## Shell integration

| Owner | Remaining work |
| --- | --- |
| `TODO(host/profile)` | Automatic Nushell and Elvish integration for prompt/command markers and working-directory context. Nushell discovery and configurable shell executables do not install these hooks; see [shell support](features/shells.md). |
| `TODO(parser/session/input/ui/host/profile)` | Click-to-move within an editable shell prompt using OSC 133 `cl=line` and compatible `click-events` metadata. Existing command markers and completion edit authority do not implement pointer-driven cursor placement. |
| `TODO(host/profile)` | Configurable command-finished alerts based on shell metadata, duration, and focus state. Command-finished listeners and terminal-requested OSC notifications already exist; neither product turns completion of a long-running command into an automatic alert. |

<a id="input-module-gaps"></a>
<a id="deferred-kitty-keyboard-protocol-scope"></a>

## Input

| Owner | Remaining work |
| --- | --- |
| `TODO(host/profile)` | Native rich-input adapters with layout-aware key identity, IME text, and complete lifecycle metadata. Portable Swing must not advertise Kitty flags 2, 4, or 16 before these inputs are reliable. |
| `TODO(parser/core/input)` | Layout-aware modifyOtherKeys modifier masks, including `CSI > 4 : 1 m`. The parser rejects colon subparameters for this command; no corresponding negotiated mask is stored or applied by the encoder. |

## Rendering and interaction

| Owner | Remaining work |
| --- | --- |
| `TODO(ui)` | Programming-font ligatures across compatible LTR ASCII cells. Contextual script and RTL shaping already handle natural ligatures, but the ASCII rendering path deliberately bypasses contextual shaping. |
| `TODO(ui/host/profile)` | OpenType feature and variable-font axis controls. Existing primary/fallback font selection and host font resolution do not expose these settings. |
| `TODO(ui/host/profile)` | Formatted clipboard copy and output export in HTML or terminal-escape form. Current built-in clipboard and export paths produce plain text; selection and command-output extraction already exist. |
| `TODO(ui/host/profile)` | Optional background images, transparency/blur where the platform permits it, and custom visual effects. Current appearance controls cover colors, fonts, geometry, and decorations, without image backgrounds or shader effects. |

## Optional protocol work

| Owner | Remaining work |
| --- | --- |
| `TODO(parser/core/session/render/ui/policy)` | Kitty graphics extensions beyond the [implemented inline image subset](features/terminal.md#host-integration-and-queries): indexed wire formats (`f=8/16`), PNG profile/gamma color management, Unicode placeholders, animation, relative placements, file and shared-memory media. The [implementation contract](development/kitty-graphics-contract.md) defines existing resource and lifecycle policies. |
| `TODO(parser/core/render/ui/policy)` | Sixel decoding, bounded transfer/decompression, retained-image budgets, render contracts, and painting. |
| `TODO(parser/core/render/ui/policy)` | Kitty OSC 21 visual-bell and transparent-background color roles. These return `unknown` under the [implemented color protocol](features/terminal.md#appearance); presentation support is needed before advertising them. |
| `TODO(parser/core/policy)` | xterm color-stack commands XTPUSHCOLORS, XTPOPCOLORS, and XTREPORTCOLORS. Kitty OSC 30001/30101 save/restore already works. |
| `TODO(parser/session/ui/policy)` | Additional OSC 22 shapes and Kitty stack/query extensions beyond the [implemented subset](features/terminal.md#appearance). |
| `TODO(parser/session/ui/host/profile/policy)` | ConEmu progress reports (OSC 9;4), retained progress state, and product presentation with bounded expiry. These subcommands are currently excluded from notification handling; OSC 9/777 notifications already work. |
| `TODO(core/policy)` | Broader XTGETTCAP replies for concrete TUI capability discovery, backed by an authoritative capability table and explicit reply allowlist. Current replies cover terminal name, color count, and truecolor aliases only. |
| `TODO(parser/core/policy)` | XTVERSION terminal-version queries with a bounded, policy-controlled identity reply. Primary/secondary device attributes already work; version queries are a separate protocol. |
| `TODO(parser/core/policy)` | xterm XTCHECKSUM extensions beyond the implemented base VT420 checksum. |
| `TODO(parser)` | Additional ISO 2022 national replacement sets: UK, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish, Swedish, Swiss, and Portuguese. |
| `TODO(host/profile)` | Built-in desktop OSC 52 writes to primary/secondary selections and cut buffers, and reads from secondary selections and cut buffers. Library callbacks already expose selection-aware requests; custom hosts can implement these destinations. |

## Accepted limitations

- Terminal grids require at least two columns so wide occupants remain intact.
  Creation and resize reject smaller widths; pixel-based viewport layout clamps to two columns.
- Grapheme processing is always enabled (mode 2027 reports permanently set).
  There is no application-selectable legacy per-codepoint width mode. The
  [width policy](../ketraterm-core/docs/terminal-core-contract.md#unicode-scalar-and-width-policy)
  permits VS15 narrowing, unlike the original Unicode Core draft.
- Streaming grapheme updates do not roll back published wraps, scrolling,
  overwrites, or insert shifts. Placement can depend on chunk boundaries; see the
  [placement contract](reference/protocol.md#streaming-grapheme-placement)
  and [verification disposition](development/conformance-testing.md#streaming-placement).
- The streaming parser retains at most 32 codepoints per grapheme; further
  continuations affect segmentation but are not emitted. Direct core cluster
  calls reject prefixes longer than 32 codepoints before mutation.
- One session has one viewport. Independently scrolling views of one process
  and standalone reuse of the Swing renderer are outside the current API.
- Column spacing uses integer logical pixels; fractional cell advances are unavailable.
- Portable Swing exposes only the key metadata available through AWT.
- Highlight mouse tracking (mode 1001) is unsupported.
- Configurable ENQ answerback and ANSI keyboard-disable mode (KAM) are unsupported;
  enabling either would require an explicit compatibility and security policy.
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
