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
