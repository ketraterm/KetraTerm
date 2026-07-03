# KetraTerm IntelliJ Plugin Changelog

## [0.1.2] - 2026-07-03

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
