# Terminal Feature Map

This document catalogs every supported terminal feature, protocol, and capability in JvTerm.

For a detailed backlog of gaps and intentional non-goals, see the [Terminal Feature Gap Map](file:///c:/Users/gagik/IdeaProjects/JvTerm/docs/terminal-feature-gap-map.md).

---

## 1. Terminal Protocols & Control Sequences

- **Margin Physics**:
  - Vertical scroll margins (`DECSTBM` / `CSI Pt ; Pb r`).
  - Left/right scroll margins (`DECSLRM` / `CSI Pl ; Pr s`).
- **Screen & Line Erasures**:
  - Selective line erasing (`DECSEL` / `CSI Ps ? K`).
  - Selective display erasing (`DECSED` / `CSI Ps ? J`).
  - Character erasing (`ECH` / `CSI Ps X`).
- **Selective Erase Protection**: Protected cell marking (`DECSCA` / `CSI Ps " q`) stamps protection onto characters to shield them from normal line and display erasures.
- **Buffer Switching**: Instantly switches between Primary and Alternate screen buffers (modes `47`, `1047`, `1048`, `1049`). Alternate screen isolates cursor save slots, margins, and automatically homes and clears the screen on entry (for `1047`/`1049`).
- **Column Toggles**: Dynamic 80/132 column switching (`DECCOLM` / `CSI Ps space $ |`) with automatic viewport resizes, screen clearing, and margin/tab-stop resets.
- **Grid Editing**: Line Insert (`IL` / `CSI Ps L`), Line Delete (`DL` / `CSI Ps M`), Character Insert (`ICH` / `CSI Ps @`), and Character Delete (`DCH` / `CSI Ps P`) constrained within horizontal and vertical margins.
- **Tab Stop Control**:
  - Tab Set (`HTS` / `ESC H`).
  - Tab Clear (`TBC` mode 0 clears active stop; mode 3 clears all stops).
  - Horizontal Tab (`HT` / `\t`).
  - Cursor Horizontal Tab (`CHT` / `CSI Ps I`).
  - Cursor Backward Tab (`CBT` / `CSI Ps Z`).
- **Cursor Settings**:
  - Blinking and visibility controls (`?25`).
  - Cursor shape styling (`DECSCUSR` / `CSI Ps SP q`) supporting Blinking Block (`1`), Steady Block (`2`), Blinking Underline (`3`), Steady Underline (`4`), Blinking Bar (`5`), and Steady Bar (`6`).
- **Resets**: Hard Reset (`RIS` / `ESC c`) and Soft Reset (`DECSTR` / `CSI ! p`).
- **DEC Alignment Test**: Renders DEC alignment test character grid (`DECALN` / `ESC # 8`).
- **Window Manipulation**: Support for standard xterm window manipulation sequences (`CSI 1 t` de-minimize, `CSI 2 t` minimize, `CSI 3 ; x ; y t` move, `CSI 5 t` raise, `CSI 6 t` lower, `CSI 8 ; rows ; cols t` resize, `CSI 9 ; mode t` maximize/restore) gated by a secure user setting (`shell_request_window_manipulation`).
- **ISO 2022 Charsets**: G0-G3 designation sets (ASCII and DEC Special Graphics) with locking shifts (`SO`/`SI`) and single shifts (`SS2`/`SS3`).

---

## 2. Text Styling & Color (SGR)

- **Text Attributes**: Bold (`1`), Faint/Dim (`2`), Italic (`3`), Underline (`4`), Blink (`5`), Inverse (`7`), Conceal (`8`), Strikethrough (`9`), and Overline (`53`) graphic modifers.
- **Underline Styles**: Single (`4:1`), Double (`4:2`), Curly (`4:3`), Dotted (`4:4`), and Dashed (`4:5`) underline styles.
- **Palette & TrueColor**:
  - 8 standard and 8 bright SGR colors.
  - 256-indexed color maps (`SGR 38:5:Ps` / `48:5:Ps`).
  - 24-bit TrueColor RGB mapping (`SGR 38:2:r:g:b` / `48:2:r:g:b`), supporting both colon-separated and semicolon-separated parameter sequences.
  - Custom SGR underline colors (`SGR 58/59`).

---

## 3. Operating System Commands (OSC) & Device Control (DCS)

- **Title Management**: Window, icon, or combined title updates with push/pop stack operations (`CSI 22 t` / `CSI 23 t`).
- **OSC 8 Hyperlinks**: Inline hyperlink parsing (`OSC 8 ; id ; url ESC \`) with interactive Ctrl-click navigation and a bounded, double-indexed LRU eviction registry in the host.
- **OSC 7 Current Working Directory**: Absolute `file://` directory URIs are parsed from bounded OSC payloads, validated and length-limited by host policy, retained as thread-safe session metadata, forwarded through PTY callbacks, and snapshotted onto bounded OSC 133 command records. Workspace launch profiles emit percent-encoded OSC 7 reports before each prompt for supported interactive PowerShell/PowerShell Core, Bash/Git Bash, zsh, and fish shells. Invalid or oversized reports leave the last valid directory unchanged.
- **OSC 133 Shell Integration Markers**: FinalTerm/iTerm-compatible shell lifecycle marker events (`OSC 133 ; A/B/C/D ST`) are parsed and forwarded through host, PTY, and workspace event boundaries. Core exposes stable primitive render-line identities that move with content through scroll, clear-history, and resize reflow; session-owned shell integration state stores a bounded primitive command timeline keyed by those identities and projects prompt starts, failed-command output, command boundaries, stable command record ids, and primitive lifecycle states into caller-owned viewport arrays. Swing paints exactly one compact gutter dot for each projected prompt: neutral normally and failure-colored when that command exits non-zero. Failed output retains the original failure-colored vertical gutter rail; successful and running commands draw no rail. These decorations preserve fixed terminal row pitch, add no separators, and are suppressed in alternate-screen applications. Session metadata captures bounded command text when a command-start marker can be safely related to a preceding prompt-end marker on the same line or immediately previous line; ambiguous, complex, or oversized command text remains unknown instead of being guessed. Swing exposes host-bindable previous/next command navigation actions that reveal the command prompt/start line through the existing scrollback viewport model, plus command hit-testing and command-output selection for retained command records. Workspace launch profiles inject idempotent startup hooks for supported interactive PowerShell/PowerShell Core, Bash/Git Bash, zsh, and fish profiles, so current directories, prompts, and command exit statuses emit OSC 7/133 metadata automatically. Persistent command history, multiline command extraction, prompt click actions, full command output copy/export, other rich command metadata, and WSL-specific shell bootstrap handling remain separate follow-up features.
- **OSC Palette Queries**: Dynamic color palette queries and updates (`OSC 4 / 10 / 11 / 12`), allowing applications to query standard palette colors or default foreground/background/cursor colors.
- **Desktop Notifications**:
  - **iTerm2 Style (`OSC 9`)**: Triggers a notification body payload (`OSC 9 ; message ST` or `BEL`).
  - **urxvt Style (`OSC 777`)**: Triggers a notification with separate title and body (`OSC 777 ; notify ; title ; body ST` or `BEL`).
  - **Extended Severity Protocol**: Optional fourth parameter (`OSC 777 ; notify ; title ; body ; level ST`) accepting `info` (standard info icon), `warning` (warning icon), `error` (error icon), or `none` (renders the custom JVTerm `>` symbol instead of OS defaults).
  - **Operational Policies**: Explicitly filters ConEmu conflicting subcommands (skips digits `0-4`, `9` after `OSC 9;`). Protects the system tray by reusing a single `TrayIcon` instance on the EDT with a self-cleaning **10-second** inactivity cleanup timer. Clamps parameters to a maximum title length of 256 characters and body of 1024 characters.
- **Capability Queries (DCS)**:
  - **DECRQSS**: Queries active SGR attributes (`m`), top/bottom scroll regions (`r`), left/right margins (`s`), and cursor shape configurations (`q`).
  - **XTGETTCAP / XTSETTCAP**: Queries color capabilities (`Co`/`colors` returning `256`), terminal name (`TN`/`name` returning `xterm-256color`), and TrueColor support (`RGB`/`Tc` returning boolean success). Responses are strictly checked against a security allowlist.

---

## 4. Query-Response Channels

- **Operating Status**: Responds operating status `CSI 0 n` on `DSR 5`.
- **Cursor Position Reports**: Responds active coordinate position on `CPR` / `DSR 6`.
- **Device Attributes**: VT100-compatible primary attribute response (`DA`) and generic secondary attribute response (`DA2`).
- **Window Size/State Reporting**: Responds window minimized/normal state (`CSI 11 t`), screen size in cells (`CSI 19 t`), grid dimensions in characters (`CSI 18 t`), or pixel dimensions (`CSI 14 t`).

---

## 5. Text & Unicode Engine

- **Unicode 17.0.0 Compliance**: Implements UAX #29 boundary segmentation for multi-scalar grapheme clusters and emoji presentation states.
- **Combining Marks**: Full support for zero-width extenders, including Thai and Lao combining characters.
- **East Asian Width**: Dynamic width calculations supporting wide, narrow, and East Asian Ambiguous width modes.
- **Live Grapheme Rendering**: Progressive rendering of printable prefixes without cursor movement for combining marks or ZWJ extensions.

---

## 6. Input Encoding & Event Reporting

- **Keyboard Mappings**: Support for physical keys, printables, Ctrl/Alt modifier combinations, keypad modes, and PF1-PF4 mappings.
- **xterm Key Enhancements**: modifyOtherKeys modes 1 and 2, and compact CSI-u layout (`formatOtherKeys=1`).
- **Kitty Keyboard Protocol**: High-fidelity key encoding supporting progressive flags:
  - Disambiguate escape codes (`1`).
  - Report event types (Press, Release, Repeat) (`2`).
  - Report alternate keys (`4`).
  - Report all keys as escape codes (`8`).
  - Report associated text (`16`).
  - Supports flag application modes: Replace (`1`), Set (`2`), and Clear (`3`), with a bounded mode push/pop stack.
- **Bracketed Paste**: paste payload wrapping (`CSI 200~` / `CSI 201~`) with configurable sanitization (stripping C0 controls and normalising line endings).
- **Focus Reporting**: Focus in/out sequences (`CSI I` / `CSI O`).
- **Mouse Protocols**: SGR mouse tracking (`?1006`), legacy tracking (`ESC [ M` coordinate-clamped to one-based limit 223), UTF-8 mouse (`?1005`), URXVT mouse (`?1015`), and high-precision SGR-Pixels (`?1016`).

---

## 7. Embedding & Swing UI

- **PTY Process Integration**: Spawns default platform shells using Pty4J with Windows ConPTY support and prompt resizing.
- **High-Performance Painting**: Decoupled, multi-threaded rendering using triple-buffering to achieve 60+ FPS paint cycles with zero tearing under heavy stdout throughput.
- **Font Fallbacks**: Scan and fall back to native system fonts for color emojis and complex visual shapes.
- **Windows Emoji Rasterizer**: Segoe UI Emoji COLR/CPAL color glyph painting.
- **Interactive Selection**: Drag selecting with velocity autoscrolling, Alt-drag block selection, double-click smart selection (words, paths, URLs), triple-click line selection, middle-click paste, and system clipboard interfaces.
- **Scrollback Search**: Regex-free text search scanning viewport and retained scrollback history, ignoring soft-wrapping boundaries.
- **Fixed-Grid Swing Viewport**: One Swing-owned geometry model maps terminal rows, fractional scroll offsets, hit testing, repaint bounds, command-navigation anchors, and terminal-pixel mouse coordinates while preserving exact fixed row pitch. Shell-integration prompt dots are zero-layout decorations and never change visible row count, scroll range, mouse coordinates, or PTY dimensions.
- **Audible Bell**: Alerts embedding host of beep signals (`BEL`), configurable via settings.
