# KetraTerm IntelliJ Plugin Changelog

## [0.2.0] - 2026-07-13

- Fixed multiline paste.
- Fixed terminal selection copying that could intermittently copy the wrong text or leave the previous clipboard contents unchanged.
- Improved copying of scrollback selections, wrapped lines, and trailing empty cells.
- Fixed scrollback behavior in terminal apps (including TUIs like codex logs) so historical output is scrollable again.
- Fixed prompt gutter clicks wrong selection under certain circumstances.

## [0.1.3] - 2026-07-08

- Improved focused-terminal shortcuts for copy, paste, search, and page scrolling.
- Added an "Override IDE shortcuts in focused terminal" setting for Ctrl+F search behavior.
- Added a compact floating search bar with result navigation and match-case control.
- Added an IDE-native terminal context menu that respects TUI mouse tracking.
- Improved macOS shortcut behavior with standard Cmd+C, Cmd+V, and Cmd+F bindings.

## [0.1.2] - 2026-07-03

- Added terminal process close confirmation dialog.
- Added IDE-discovered terminal links, so plain URLs and IDE-recognized file locations in output can be highlighted and opened from KetraTerm.
- Added option to toggle "Scroll on output" under terminal behavior settings, allowing users to lock their scroll position while background tasks compile or output text.
- Fixed selection copying to support selecting and copying text spanning across the entire terminal scrollback history.
- Fixed first-run TUI wrapping corruption.

## [0.1.1] - 2026-07-02

- Fixed AltGr character input and terminal grid corruption on AZERTY keyboards for Windows/Linux.
- Fixed terminal tool window focus issue where clicking on the terminal component (including Vim/Neovim mouse tracking and hyperlinks) failed to transfer focus back from the editor.
- Fixed Settings dialog EDT blocking exception when opening settings from the tool window gear icon.

## [0.1.0] - 2026-06-30

- First public KetraTerm release for IntelliJ Platform IDEs.
- Added a KetraTerm tool window with local terminal tabs and quick actions for
  common shell profiles.
- Added project-aware terminal startup defaults, configurable shell path,
  working directory, environment variables, and tab naming.
- Added IDE-native settings for font, color scheme, cursor, scrollback, paste
  handling, visual bell, middle-click paste, and shell suggestions.
- Added guarded clipboard and title handling for terminal escape sequences so
  sensitive IDE actions stay under the user-configurable policy.
- Added IDE notifications from supported terminal notification sequences.
