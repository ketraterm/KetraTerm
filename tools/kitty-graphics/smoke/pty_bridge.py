#!/usr/bin/env python3
# Copyright 2026 Gagik Sargsyan. Licensed under the Apache License, Version 2.0.
"""Launch a real application in a Linux PTY and relay its bytes without parsing them."""
import argparse
import errno
import fcntl
import os
import pty
import select
import signal
import struct
import sys
import termios
import zlib
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", choices=("yazi", "kitten"))
    parser.add_argument("executable", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    executable = args.executable.resolve(strict=True)
    output = args.output.resolve()
    for name in ("images", "config", "cache", "state"):
        (output / name).mkdir(parents=True, exist_ok=True)

    def chunk(name, data):
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))

    pixels = b"".join(b"\0" + bytes((255, 0, 0, 255)) * 160 for _ in range(80))
    image = (b"\x89PNG\r\n\x1a\n"
             + chunk(b"IHDR", struct.pack(">IIBBBBB", 160, 80, 8, 6, 0, 0, 0))
             + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))
    (output / "images/a.png").write_bytes(image)
    (output / "images/b.txt").write_text("KETRATERM_SMOKE_TEXT\n", encoding="utf-8")

    pid, master = pty.fork()
    if pid == 0:
        # Yazi must discover cell pixels through CSI 16 t when ioctl has none.
        # icat reads ioctl only, so its independent smoke needs real pixel fields.
        pixel_width, pixel_height = (0, 0) if args.app == "yazi" else (800, 600)
        fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 100, pixel_width, pixel_height))
        env = os.environ.copy()
        for name in ("KITTY_WINDOW_ID", "WEZTERM_EXECUTABLE", "TERM_PROGRAM", "TMUX", "SSH_CONNECTION"):
            env.pop(name, None)
        env.update(TERM="xterm-256color", COLORTERM="truecolor",
                   YAZI_CONFIG_HOME=str(output / "config"), YAZI_CACHE_HOME=str(output / "cache"),
                   XDG_STATE_HOME=str(output / "state"))
        os.chdir(output / "images")
        command = [str(executable)]
        if args.app == "kitten":
            command += ["icat", "--transfer-mode=stream", "--stdin=no"]
        command += [str(output / "images/a.png")]
        os.execve(executable, command, env)

    try:
        while True:
            ready, _, _ = select.select([master, 0], [], [])
            if master in ready:
                try:
                    data = os.read(master, 65536)
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not data:
                    break
                sys.stdout.buffer.write(data)
                sys.stdout.buffer.flush()
            if 0 in ready:
                data = os.read(0, 65536)
                if not data:
                    break
                while data:
                    data = data[os.write(master, data):]
    finally:
        os.close(master)
        try:
            os.kill(pid, signal.SIGHUP)
        except ProcessLookupError:
            pass
        _, status = os.waitpid(pid, 0)
    return os.waitstatus_to_exitcode(status)


if __name__ == "__main__":
    sys.exit(main())
