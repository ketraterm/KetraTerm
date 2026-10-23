#!/usr/bin/env python3
"""Compile pinned upstream serializers and verify (or explicitly replace) fixtures.

Requires Python 3.12+, Go 1.27.3, Rust 1.94.0, and Node.js with WASI preview1.
Downloaded source and build products stay in the ignored root build directory.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tarfile
import urllib.request
import zlib

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
WORK = ROOT / "build/kitty-graphics-stage1"
CORPUS = ROOT / "ketraterm-testkit/src/test/resources/kitty-graphics"
YAZI = "7c45837d553b6cb8032bd7fec2106fdb628e4017"
KITTY = "1c5a4829ae4dd472017f592b1424381d5ece7575"
SOURCES = {
    "yazi-Cargo.lock": (
        "sxyazi/yazi",
        YAZI,
        "Cargo.lock",
        "49a59068462ba7066a89ac29b26d9f7820301657bdebffaaeb1e3807c7d62fd7",
    ),
    "yazi-query.rs": (
        "sxyazi/yazi",
        YAZI,
        "yazi-tty/src/sequence/query.rs",
        "f2bf5a8ceaeb00b2ab9e4c01ca659cdf0586129a79cc6bbe1e521b7993c37a7a",
    ),
    "yazi-cursor.rs": (
        "sxyazi/yazi",
        YAZI,
        "yazi-tty/src/sequence/cursor.rs",
        "60fb4a50d0724a2c5dbed7a7065cbb71d398a2026ee7d578b266be44e0e06047",
    ),
    "yazi-kgp-old.rs": (
        "sxyazi/yazi",
        YAZI,
        "yazi-adapter/src/drivers/kgp_old.rs",
        "55bb377376b0d110b5a22e1015f0ff9010df689ad173cd2e174f61d6010b00fd",
    ),
    "kitty-command.go": (
        "kovidgoyal/kitty",
        KITTY,
        "tools/tui/graphics/command.go",
        "bdd58a2559032472d48a433f87b604399736e9ed017ed1b176619ebe7d56faf2",
    ),
}
RUST_STD_URL = "https://static.rust-lang.org/dist/rust-std-1.94.0-wasm32-wasip1.tar.xz"
RUST_STD_SHA = "acbacacdca3e0ec0d93ff46d5f3579ae8cd5133b86e31a893da22cd94e28d0bf"
BASE64_VERSION = "0.23.1"
BASE64_URL = f"https://static.crates.io/crates/base64/base64-{BASE64_VERSION}.crate"
BASE64_SHA = "ac07cdecf99051d9a5238b80f35af32cdeba5b336e55d957b318b50137e18da5"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fetch(name, url, expected):
    path = WORK / name
    if not path.exists():
        request = urllib.request.Request(
            url, headers={"User-Agent": "KetraTerm-fixture-capture"}
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
        if sha(data) != expected:
            raise RuntimeError(f"Downloaded checksum mismatch: {url}")
        path.write_bytes(data)
    if sha(path.read_bytes()) != expected:
        raise RuntimeError(f"Cached checksum mismatch: {path}")
    return path


def between(source, start, end):
    return source[source.index(start) : source.index(end)]


def function(source, signature):
    """Extract a pinned function verbatim, including its original indentation."""
    start = source.index(signature)
    # Braces inside these pinned bodies' format strings occur in balanced pairs.
    depth = 0
    for pos in range(source.index("{", start), len(source)):
        depth += (source[pos] == "{") - (source[pos] == "}")
        if depth == 0:
            return source[start : pos + 1]
    raise RuntimeError(f"Unclosed source function: {signature}")


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def extract_archive(path, destination):
    with tarfile.open(path) as archive:
        archive.extractall(destination, filter="data")


def compile_yazi(args, sources):
    std_archive = fetch("rust-std.tar.xz", RUST_STD_URL, RUST_STD_SHA)
    base64_archive = fetch(f"base64-{BASE64_VERSION}.crate", BASE64_URL, BASE64_SHA)
    sysroot = WORK / "rust-sysroot"
    target_lib = sysroot / "lib/rustlib/wasm32-wasip1/lib"
    if not target_lib.exists():
        extract_archive(std_archive, WORK / "rust-component")
        component = (
            WORK
            / "rust-component/rust-std-1.94.0-wasm32-wasip1/rust-std-wasm32-wasip1/lib"
        )
        shutil.copytree(component, sysroot / "lib", dirs_exist_ok=True)
    base64_source = WORK / f"base64-{BASE64_VERSION}/src/lib.rs"
    if not base64_source.exists():
        extract_archive(base64_archive, WORK)
    compiler = [
        args.rustc,
        "--target", "wasm32-wasip1",
        "--sysroot", sysroot,
        "--edition=2021",
    ]
    if args.rust_linker:
        compiler.extend(["-C", f"linker={args.rust_linker}"])
    run(
        compiler
        + [
            "--crate-name", "base64",
            "--crate-type=rlib",
            "--cap-lints=allow",
            '--cfg=feature="alloc"',
            '--cfg=feature="std"',
            base64_source,
            "-o", WORK / "libbase64.rlib",
        ]
    )
    query = sources["yazi-query.rs"]
    kgp = sources["yazi-kgp-old.rs"]
    harness = (HERE / "yazi-harness.rs").read_text(encoding="utf-8")
    harness = harness.replace(
        "// UPSTREAM_QUERY",
        between(query, "pub struct RequestDA1", "/// Request cursor style"),
    )
    harness = harness.replace(
        "// UPSTREAM_CURSOR",
        between(sources["yazi-cursor.rs"], "pub struct MoveTo", "/// Show cursor"),
    )
    harness = harness.replace("// UPSTREAM_OUTPUT", function(kgp, "fn output_b64("))
    harness = harness.replace("// UPSTREAM_PLACE", function(kgp, "fn place("))
    harness = harness.replace(
        "// UPSTREAM_DELETE", function(kgp, "pub(super) fn image_erase(")
    )
    source = WORK / "yazi-capture.rs"
    source.write_text(harness, encoding="utf-8", newline="\n")
    run(
        compiler
        + [
            "--extern", f"base64={WORK / 'libbase64.rlib'}",
            source,
            "-o", WORK / "yazi-capture.wasm",
        ]
    )
    return [
        args.node,
        "--disable-warning=ExperimentalWarning",
        HERE / "run-wasi.mjs",
        WORK / "yazi-capture.wasm",
    ]


def enum_strings(source):
    """Supply upstream's generated enum String methods from its enum annotations."""
    methods = []
    enums = source[: source.index("type GraphicsCommand struct")]
    for match in re.finditer(r"type (\w+) int[^\n]*\n\s*const \((.*?)\n\)", enums, re.S):
        name, body = match.groups()
        values = []
        for line in body.splitlines():
            if "//" in line:
                _, value = line.split("//", 1)
                values.append(value.strip())
        entries = ",".join(json.dumps(value) for value in values)
        methods.append(
            f"func (v {name}) String() string {{"
            f" return [...]string{{{entries}}}[int(v)] }}"
        )
    return "\n".join(methods)


def compile_kitty(args, sources):
    source = sources["kitty-command.go"]
    writer = between(source, "// Enums {{{", "var debugprintln = tty.DebugPrintln")
    writer += between(
        source,
        "func (self *GraphicsCommand) serialize_to",
        "type loop_io_writer struct",
    )
    writer += function(source, "func (self *GraphicsCommand) Format()") + "\n"
    writer += function(source, "func (self *GraphicsCommand) SetCompression(")
    harness = source.splitlines()[0] + "\n" + (HERE / "kitty-harness.go").read_text(encoding="utf-8")
    harness = harness.replace("// UPSTREAM_WRITER", writer)
    harness = harness.replace("// UPSTREAM_ENUM_STRINGS", enum_strings(source))
    path = WORK / "kitty-capture.go"
    path.write_text(harness, encoding="utf-8", newline="\n")
    binary = WORK / ("kitty-capture.exe" if os.name == "nt" else "kitty-capture")
    env = dict(
        os.environ,
        GOCACHE=str(WORK / "go-cache"),
        GOENV="off",
        GOTOOLCHAIN="local",
    )
    run([args.go, "build", "-o", binary, path], env=env)
    return [binary]


def noise(length):
    state = 0x12345678
    result = bytearray()
    for _ in range(length):
        state ^= (state << 13) & 0xFFFFFFFF
        state ^= state >> 17
        state ^= (state << 5) & 0xFFFFFFFF
        result.append(state & 255)
    return bytes(result)


def png(pixels):
    def chunk(kind, data):
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data))
        )

    scanlines = b"\0" + pixels[:8] + b"\0" + pixels[8:]
    # Fixed stream preserves the tiny fixture across Python/zlib encoder versions.
    compressed = bytes.fromhex("789c63f8cfc0f01f081b18c0341000003fd70879")
    if zlib.decompress(compressed) != scanlines:
        raise RuntimeError("PNG fixture pixels and fixed IDAT stream disagree")
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", compressed)
        + chunk(b"IEND", b"")
    )


def commands(data):
    result = []
    for match in re.finditer(rb"\x1b_G(.*?)\x1b\\", data, re.S):
        controls, _, payload = match[1].partition(b";")
        fields = dict(
            field.decode("ascii").split("=", 1)
            for field in controls.split(b",")
            if field
        )
        result.append({"controls": fields, "payloadBytes": len(payload)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go", default="go")
    parser.add_argument("--rustc", default="rustc")
    parser.add_argument("--node", default="node")
    parser.add_argument("--rust-linker")
    parser.add_argument(
        "--write",
        action="store_true",
        help="replace committed fixtures after an intentional upstream review",
    )
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    go_version = run([args.go, "version"], capture_output=True, text=True).stdout
    if "go1.27.3" not in go_version:
        raise RuntimeError("Use Go 1.27.3 to preserve compressed-byte reproducibility")
    rust_version = run([args.rustc, "--version"], capture_output=True, text=True).stdout
    if "rustc 1.94.0" not in rust_version:
        raise RuntimeError("Use Rust 1.94.0 with its pinned WASI standard library")
    sources = {}
    provenance = []
    for name, (repo, revision, path, digest) in SOURCES.items():
        url = f"https://raw.githubusercontent.com/{repo}/{revision}/{path}"
        sources[name] = fetch(name, url, digest).read_text(encoding="utf-8")
        provenance.append(
            {
                "producer": "yazi" if repo == "sxyazi/yazi" else "kitty",
                "path": path,
                "url": url,
                "sha256": digest,
            }
        )
    yazi = compile_yazi(args, sources)
    kitty = compile_kitty(args, sources)
    captures = []
    for mode, name, kind in [
        ("probes", "yazi-probes", "query"),
        ("rgb", "yazi-rgb-upload", "upload"),
        ("rgba", "yazi-rgba-upload", "upload"),
        ("placements", "yazi-placements", "placement"),
        ("delete-placements", "yazi-delete-placements", "delete"),
        ("delete-image", "yazi-delete-image", "delete"),
    ]:
        image = None
        raw = b""
        if mode in ("rgb", "rgba"):
            width, height, channels = (73, 19, 3) if mode == "rgb" else (41, 29, 4)
            raw = noise(width * height * channels)
            image = {
                "format": channels * 8,
                "width": width,
                "height": height,
                "pixelFormat": "RGB8" if channels == 3 else "RGBA8",
                "pixelSha256": sha(raw),
            }
        output = run(yazi + [mode], input=raw, capture_output=True).stdout
        captures.append((name, "yazi", kind, output, image))
    pixels = bytes([255, 0, 0, 255, 0, 255, 0, 128, 0, 0, 255, 0, 255, 255, 255, 255])
    solid_rgb = bytes([17, 34, 51]) * (128 * 64)
    large_rgba = noise(199 * 181 * 4)
    kitty_inputs = [
        ("png", png(pixels), pixels, 100, 2, 2, "RGBA8"),
        ("zlib-rgb", solid_rgb, solid_rgb, 24, 128, 64, "RGB8"),
        ("large-rgba", large_rgba, large_rgba, 32, 199, 181, "RGBA8"),
    ]
    for mode, payload, pixels, image_format, width, height, pixel_format in kitty_inputs:
        output = run(kitty + [mode], input=payload, capture_output=True).stdout
        image = {
            "format": image_format,
            "width": width,
            "height": height,
            "pixelFormat": pixel_format,
            "pixelSha256": sha(pixels),
        }
        captures.append((f"kitty-{mode}", "kitty", "upload", output, image))
    cases = []
    for name, producer, kind, output, image in captures:
        expected = {"commands": commands(output)}
        if image:
            expected["image"] = image
        if kind == "query":
            expected["shmName"] = "/ketraterm-fixture-shm"
        cases.append(
            {
                "name": name,
                "file": name + ".bin",
                "sha256": sha(output),
                "producer": producer,
                "kind": kind,
                "expected": expected,
            }
        )
    manifest = {
        "schemaVersion": 1,
        "captureMethod": (
            "pinned upstream serializer bodies executed in isolated producer harnesses; "
            "not full application sessions"
        ),
        "producers": {
            "yazi": {
                "revision": YAZI,
                "license": "MIT",
                "licenseUrl": f"https://github.com/sxyazi/yazi/blob/{YAZI}/LICENSE",
            },
            "kitty": {
                "revision": KITTY,
                "license": "GPL-3.0-only",
                "licenseUrl": f"https://github.com/kovidgoyal/kitty/blob/{KITTY}/LICENSE",
            },
        },
        "sources": provenance,
        "toolchains": {
            "go": "1.27.3",
            "rust": "1.94.0",
            "rustTarget": "wasm32-wasip1",
            "rustStdSha256": RUST_STD_SHA,
            "base64Crate": BASE64_VERSION,
            "base64CrateSha256": BASE64_SHA,
        },
        "cases": cases,
    }
    artifacts = {name + ".bin": output for name, _, _, output, _ in captures}
    artifacts["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    if args.write:
        CORPUS.mkdir(parents=True, exist_ok=True)
        for name, data in artifacts.items():
            (CORPUS / name).write_bytes(data)
        print(f"Wrote {len(cases)} producer fixtures and manifest")
    else:
        for name, data in artifacts.items():
            path = CORPUS / name
            if not path.exists() or path.read_bytes() != data:
                raise RuntimeError(
                    f"Fixture differs: {path}; review before using --write"
                )
        print(f"Verified {len(cases)} producer fixtures and manifest byte-for-byte")


if __name__ == "__main__":
    main()
