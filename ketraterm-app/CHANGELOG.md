# KetraTerm Standalone Changelog

## [Unreleased]

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
- Improved compatibility with apps that switch terminal width, while respecting resize settings and protecting split layouts.
- Fixed cursor and scroll-position jumps when resizing terminals with a full history.
- Fixed terminal links opening the wrong destination after a reset.
- Compatible terminal apps can now match the terminal's light or dark theme.
- Added a startup command setting for new terminal tabs and splits.
- Fixed missing spaces and line breaks when copying or exporting terminal text, including after resizing.
- Improved compatibility with custom shell startup files and commands.
- Improved link underlines and kept hover highlights consistent across wrapped lines and output updates.

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
- Fixed prompt gutter clicks selecting the wrong text under certain circumstances.

## [0.1.3] - 2026-07-08

- Improved focused-terminal shortcuts for copy, paste, search, and page scrolling.
- Added a compact floating search bar with result navigation and match-case control.
- Added a terminal context menu that respects TUI mouse tracking.
- Improved macOS shortcut behavior with standard Cmd+C, Cmd+V, and Cmd+F bindings.

## [0.1.2] - 2026-07-03

- Added terminal process close confirmation.
- Added a "Scroll on output" setting to keep the viewport in place or follow new output.
- Fixed selection copying to support text spanning the entire terminal scrollback history.
- Fixed first-run TUI wrapping corruption.

## [0.1.1] - 2026-07-02

- Fixed AltGr character input and terminal grid corruption on AZERTY keyboards for Windows/Linux.

## [0.1.0] - 2026-06-30

- First public KetraTerm standalone desktop application release.
- Added a native desktop window with multiple terminal tabs, custom titles, and tab-management shortcuts.
- Added local terminal sessions for zsh, bash, sh, PowerShell, and cmd.exe.
- Added configurable typography, default colors, palettes, and scrollback capacity.
