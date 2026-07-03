# KetraTerm Standalone App Changelog

## [Unreleased]

- Added a `scrollOnOutput` configuration policy and UI settings toggle to lock viewport scroll position or snap to bottom when new process output arrives.
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
