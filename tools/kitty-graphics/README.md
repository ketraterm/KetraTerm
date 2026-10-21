# Kitty graphics sender fixtures

`capture.py` executes serialization functions from immutable Yazi and Kitty
revisions to produce the binary corpus in
[`ketraterm-testkit/src/test/resources/kitty-graphics`](../../ketraterm-testkit/src/test/resources/kitty-graphics).
The manifest records the source revisions and SHA-256 digests, wire bytes,
command fields, payload lengths, dimensions, and independent input-pixel hashes.
The regular testkit suite verifies the committed corpus without these toolchains
or network access.

These are **producer-source harness captures**, not interactive application
recordings. They verify actual upstream serialization. They do not exercise
terminal detection, image-file decoding, UI placement selection, or filesystem
and shared-memory transfers in either application. Sender output is evidence of
interoperability requirements; protocol semantics remain governed by the protocol
and KetraTerm's documented contract.

## Reproduction

Use Python 3.12+, Go 1.27.3, Rust 1.94.0, and Node.js 22+ with WASI preview1.
Go is pinned because its zlib writer contributes captured bytes. The script
downloads checksum-verified upstream sources, Rust's matching WASI standard
library, and Yazi's Base64 dependency into the ignored
`build/kitty-graphics-stage1/` directory. It neither installs toolchains nor
changes global configuration. An offline repeat works with those downloads
cached.

From the repository root, with these executables on `PATH`:

```shell
python tools/kitty-graphics/capture.py
```

This compiles and runs both harnesses and compares every fixture and the manifest
byte for byte. It fails on any difference. Pass `--go`, `--rustc`, or `--node` to
select explicit executable paths. Windows Rust installations can use the bundled
`rust-lld.exe` with `--rust-linker`; no Visual Studio linker is needed because
the Rust harness targets WASI. The Node runner provides no filesystem preopens.

Replacing the corpus is an explicit operation:

```shell
python tools/kitty-graphics/capture.py --write
./gradlew :ketraterm-testkit:test --tests '*TerminalGraphicsFixtureTest'
```

Review source changes, harness changes, pixel expectations, and resulting wire
changes together. Do not replace fixtures merely to resolve a failed comparison.
The binary resources have Git text conversion disabled.

## Display a PNG directly from PowerShell

Run [Show-TerminalImage.ps1](Show-TerminalImage.ps1) from PowerShell **inside
KetraTerm**. From the repository root:

```powershell
.\tools\kitty-graphics\Show-TerminalImage.ps1 'C:\Pictures\example.png'
.\tools\kitty-graphics\Show-TerminalImage.ps1 'C:\Pictures\example.png' -Columns 25
```

Windows PowerShell 5.1 and PowerShell 7 are supported. No WSL, `kitten`, Python,
or additional PowerShell modules are needed to display an image. The width
defaults to 40 columns; choose a width that fits the viewport. KetraTerm derives
height from the image's aspect ratio and its own cell geometry, without native
PTY pixel-size queries.

The script accepts PNG files only and sends their bytes in 4096-character
Base64 chunks. Run it directly without piping or redirecting output. It checks
the PNG signature; KetraTerm validates the complete image and enforces its
graphics limits. Terminal replies are suppressed so they do not become shell
input; successful script completion is not an acknowledgement of display.

The optional sender check requires Python 3 and runs every available Windows
PowerShell/PowerShell 7 executable. It verifies exact framing, multi-chunk PNG
round-trips, literal paths, defaults, and rejected input without opening a UI:

```powershell
python .\tools\kitty-graphics\smoke\test_powershell.py
```

## Harness boundaries

The script extracts upstream function bodies verbatim from the checksum-pinned
files; it never substitutes a handwritten protocol serializer. The committed
Rust and Go files contain only the scaffolding into which those bodies are
inserted. Generated upstream source stays in the ignored build directory.

Yazi's harness executes `RequestKgp`, `RequestKgpShm`, `RequestDA1`, `MoveTo`,
`KgpOld`'s inline `output_b64`, `place`, and `image_erase`. It uses the same
`base64` 0.23.1 crate pinned by Yazi's `Cargo.lock`. Small host shims provide a
byte vector, rectangle bounds, stdout, fixed image ID 73, and a fixed shared-memory
name. The query name is serialized by upstream code; the harness opens no shared
memory. Passthrough constants select the ordinary terminal path, with no tmux envelope. The
`writef!` shim omits an immediate flush, which does not change captured bytes.
The probe fixture concatenates the two graphics requests and a DA1 barrier;
unrelated startup probes are deliberately absent.

Kitty's harness executes `GraphicsCommand.serialize_to`, its payload writers,
and their compression logic. Generated enum string methods are reconstructed
from the source's enum annotations. Go's standard `encoding/base64` replaces
Kitty's optimized Base64 package with the same `RawStdEncoding` semantics;
compression still uses upstream's own `compress/zlib` calls. The harness sets
fixed command values and IDs 101–103 and writes to stdout without tmux wrapping.
It does not run `kitten icat`'s argument parsing or select its image conversion
and placement policy.

The image inputs are synthetic and deterministic: xorshift32 bytes for chunked
RGB/RGBA, a solid RGB image to trigger upstream compression, and a 2×2 RGBA PNG
with opaque, translucent, and transparent pixels. Its fixed IDAT stream avoids
dependence on Python's zlib encoder version; the script verifies that it expands
to the independently specified input pixels. Pixel hashes are computed from those
inputs, independently of the captured output. The corpus intentionally
includes Yazi's 4096-byte payload chunks and padded Base64, Kitty's 131072-byte
payload chunks and unpadded Base64, inherited continuation controls, cropped
per-cell placements, and both placement-only and image-data deletion.

## Upstream attribution

The manifest links the exact upstream source and license revisions. Yazi source
is MIT licensed (copyright sxyazi); Kitty's writer is GPLv3 (copyright Kovid
Goyal). The generated Go source retains the upstream license header. Rust's
downloaded Base64 crate is MIT/Apache-2.0 licensed. No upstream source files or
toolchain binaries are vendored in this directory.

## Real application smoke tests

The opt-in [Java runner](smoke/AppSmoke.java) launches the
[Linux PTY relay](smoke/pty_bridge.py) and connects its raw bytes to a real
`TerminalSession`. It exercises Yazi's terminal detection, image-file loading,
protocol emission, session decoding, placement, and Yazi navigation/deletion.
It checks all pixels and the displayed extent of a generated 160×80 red PNG,
followed by a successful application exit. The session uses 8×20-pixel cells;
the complete image must occupy 20×4 cells, preserving its aspect ratio. The runner
waits for complete source coverage across Yazi's cropped placements before checking
extent or navigating away. Yazi deletion requires both the text-file preview
marker and zero placements in the same frame, rather than accepting a transient
empty frame. It uses KetraTerm's normal `xterm-256color`/`truecolor` identity; it does not
pretend to be Kitty. `kitten icat` explicitly selects stream transfer because
file/shared-memory transports are outside this slice.

Yazi's PTY intentionally reports zero pixel dimensions through `TIOCGWINSZ`, so
the application must discover cell geometry through `CSI 16 t`. This covers the
missing-ioctl-pixels case without assuming the common 1:2 cell aspect ratio.
The separate `icat` run supplies the actual 800×600 pixel size in the PTY: `icat`
reads `TIOCGWINSZ` and does not fall back to terminal size-report escape sequences.

These are real-process smoke checks, separate from deterministic CI tests. The
30-second event waits and bounded teardown waits (five seconds for graceful exit,
then five seconds after forced termination) are hang guards, never timing
assertions. Terminal-output capture is capped at 8 MiB and terminal replies/input at 64 KiB;
errors propagate to the runner. Closing the runner's input asks the relay to close
the PTY and reap its child, including after failed assertions. All generated
images, isolated Yazi configuration/cache, and byte captures belong under an
ignored `build/` directory. Use a fresh output directory for each verification.

Provide Python 3 on Linux, JDK 25, and explicit executable paths. Neither runner
downloads, installs, or modifies global configuration. The standalone Kitty
`kitten` executable is sufficient; the Kitty GUI is unnecessary. Build the runtime
classpath with `./gradlew :ketraterm-benchmarks:jmhJar`, then on Linux:

```sh
JMH_JAR=/absolute/path/to/ketraterm-benchmarks/build/libs/ketraterm-benchmarks-VERSION-jmh.jar
OUT="$PWD/build/graphics-smoke"
mkdir -p "$OUT/classes"
javac -d "$OUT/classes" -cp "$JMH_JAR" tools/kitty-graphics/smoke/AppSmoke.java
java -Xmx256m -cp "$OUT/classes:$JMH_JAR" AppSmoke yazi "$OUT/yazi" \
  python3 "$PWD/tools/kitty-graphics/smoke/pty_bridge.py" yazi /absolute/path/to/yazi "$OUT/yazi"
java -Xmx256m -cp "$OUT/classes:$JMH_JAR" AppSmoke kitten "$OUT/kitten" \
  python3 "$PWD/tools/kitty-graphics/smoke/pty_bridge.py" kitten /absolute/path/to/kitten "$OUT/kitten"
```

On Windows, compile/run with the Windows JDK and a semicolon-separated classpath.
Replace the relay command after the Java output-directory argument with
`wsl.exe -d Ubuntu -- python3 /mnt/c/.../tools/kitty-graphics/smoke/pty_bridge.py`
followed by the application name, Linux executable path, and Linux output path.
The Java output path is its corresponding Windows path. This exercises a Linux
PTY → WSL stdio → Windows JVM session, not Windows ConPTY or the standalone/IDE
window lifecycle. The runner reads render frames; Swing painting is covered by
its own deterministic tests and benchmarks, not a native-window visual assertion.

Verified on 2026-10-21 with Ubuntu WSL and Temurin 25.0.3 on Windows:

| Official release asset | SHA-256 from GitHub release asset metadata | Observed result |
| --- | --- | --- |
| [Yazi v26.9.1 Linux x86-64](https://github.com/sxyazi/yazi/releases/download/v26.9.1/yazi-x86_64-unknown-linux-gnu.zip) | `a02fe91d3304294048c681f010f1100856872a4e98ecf6927328e888d40a6ad2` | Zero ioctl pixels, cell-size query, exact pixels and displayed extent, cropped placements, removal after navigation to text, clean exit |
| [Kitty kitten v0.49.2 Linux amd64](https://github.com/kovidgoyal/kitty/releases/download/v0.49.2/kitten-linux-amd64) | `2a803b4d411f327cc41b7193cb529f6de06ae3b169a346b3060212bbfeafc8bc` | Actual `icat` file decoding, PNG transfer, exact pixels and displayed extent, one placement, clean exit |

The release binaries were checked against those upstream digests before execution.
These application versions are separate from the source revisions pinned in the
serializer corpus. Raw captures contain runtime IDs and are diagnostic output,
not replacement golden fixtures.

### Interactive `icat` on a PTY without pixel dimensions

Native PTY pixel dimensions can be unavailable even when terminal size-report
escape sequences work. `icat` then exits before sending an image because it
reads `TIOCGWINSZ` without that fallback. The [gap map](../../docs/terminal-feature-gap-map.md#transports-and-execution-environments)
records the pty4j and Windows/WSL backend limitations. The smoke relay's explicit
pixel size does not verify pixel-size propagation through the native pty4j/ConPTY
backend.

For a manual test, [`icat --use-window-size`](https://sw.kovidgoyal.net/kitty/kittens/icat/#cmdoption-icat-use-window-size)
accepts the actual grid columns, rows, text-area pixel width and pixel height:

```sh
kitten icat --transfer-mode=stream --stdin=no \
  --use-window-size COLS,ROWS,PIXEL_WIDTH,PIXEL_HEIGHT image.png
```

Replace all four values with the current terminal geometry; `CSI 18 t` and
`CSI 14 t` report the grid and text-area dimensions respectively. Do not substitute
the smoke fixture's dimensions for a differently sized interactive window.
