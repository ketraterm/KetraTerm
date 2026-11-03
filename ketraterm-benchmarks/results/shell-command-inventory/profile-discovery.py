"""Measure complete fresh-process shell inventories; Python stdlib only.

Run from any directory. --wsl uses an installed WSL distribution. Filesystem/OS
caches are deliberately not flushed. --count creates executable fixture files
outside the timed region in the shell environment and cleans them on exit.
"""
import argparse
import base64
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--shell', choices=['bash', 'zsh'], required=True)
parser.add_argument('--executable')
parser.add_argument('--wsl')
parser.add_argument('--repeat', type=int, default=3)
parser.add_argument('--count', type=int, default=0)
parser.add_argument('--output', type=Path, help='Write final JSON results to this file')
args = parser.parse_args()
if args.repeat < 1 or not 0 <= args.count <= 100000:
    parser.error('--repeat must be positive and --count must be between 0 and 100000')
root = Path(__file__).resolve().parents[3]
resources = root / 'ketraterm-workspace/src/main/resources/io/github/ketraterm/workspace'
shell = args.executable or args.shell
command = (["wsl.exe", "-d", args.wsl, "--"] if args.wsl else []) + [shell]
command += ['--noprofile', '--norc', '-s'] if args.shell == 'bash' else ['-f', '-s']
script = ('zmodload zsh/datetime\n' if args.shell == 'zsh' else '')
if args.count:
    assert 0 < args.count <= 100000
    script += f"""
fixture=$(mktemp -d) || exit 1
trap 'command rm -rf -- "$fixture"' EXIT
for ((i=0; i<{args.count}; i++)); do printf '#!/bin/sh\\n' > "$fixture/ketra_$i"; done
command chmod +x "$fixture"/*
PATH="$fixture:/usr/bin:/bin"
"""
script += (resources / 'path-commands.sh').read_text(encoding='utf-8') + '\n'
script += (resources / ('bash-completion.bash' if args.shell == 'bash' else 'zsh-completion.zsh')).read_text(encoding='utf-8') + '\n'
publisher = '__ketraterm_publish_commands' if args.shell == 'bash' else '__ketraterm_zsh_publish_commands'
for index, (label, kind) in enumerate([('local', 'Local'), ('path', 'Path'), ('alias', 'Local')], 1):
    if label == 'alias': script += "alias ketra_probe='git switch'\n"
    script += f"builtin printf 'MEASURE:{label}:start:%s\\n' \"$EPOCHREALTIME\" >&2\n"
    script += f'{publisher} test {index} {kind}\n'
    script += f"builtin printf 'MEASURE:{label}:end:%s\\n' \"$EPOCHREALTIME\" >&2\n"
results = []
for _ in range(args.repeat):
    result = subprocess.run(command, input=script.encode(), capture_output=True, timeout=120)
    assert result.returncode == 0, result.stderr.decode(errors='replace')
    chunks = {}; counts = {}
    for source, message in re.findall(rb'\x1b]633;(KetraTerm(?:Shell)?Commands);3;([^\x07]*)\x07', result.stdout):
        fields = message.split(b';'); key = (source, fields[2])
        if fields[0] == b'Begin': chunks[key] = []
        elif fields[0] == b'Chunk':
            assert int(fields[3]) == len(chunks[key])
            chunks[key].append(base64.b64decode(fields[4], validate=True))
        elif fields[0] == b'Fail': raise AssertionError(message)
        elif fields[0] == b'End':
            body = b''.join(chunks[key]); records = body.split(b'\0')[:-1]
            assert len(records) % 4 == 0
            count = len(records)//4
            assert [len(chunks[key]), count, len(body)] == list(map(int, fields[3:]))
            assert len(set(records[::4])) == count
            counts[fields[2].decode()] = count
    assert len(counts) == 3 and counts['2'] > args.count
    times = {}
    for label, stage, stamp in re.findall(rb'MEASURE:(\w+):(start|end):([\d.]+)', result.stderr):
        times.setdefault(label.decode(), {})[stage.decode()] = float(stamp)
    sample = {'milliseconds': {k: round((v['end']-v['start'])*1000, 3) for k,v in times.items()}, 'entries': counts}
    results.append(sample)
    print(json.dumps(sample), flush=True)
report = json.dumps({'command': command, 'synthetic_count': args.count, 'samples': results}, indent=2)
if args.output:
    args.output.write_text(report + '\n', encoding='utf-8')
print(report)
