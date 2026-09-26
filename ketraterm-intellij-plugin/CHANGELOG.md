# KetraTerm IntelliJ Plugin Changelog

## [Unreleased]

- Improved responsiveness and reliability when pasting large text into busy terminals.
- Improved validation of terminal clipboard access requests.
- Improved keyboard compatibility with terminal apps that configure and query modifier handling.
- Improved compatibility with terminal apps that query active terminal settings and supported features. Input settings can be changed safely during large pastes.
- Simplified clipboard and title permissions so they apply consistently throughout a terminal, including SSH sessions. Clipboard choices are now Deny, Ask, and Allow, with writes allowed and reads blocked by default. Previous separate local/remote settings reset to the new defaults.
- Improved protection when pasting copied terminal sequences and simplified paste handling settings.
- Improved compatibility with combined text formatting commands and prevented oversized terminal commands from changing formatting or terminal settings unexpectedly.

## [0.3.0] - 2026-09-23

- Fixed scrolling in split terminal layouts disturbing neighboring content.
- Fixed slow trackpad movements being ignored in full-screen terminal apps when mouse mode is off.
- Terminal apps can now copy larger selections to the clipboard, up to 1 MiB, while respecting clipboard permissions.
- Fixed cursor shape and blinking not returning after leaving full-screen terminal apps such as Neovim.
- Tabs can now show the running application's name when it does not supply a title. Custom names keep priority, and automatic process titles can be turned off in settings.
- Improved cursor positioning compatibility with terminal apps that save and restore the cursor.
- Fixed cursor and scroll-position jumps when resizing terminal panels with a full history.
- Fixed terminal links opening the wrong destination after a reset.
- Compatible terminal apps can now match the terminal's light or dark theme, including IDE themes.
- Added a project-specific startup command setting for new terminals.
- Fixed missing spaces and line breaks when copying terminal text, including after resizing the terminal panel.
- Terminal tabs now reopen with their names and working directories after an IDE restart.
- Added "Rename Tab" to the terminal context menu.
- Added "Open in KetraTerm" to project and editor context menus.
- New terminals now use your project's JDK automatically, with an option to turn this off.
- Improved link underlines and highlighting across wrapped lines.
- Fixed delayed or flickering links while scrolling and during build output.

## [0.2.2] - 2026-09-11

- Fixed alignment of Hebrew, Arabic, and mixed-direction text with cell backgrounds, the cursor, selection and search highlights, and mouse targets.
- Fixed Arabic letter forms changing under the block cursor and missing text in very long lines that require contextual shaping.
- Fixed Arabic text containing joiners unexpectedly switching fonts, particularly on macOS.
- Preserved native emoji rendering and solid block graphics on lines containing right-to-left text.
- Fixed concealed text and emoji becoming visible under the block cursor or when hovering over links. Prevented neighboring characters from spilling into concealed cells.
- Fixed rectangular selections widening across mixed-direction lines or when part of the selection scrolls out of view.
- Fixed a terminal view retaining the previous session's text or search results when switching to a new session.
- Fixed blinking text and the cursor remaining hidden after unrelated output updates.
- Fixed inverted colors in scrollback failing to update when reverse-video mode changes or resets.
- Fixed gaps during smooth scrolling while new content is loading, scroll-position jumps during resizing, and stale scroll animations after screen switches.

## [0.2.1] - 2026-07-14

- Fixed holding Backspace or Delete deleting only one character.
- Improved compatibility for Ctrl-number shortcuts, modified keys, extended function keys, and applications that configure Backspace behavior.
- Reported terminal capabilities more accurately to shells and terminal applications.
- Improved compatibility with full-screen terminal applications that verify rectangular screen regions.

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
