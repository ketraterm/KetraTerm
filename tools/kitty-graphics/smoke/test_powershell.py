"""Verify the PowerShell sender's wire output; requires PowerShell, not a terminal."""

import base64
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
import zlib


SCRIPT = Path(__file__).resolve().parents[1] / "Show-TerminalImage.ps1"
SHELLS = [path for name in ("powershell", "pwsh") if (path := shutil.which(name))]


def png(width, height):
    """Build a valid red RGB PNG without compression to exercise chunking."""
    def chunk(kind, data):
        return (
            struct.pack(">I", len(data)) + kind + data
            + struct.pack(">I", zlib.crc32(kind + data))
        )

    rows = (b"\0" + b"\xff\0\0" * width) * height
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows, level=0))
        + chunk(b"IEND", b"")
    )


class PowerShellSenderTest(unittest.TestCase):
    def test_sender(self):
        self.assertTrue(SHELLS, "Install Windows PowerShell or PowerShell 7 to run this check")
        with tempfile.TemporaryDirectory(prefix="ketraterm graphics ") as directory:
            path = Path(directory) / "image [literal].png"
            for shell in SHELLS:
                # These widths straddle the exact 4096-character Base64 boundary.
                for data, columns in (
                    (png(1, 1), None),
                    (png(1000, 1), 20),
                    (png(1001, 1), 20),
                    (png(1002, 1), 20),
                    (png(160, 80), 20),
                    (b"", 20),
                    (b"not a PNG", 20),
                    (png(1, 1), 0),
                ):
                    with self.subTest(shell=shell, bytes=len(data), columns=columns):
                        path.write_bytes(data)
                        command = [
                            shell, "-NoProfile", "-NonInteractive", "-File",
                            str(SCRIPT), str(path),
                        ]
                        if columns is not None:
                            command += ["-Columns", str(columns)]
                        result = subprocess.run(command, capture_output=True, timeout=30)
                        if not data.startswith(b"\x89PNG") or columns == 0:
                            self.assertNotEqual(result.returncode, 0)
                            self.assertEqual(result.stdout, b"")
                            continue
                        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
                        self.assertEqual(result.stderr, b"")
                        frame = rb"\x1b_G([^;]+);([A-Za-z0-9+/=]+)\x1b\\"
                        self.assertIsNotNone(re.fullmatch(b"(?:" + frame + b")+\r?\n", result.stdout))
                        frames = re.findall(frame, result.stdout)
                        parts = []
                        for index, (header, part) in enumerate(frames):
                            fields = dict(field.split("=") for field in header.decode().split(","))
                            self.assertEqual(fields.pop("m"), "0" if index == len(frames) - 1 else "1")
                            expected = {"q": "2"}
                            if index == 0:
                                expected.update(a="T", f="100", t="d", c=str(columns or 40))
                            self.assertEqual(fields, expected)
                            self.assertLessEqual(len(part), 4096)
                            if index < len(frames) - 1:
                                self.assertEqual(len(part), 4096)
                            parts.append(part)
                        self.assertEqual(base64.b64decode(b"".join(parts), validate=True), data)
                        encoded_length = 4 * ((len(data) + 2) // 3)
                        self.assertEqual(len(parts), (encoded_length + 4095) // 4096)


if __name__ == "__main__":
    unittest.main()
