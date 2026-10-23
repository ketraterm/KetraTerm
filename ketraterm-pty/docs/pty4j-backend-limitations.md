# Pty4j backend limitations

Confirmed against `org.jetbrains.pty4j:pty4j:0.14.0` on 2026-10-22. This reference
records backend constraints and reproduction evidence; open work belongs in the
[gap map](../../docs/terminal-feature-gap-map.md#transports-and-execution-environments).

## Native pixel dimensions

Pty4j's `WinSize` stores character columns and rows only. Its four-argument
constructor accepts pixel width and height but discards both. Passing pixel
dimensions to that constructor does not propagate them to the child PTY.
This was verified in the pinned
[0.14.0 source artifact](https://repo1.maven.org/maven2/org/jetbrains/pty4j/pty4j/0.14.0/pty4j-0.14.0-sources.jar),
including `WinSize.kt` and the Unix native resize interface.

- Unix PTYs can carry pixels through `TIOCSWINSZ`. Pty4j's Unix native structure
  already has pixel fields, but its `WinSize` mapping leaves them zero on writes
  and discards them on reads. The public API and mapping must preserve these
  values before KetraTerm can use that path.
- Windows [ConPTY resize](https://learn.microsoft.com/en-us/windows/console/resizepseudoconsole)
  accepts character dimensions only. Updating pty4j's `WinSize` alone cannot add
  Windows pixel-size propagation.
- Windows/WSL additionally needs a path to carry pixels into the guest PTY;
  resizing the outer ConPTY does not supply guest `TIOCGWINSZ` pixel fields.

KetraTerm's in-band text-area and cell-size queries already work when the view
supplies geometry. Yazi can use these queries. The tested `kitten icat` instead
reads native PTY dimensions and needs
[explicit dimensions](../../tools/kitty-graphics/README.md#interactive-icat-on-a-pty-without-pixel-dimensions)
when native pixels are unavailable. Image decoding or renderer changes cannot
repair missing ioctl data.

## Windows graphics cursor desynchronization

The pinned pty4j bundle used in the native reproduction contains ConPTY and
OpenConsole **1.24.2607.10001**. This backend forwards Kitty graphics APC strings
without accounting for their cursor movement in its own console model.
KetraTerm advances its cursor according to Kitty semantics, while PowerShell's
console APIs can still return the earlier position. Resize or a subsequent
PSReadLine redraw can then place typed text above the visible prompt.

Microsoft tracks this exact defect in
[terminal#19926](https://github.com/microsoft/terminal/issues/19926).
[Fix #20009](https://github.com/microsoft/terminal/pull/20009) requests terminal
cursor position after unknown sequences and was serviced in ConPTY 1.25.
The installed Windows Terminal application's version does not replace pty4j's
bundled native backend. Falling back to the operating system's ConPTY is not
evidence that the fix is present there.

The controlled native reproduction used PowerShell 7.6.5 on Windows x86-64
build 10.0.26300.0, 8x20-pixel cells, a 160x80 PNG displayed at 20 columns,
an 80x30 grid widened to 140x30, and an
explicit `Console.SetCursorPosition` before writing `TYPED`. Phase handshakes
kept the child alive until output was consumed. Coordinates below are zero-based:

| Phase | Bundled 1.24: console / KetraTerm cursor row | Fixed 1.25: console / KetraTerm cursor row |
| --- | --- | --- |
| Image followed by `PS> ` | 3 / 6 | 6 / 6 |
| After widening | 2 / 5 | 5 / 5 |
| After absolute cursor positioning and `TYPED` | 2 / 2; prompt remains on row 5 | 5 / 5; text follows the prompt |

The comparison changed only the native backend, using Microsoft's stable
[ConPTY 1.25.260930003 package](https://www.nuget.org/packages/Microsoft.Windows.Console.ConPTY/1.25.260930003).
The downloaded package's SHA-256 was
`02b07b349af66d801159bdf9e440d4a1ce78bb951f37fc8609731665afdae7ee`.
Its matching `conpty.dll` and `OpenConsole.exe` were tested together through
pty4j's `pty4j.preferred.native.folder` override, with the other required pty4j
helpers present. These test binaries are not shipped by KetraTerm. The deferred
upstream change must update the backend pair, rather than changing Kitty cursor
semantics or parsing graphics in the PTY transport.

Cursor synchronization is not graphics-aware screen-buffer storage: the upstream
fix does not make ConPTY understand image pixels or guarantee complete buffer
consistency for every application. The table records the scenario actually tested.

## Distinguish terminal policy from backend limitations

Ordinary Kitty images keep physical cell anchors on width reflow, while text
rewraps. Collapsing a wrapped command can move a later prompt toward or under an
image. This is distinct from input jumping away from the prompt; see the pinned
[placement and resize contract](../../docs/development/kitty-graphics-contract.md#placement-and-screen-ownership).

Foreground-process names are also best-effort: Windows uses a descendant
heuristic, and applications inside WSL or SSH are not inspected. This is an
[adapter metadata constraint](pty4j-process-lifecycle.md#2-windows-conpty-considerations),
not an image-protocol defect.
