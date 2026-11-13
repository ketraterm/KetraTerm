# KetraTerm Standalone Changelog

Release notes for the KetraTerm desktop application.
For emulator APIs and embedding changes, see the [library changelog](../CHANGELOG.md).

Released versions: [0.5.0](#050---2026-11-09) · [0.4.0](#040---2026-10-31) ·
[0.3.0](#030---2026-09-23) · [0.2.2](#022---2026-09-11) · [0.2.1](#021---2026-07-14) ·
[0.2.0](#020---2026-07-13) · [0.1.3](#013---2026-07-08) · [0.1.2](#012---2026-07-03) ·
[0.1.1](#011---2026-07-02) · [0.1.0](#010---2026-06-30)

## [Unreleased]

### Suggestions

- Added opt-in inline completion previews, including when suggestion popups are disabled.
  Existing history-inline preferences migrate automatically.
- Popups show alternatives to the inline preview without briefly duplicating it.
- Right Arrow accepts an inline preview. After browsing the popup, Tab or Enter inserts the
  selected suggestion without executing it; passive popups preserve shell Tab/Enter behavior.
- Added a Suggestions settings page for inline previews, popup mode, learning, and customizable
  shortcuts. Press keys to record a shortcut; click its chip to remove it.
- PowerShell suggestions stay responsive while native inline predictions are visible.
- Ctrl+Space passes through to the running application when shell completion cannot start.

### Keyboard, selection, and navigation

- Added temporary font zoom and reset for each pane, Windows Ctrl+Shift+C/V clipboard shortcuts,
  and window-scoped tab and pane shortcuts.
- History paging keeps one row of context. Shift+Home/End jumps to oldest history or live output.
- Double- and triple-click dragging selects whole words or rows. Word selection preserves wide
  characters and keeps neighboring paths out of whitespace selections.

### Startup and session recovery

- New configurations use 100 × 28 cells and 10,000 scrollback lines. The initial window fits the
  available desktop; saved preferences remain effective.
- Linux and other non-Windows/macOS platforms choose an installed monospace font, with
  `Monospaced` as the fallback.
- Fresh Windows configurations prefer modern PowerShell when installed, with Windows PowerShell
  as fallback. Saved shell choices remain effective.
- Failed sessions retain their output with an exit-status banner and Close button, including
  transport failures and unknown exit statuses. Successful exits close normally.

### Security and notifications

- Install the companion CLI in a private temporary directory instead of trusting
  a predictable shared temporary path.
- Pastes containing line breaks or control characters show an escaped preview for review,
  with Cancel selected by default.
- Invalid clipboard or title permissions deny access and identify the affected setting.
  Configuration recovery also denies these permissions.
- Bell feedback is limited to once every 500 ms per window.

## [0.5.0] - 2026-11-09

### Suggestions

- Expanded experimental suggestions with PowerShell completions, Bash/zsh command discovery,
  command specifications, local paths, and Git references. Suggestions are enabled by default;
  saved learning is opt-in. See [shell requirements](../docs/features/completion.md).
- Added match highlighting, argument hints, and settings for automatic popups and Enter acceptance.
  PowerShell prediction redraws preserve suggestions; changed input invalidates acceptance.

### Appearance and navigation

- Improved shell icon contrast and proportions, including a clearer Command Prompt icon.
- Added sticky command headers, enabled by default. Click a header to return to its command;
  disable the overlay independently in settings.
- Typing and pasting return immediately to live input. Scroll on output now defaults to off;
  saved preferences remain effective.

## [0.4.0] - 2026-10-31

### Terminal features

- Compatible applications can show progress rings in shell icons and window/taskbar status where
  supported. Progress works independently of titles and clears after five minutes without updates.
- Added Kitty image previews for RGB/RGBA and PNG, including compression, cropping, and scaling.
- Compatible applications can change the mouse pointer shape.

### Search

- Added whole-word and regular-expression search, with feedback for invalid patterns.
- Search stays responsive in large histories, updates results during scanning, and starts at the
  viewport. Queries with many matches show `1,000+`; navigation still reaches all retained matches.
- Search controls fit narrow panes, follow the theme, and show shortcut hints and keyboard focus.
  Alt+C/W/R toggles case, whole words, and regex; Enter/Shift+Enter navigates; Escape closes search.
- Fixed highlight remnants after changing a selected match to a query with no results.

### Appearance and layout

- Added Gutter, Divider, and None prompt styles. Integrated shells reserve their gutter at startup.
- Centered full-screen terminal applications consistently across shell profiles.
- Unfocused terminals keep visible cursors: outlined blocks or steady bars and underlines.
- Improved link highlighting across wrapped lines, scrolling, and output updates.
- Fixed layout errors after prompt-style changes, window moves, and resizing near desktop edges.

### Suggestions and notifications

- Stale suggestions no longer affect newer input. Closing a terminal or disabling suggestions
  closes its popup.
- Added opt-in alerts for long-running commands that finish in an unfocused terminal,
  with a configurable duration threshold of 10 seconds by default.

### Security and permissions

- Compatible applications can read clipboard text with consent. Dialogs offer one-time approval,
  denial, or blocking for the terminal; new settings ask before reading.
- Clipboard and title permissions now apply throughout a session, including SSH. Clipboard choices
  are Deny, Ask, and Allow; writes default to Allow. Previous local/remote settings reset to defaults.
- Improved paste protection and simplified paste settings.
- Unsupported status queries can no longer inject supplied text or control characters into input.

### Compatibility and reliability

- Improved cursor-position, cursor-style, modifier-key, and terminal-capability query compatibility.
- Fixed column switching when window resizing is disabled or the layout cannot resize.
- Improved large-paste reliability and prevented premature writes during terminal startup.
  Input settings can change safely during a paste.
- Improved combined text formatting and rejected oversized commands safely.
- Improved responsiveness during large output and window-state reporting while output is active.

## [0.3.0] - 2026-09-23

### Shells and startup

- Tabs can show the running application's name when no title is supplied. Custom names take
  priority, and automatic process titles can be disabled.
- Added startup commands for new tabs and splits, with improved custom shell startup compatibility.

### Scrolling, selection, and links

- Fixed scrolling in split layouts disturbing neighboring content and slow trackpad gestures
  being ignored in full-screen applications when mouse mode is off.
- Fixed cursor and scroll-position jumps when resizing with a full history.
- Fixed missing spaces and line breaks when copying or exporting text after resizing.
- Fixed links opening the wrong destination after reset and improved wrapped-link highlighting.
- Compatible applications can copy up to 1 MiB to the clipboard, subject to permissions.

### Terminal compatibility

- Fixed cursor shape and blinking after leaving full-screen applications such as Neovim.
- Improved cursor save/restore and terminal-width switching while respecting resize settings
  and protecting split layouts.
- Compatible applications can detect the terminal's light or dark theme.

## [0.2.2] - 2026-09-11

### Unicode and text rendering

- Aligned Hebrew, Arabic, and mixed-direction text with backgrounds, cursors, selections,
  search highlights, and mouse targets.
- Fixed Arabic shaping under block cursors, missing text in long shaped lines, and unexpected
  font changes around joiners, particularly on macOS.
- Preserved native emoji and solid block graphics on lines containing right-to-left text.

### Selection and display fixes

- Concealed text stays hidden under cursors and link highlights; neighboring glyphs stay clipped.
- Fixed rectangular selections widening across mixed-direction or partially hidden rows.
- Switching sessions clears the previous session's text and search results.
- Fixed blinking text, cursor visibility, and reverse-video updates in scrollback.
- Fixed smooth-scroll gaps and jumps during loading, resizing, and screen switches.

## [0.2.1] - 2026-07-14

### Input and compatibility

- Fixed held Backspace and Delete keys deleting only one character.
- Improved Ctrl-number shortcuts, modified keys, extended function keys, and Backspace behavior.
- Improved capability reporting and compatibility with applications that verify screen regions.

## [0.2.0] - 2026-07-13

### Clipboard and selection

- Fixed multiline paste and intermittent copies of incorrect or stale text.
- Improved copying of scrollback selections, wrapped lines, and trailing empty cells.
- Fixed prompt gutter clicks selecting the wrong text.

### Scrolling

- Restored historical output scrolling in terminal applications using scroll regions.

## [0.1.3] - 2026-07-08

### Search and shortcuts

- Added a floating search bar with result navigation and case-sensitive matching.
- Improved copy, paste, search, and scrolling shortcuts, including standard macOS Cmd bindings.
- Added a context menu that respects terminal mouse tracking.

## [0.1.2] - 2026-07-03

### Settings and fixes

- Added process close confirmation and a Scroll on output setting.
- Fixed copying selections across the entire scrollback history and first-run TUI wrapping.

## [0.1.1] - 2026-07-02

### Input fixes

- Fixed AltGr input and grid corruption on AZERTY keyboards for Windows/Linux.

## [0.1.0] - 2026-06-30

### Initial release

- First public standalone desktop release, with tabs, custom titles, and tab shortcuts.
- Added local sessions for zsh, bash, sh, PowerShell, and cmd.exe.
- Added configurable typography, colors, palettes, and scrollback capacity.
