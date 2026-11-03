# Shell command inventory: local measurement

Measured 2026-11-03 on Windows x64, OpenJDK Temurin 25.0.3+9-LTS, JMH 1.37,
OpenJDK JOL 0.17. Source: base revision `50d4aaa737a92c11edd5d55e5fb28ed765bc98c2`
plus the accompanying inventory changes. This is a short local measurement, not
a release performance guarantee or a comparison with the previous implementation.

## Retained memory

JOL `GraphLayout.parseInstance(inventory)` counts only the graph reachable from
one completed snapshot. Names are `command-00000` through the configured count;
every twentieth record has an alias definition `git -C 'repository N' status`.
Display and insertion share a string. This is the same representative data as
`TerminalShellCommandInventoryBenchmark`.

| Commands | Retained bytes | Objects |
| ---: | ---: | ---: |
| 1,000 | 98,496 | 3,165 |
| 10,000 | 980,480 | 31,515 |
| 32,768 | 3,214,992 | 103,236 |

A deliberately expensive boundary case has 32,768 aliases, each with distinct
16-unit name, 16-unit insertion, and 32-unit definition strings containing
non-Latin-1 characters. It uses the entire 2,097,152-unit receiver text budget:
**10,879,360 bytes / 262,159 objects**. The entry count is therefore not a fixed
heap-size guarantee. Workspace producers apply a stricter UTF-8 byte budget;
this case exercises the public snapshot's receiver boundary.

These totals exclude collection, decoding, sorting scratch, popup candidates,
and the shell process. During refresh the previous complete snapshot and one
bounded replacement assembly can coexist. Session closure drops both owned
references; consumers can extend a borrowed snapshot's lifetime by retaining it.
No trie, second full name map, or per-request alias-map copy is retained.

## JMH

Raw measurements, JVM details, uncertainty and allocation results are in
[jmh.json](jmh.json). Commands, from the repository root with JDK 25:

```text
./gradlew :ketraterm-benchmarks:jmhJar
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-{version}-jmh.jar ".*TerminalShellCommandInventoryBenchmark.*" -wi 2 -i 3 -w 500ms -r 500ms -f 1 -prof gc -rf json -rff inventory-jmh.json
```

The local run redirected Gradle outputs with `-I build/verify-shell-authority.init.gradle`
to `build/shell-authority-verification/<module>`; this changes output paths only.
One thread/fork, two 500 ms warmups, three 500 ms measurements. Payload construction
is setup work. `buildSnapshot` includes validation, sorting reverse-ordered records,
and alias-map construction, but excludes decoding. It is not worst-case sorting.

For 32,768 entries, returning 16 prefix matches took about **0.41 μs/op** and
allocated **56 B/op**, independent of inventory size. An absent prefix took about
**0.07 μs/op** with allocation at profiler noise level. Cached alias access likewise
had no measurable per-operation allocation. These are lookup costs, not end-to-end
popup or shell-discovery latency.

Retained-memory reproduction uses JOL as an external measurement dependency,
not a production or Gradle dependency. Construct the benchmark's records, then:

```java
var inventory = new TerminalShellCommandInventory(entries, TerminalShellCommandInventoryStatus.READY);
var graph = org.openjdk.jol.info.GraphLayout.parseInstance(inventory);
System.out.println(graph.totalSize() + " bytes / " + graph.totalCount() + " objects");
```

Run with `-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading` and
`jol-core-0.17.jar` plus the benchmark JAR on the classpath. JOL's own tooling may
emit JVM serviceability/Unsafe warnings; it is not shipped with the terminal.

## Native discovery

Installed zsh 5.9 in Ubuntu WSL produced **8,288 commands**, including `git`,
in **207,790 wire bytes**. A login-shell run measured 57.93 seconds and 10,060 KiB
maximum RSS using `/usr/bin/time -v`. Separate phase timing put about 49 seconds
inside per-entry discovery/validation; hashing the already initialized command
table was negligible. This environment's PATH includes mounted Windows directories.
Copying the command table first did not materially improve it and was not adopted.

Native tests also verified 10,000 generated commands, explicit overflow, literal
aliases, executable removal, Unicode quoting, and WSL PTY conditional acceptance.
Outstanding initial-discovery latency is tracked in the
[feature gap map](../../../docs/terminal-feature-gap-map.md); bounded transport and
fast cached lookup do not remove filesystem latency.

## Independent source refresh measurements (2026-11-03)

After separating shell-local metadata from PATH discovery, a real WSL PTY check
with the inherited PATH (including Windows mounts) measured:

| Shell | Initial local commands | Full PATH discovery | Alias update using retained PATH |
| --- | ---: | ---: | ---: |
| Bash 5.3.9 | 31 ms | 47.1 s | 9.7 ms |
| zsh 5.9 | 69 ms | 33.3 s | 16.6 ms |

These are single-run end-to-end observations on this development machine, not
latency guarantees or comparable JMH samples. Initial local timing includes
sourcing the hook and PTY scheduling. The PTY check also verified unchanged PATH
epochs across alias updates and absolute-PATH directory changes, and changed
epochs across directory changes with relative PATH entries. The committed native
workspace tests cover absolute, relative, and empty entries.

Cold filesystem enumeration remains expensive. Local metadata now publishes at
the prompt without enumerating PATH, while at most one PATH worker runs in the
background. Large shell-local tables can still increase prompt work. An unchanged
PATH is eligible for refresh 60 seconds after a prompt observes completion.

The earlier object-layout measurements describe one inventory, not total session
memory after this change. Integration now retains two bounded source snapshots
and the merged index, sharing candidate/string objects between them. Each source
can retain the documented per-source limit, including while a refresh assembles;
the merged publication independently enforces the original entry/text limits.

Native Windows Git Bash publisher timings (one shell process, inherited PATH):
local metadata 64.9 ms, PATH discovery 36.9 s, alias metadata refresh 63.5 ms.
These use Bash EPOCHREALTIME around each publisher call; they exclude process
startup and differ from the WSL PTY measurement method. The warm alias operation
performs no PATH enumeration, but still starts the Base64 encoder.

## Directory-relative discovery (2026-11-03)

The shared Bash/zsh collector now enumerates PATH directories and checks executable
basenames inside each directory in an isolated subshell. This avoids repeatedly
resolving the ancestors of mounted paths. It retains executable permission checks,
valid explicit command hashes, and shell-local precedence. No GNU find dependency
or file-mode shortcut was introduced. Git Bash avoids repeated command resolution
for unambiguous ASCII `.exe` insertions; collisions and hashes use Bash's resolver.

Environment: Windows 10.0.26300 x64, AMD64 family 25/model 117/stepping 2,
Git Bash 5.3, Ubuntu WSL Bash 5.3.9 and zsh 5.9. Source is the base revision above
plus the accompanying directory-relative discovery changes. The inherited WSL
PATH includes Windows mounts. OS caches were not flushed. These are fresh shell
processes with publisher calls timed using `EPOCHREALTIME`, excluding process
startup, hook loading, UI work, and fixture setup. They do not measure cold disks,
network shares, server hardware, or end-to-end popup latency.

Baseline phase instrumentation identified the expensive work:

| Shell | Enumeration | Complete publisher | Base64 encoding total |
| --- | ---: | ---: | ---: |
| Git Bash | 5.37 s | 19.20 s | 0.17 s |
| WSL Bash | 32.59 s | 38.20 s | 0.036 s |
| WSL zsh | 0.18 s | 26.08 s | 0.058 s |

Raw baseline phase timestamps: [Git Bash](baseline-gitbash-phases.txt),
[WSL Bash](baseline-wslbash-phases.txt), [WSL zsh](baseline-wslzsh-phases.txt).
The Bash enumeration includes `compgen`; zsh spends most of its time in subsequent
per-entry validation. Baseline runs and final runs are not controlled paired trials.

Final publisher samples:

| Environment | PATH entries | Local metadata | Full PATH | Alias refresh |
| --- | ---: | ---: | ---: | ---: |
| Git Bash, inherited PATH | 5,775 | 68–106 ms | 3.06–7.27 s | 80 ms |
| WSL Bash, inherited PATH | 7,193 | 6.6–10.9 ms | 10.37–11.72 s | 7.0–8.1 ms |
| WSL zsh, inherited PATH | 7,193 | 8.3–9.1 ms | 10.50–11.15 s | 7.2 ms |
| WSL Bash, 10,000 generated executables plus `/usr/bin:/bin` | 10,976 | 8.2 ms | 0.54 s | 7.1 ms |

Raw samples: [Git Bash](discovery-gitbash.json), [WSL Bash](discovery-wsl-bash.json),
[WSL zsh](discovery-wsl-zsh.json), [10,000 commands](discovery-wsl-bash-10000.json).
Two samples per inherited PATH, one synthetic sample. The final Git Bash run
overlapped Gradle verification; the range reflects that contention. Earlier local
runs ranged from 2.56 to 5.47 seconds. The synthetic directory is on WSL's Linux
filesystem and is not comparable to the inherited mounted PATH. These small
samples establish a local improvement, not a portable latency guarantee.

Reproduce from the repository root with Python 3 (use its full path if the Windows
`python` alias is unavailable):

```text
python ketraterm-benchmarks/results/shell-command-inventory/profile-discovery.py --shell bash --executable "C:/Program Files/Git/bin/bash.exe" --repeat 2 --output discovery-gitbash.json
python ketraterm-benchmarks/results/shell-command-inventory/profile-discovery.py --shell bash --wsl Ubuntu --repeat 2 --output discovery-wsl-bash.json
python ketraterm-benchmarks/results/shell-command-inventory/profile-discovery.py --shell zsh --wsl Ubuntu --repeat 2 --output discovery-wsl-zsh.json
python ketraterm-benchmarks/results/shell-command-inventory/profile-discovery.py --shell bash --wsl Ubuntu --repeat 1 --count 10000 --output discovery-wsl-bash-10000.json
```

The [stdlib-only harness](profile-discovery.py) validates complete v3 snapshots,
chunk sequencing, entry uniqueness, and declared totals outside the timed shell
operation. Generated executable fixtures are created before timing and removed
on exit. Discovery still depends on filesystem latency. Snapshot budgets do not
bound the shell's directory-glob expansion or total process heap.

Validation: collector regressions and Swing suites passed; WSL zsh checks covered
PATH semantics, explicit hashes, removal, and suppression of user directory hooks.
Workspace native runs also exposed intermittent 15-second timeouts in Bash and
PowerShell acceptance tests. Debugger traces confirmed that Bash's background
PATH frames could interleave with foreground prompt markers, leaving the prompt
unavailable even after the accepted command ran. Bash/zsh now publish background
snapshots through a bounded, session-owned file mailbox instead of the PTY.
Deterministic tests cover delivery between prompt bytes, stale environments,
malformed envelopes, atomic file replacement, and session cleanup. Ten repeated
Bash acceptance runs passed after the fix; real WSL Bash/zsh checks confirmed
that background PATH frames stay out of the terminal stream.

PowerShell's earlier timeout did not reproduce in 40 repeated runs, including ten
with immediate Enter after replacement. Its cause remains unconfirmed; no timeout
was increased. Visual popup verification remains blocked because the computer-use
runtime cannot start its Node process.
