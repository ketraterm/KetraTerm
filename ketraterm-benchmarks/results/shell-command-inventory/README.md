# Shell command inventory benchmarks

Use these workloads to investigate completion costs during development. Run from
the repository root with JDK 25; on Windows use `gradlew.bat`.

## Snapshot and lookup costs

```text
./gradlew :ketraterm-benchmarks:jmhJar
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-{version}-jmh.jar ".*TerminalShellCommandInventoryBenchmark.*" -wi 2 -i 3 -w 500ms -r 500ms -f 1 -prof gc -rf json -rff ketraterm-benchmarks/build/inventory-jmh.json
```

| Workload | What it measures |
| --- | --- |
| `buildSnapshot` | Validation, sorting, and index construction from existing records. |
| `prefixMatches` | Lookup of up to 16 matching commands in an existing snapshot. |
| `missingPrefix` | Lookup with no matching commands. |
| `aliases` | Access to the cached alias map. |

The `count` parameter selects inventory size. Record/string creation is setup
work; lookup workloads reuse a completed snapshot. These workloads exclude shell
discovery, protocol decoding, and popup rendering. Use the GC profiler to inspect
allocations. See the [benchmark guide](../../README.md) for measurement practices.

## Native shell discovery

The [Python harness](profile-discovery.py) measures shell-local publication,
executable discovery, and alias refresh in fresh shell processes:

```text
python ketraterm-benchmarks/results/shell-command-inventory/profile-discovery.py --shell zsh --wsl Ubuntu --repeat 3
python ketraterm-benchmarks/results/shell-command-inventory/profile-discovery.py --shell bash --executable "C:/Program Files/Git/bin/bash.exe" --repeat 3
```

Use `--count 10000` to create a synthetic executable directory before timing;
fixtures are removed on exit. Python 3 and the chosen shell must be installed.
The harness checks complete inventory frames, totals, and unique names before
printing results. Timings exclude process startup and fixture creation; filesystem
caches are not flushed. Native correctness tests use the shared
[`nativeTest` suite](../../../docs/development/testing.md#execution-suites).
