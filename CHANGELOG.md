# KetraTerm Changelog

## [0.1.3] - 2026-07-08

- Moved search chrome and shortcut policy into host-owned action wiring.
- Added shared Swing host utilities for floating search UI and default host shortcuts.
- Added host key handling so shortcuts can be handled or passed through to the PTY.
- Added host-owned terminal context menus with mouse-reporting-aware right-click routing.
- Added shared context-menu actions for copy, paste, select all, search, link handling, and clear-screen requests.
- Changed clear screen to send Ctrl+L to the foreground process instead of mutating terminal state behind the PTY.
- Expanded search and shortcut coverage.

## [0.1.2] - 2026-07-03

- Added terminal process close confirmation and enhance command lifecycle tracking.
- Added render-cache-bounded IDE hyperlink overlays so host-discovered links can be highlighted and activated without scanning during paint, hover, parser, or core hot paths.
- Improved hyperlink rendering with a cleaner solid underline and immediate preservation of discovered link highlights while scrolling through cached rows.
- Added a `scrollOnOutput` configuration policy and UI settings toggle to lock viewport scroll position or snap to bottom when new process output arrives.
- Added host-provided terminal font fallback resolution through `TerminalFontResolver`, including IntelliJ integration backed by `ComplementaryFontsRegistry`.
- Added bounded script-run shaping for complex Swing text rendering so Arabic, Indic, and connected-script runs are shaped with full run context while the ASCII path stays fast.
- Replaced eager system fallback font retention with a bounded `SystemFontLru` cache to reduce AWT native font memory pressure.
- Overhauled `TerminalSelectionController` to copy multi-line selection text across the entire terminal history by fetching custom render frames instead of clamping to the active visible viewport.
- Fixed first-run TUI wrapping corruption by drawing the scrollback scrollbar inside a reserved terminal gutter instead of letting scrollbar visibility change the available terminal width.

## [0.1.1] - 2026-07-02

- Fixed AltGr character input and terminal grid corruption on AZERTY keyboards for Windows/Linux.

## [0.1.0] - 2026-06-30

- First public KetraTerm standalone desktop application release.
- Added a native desktop window hosting the KetraTerm terminal component.
- Added support for multiple terminal tabs with custom titles.
- Added keyboard shortcuts for tab navigation (Ctrl+T for new tab, Ctrl+W to close tab, etc.).
- Integrated local pseudo-terminal (PTY) transport for zsh, zli/zsh, bash, sh, PowerShell, and cmd.exe.
- Configurable typography, default colors, palettes, and scrollback capacity.
