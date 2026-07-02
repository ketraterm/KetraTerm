# Completion Module Architecture

`ketraterm-completion` is a dependency-free completion engine module. It owns
pure request/candidate contracts, static command specs, command-line parsing,
ranking, and bounded in-memory learning indexes. It does not own UI popup
behavior, persistence, shell processes, PTY/session lifecycle, or IntelliJ APIs.

## Public Surface

External modules should import only:

- `io.github.ketraterm.completion.api`
- `io.github.ketraterm.completion.model`

The `api` package exposes host-facing engines, source factories, request and
candidate contracts, and mutable learning-source interfaces. Factory methods
are intentionally narrow: hosts may create spec, session-MRU, stats, and
feedback-aware sources, then merge prioritized sources into an engine. Ranking
decorators that are specific to one source type remain implementation details.

The `model` package contains durable public data models that hosts may persist
or construct:

- `TerminalCommandSpec` and `TerminalOptionSpec`
- `TerminalPathArgumentKind`
- `TerminalCommandSpecs`
- `TerminalCommandCompletionStats`
- `TerminalCommandShapeStats` and `TerminalCommandLineShape`
- `TerminalCompletionFeedbackStats` and feedback vocabulary
- `TerminalCommandCompletionStatsSnapshot`
- `TerminalCommandCompletionStatsSnapshotCodec`

Model constructors expose durable host-owned fields only. Derived matching keys,
such as normalized command text and normalized command-shape keys, are computed
by completion internals and snapshot codecs instead of being caller-owned
constructor state.

Types used only to tokenize, classify, rank, merge, or index suggestions belong
in implementation packages and must stay `internal`.

## Internal Implementation

These packages are implementation detail and must not be imported by app,
workspace, Swing UI, or future plugin code:

- `commandline`
- `engine`
- `internal`
- `ranking`
- `source`
- `spec`
- `stats`

Top-level declarations in those packages should be `internal` unless a product
decision explicitly promotes a type into `api` or `model`.

## Host Ownership

Hosts are responsible for privacy filtering and disk persistence. Completion
sources accept compact stats snapshots and live feedback events, but they never
read files, scan raw shell history, spawn shells, or talk to UI frameworks.

The standalone app and IntelliJ plugin should compose completion sources through
`TerminalCompletionSources` and `TerminalCompletionEngines`, then adapt returned
candidates to their own UI presentation.

The public API should not grow by convenience. New public functions must be
durable host contracts, used by standalone/plugin integration, or explicitly
documented persistence/model contracts.

## Command-Line Context Policy

Completion treats trailing space as a semantic boundary. With the cursor inside
`cd`, sources complete the command token. With the cursor after `cd `, sources
complete a new empty argument at the cursor while full-command MRU and stats
sources may still match the normalized visible command prefix.

Path completion is intentionally conservative. In command position it only
returns candidates for explicitly path-like prefixes. In argument position it
returns bare current-directory entries only when the resolved
`TerminalCommandSpec` or `TerminalOptionSpec` declares path metadata through
`TerminalPathArgumentKind`. Directory-changing commands such as `cd`, `chdir`,
`pushd`, and PowerShell `Set-Location` aliases receive directory-only
candidates, while commands such as `git add` and `kubectl apply` may request
file-or-directory candidates. Dot-prefixed entries are hidden for an empty path
prefix and appear once the user types `.`. When the active path token begins
with a quote, path candidates replace the whole token with a matching quoted
replacement instead of dropping the quote.

Static bounded option domains belong in `TerminalOptionSpec.valueCandidates`.
Examples are output formats, log levels, or other values that are stable and do
not require host I/O. Dynamic domains such as Git branches, Docker contexts,
Kubernetes namespaces, IDE run configurations, project files, or indexed symbols
must come from host-owned providers.

## Host Dynamic Providers

Standalone and IntelliJ completion providers are expected to differ internally.
The standalone app may use bounded local filesystem/process adapters with
timeouts and background caches. The IntelliJ plugin should prefer IDE services
for VCS roots, branches, changelists, project files, indexes, SDKs, run
configurations, and other project context.

Both hosts should map their data into the shared request/candidate/source
contracts and let the shared engine merge, deduplicate, and rank candidates.
`ketraterm-completion` must stay pure: it should not shell out to Git, read IDE
indexes, watch files, or block on host I/O.

## Ranking Policy Direction

Current ranking is deterministic source-priority plus candidate-score ordering.
Future source-aware ranking should make the active command-line context explicit
instead of hardcoding host behavior: command position, subcommand position,
option name, option value, positional value, expected argument kind, command
family, and source kind should all be available to ranking decorators. This is
the place to prefer branches over paths for `git switch`, paths over MRU for
`cd`, and option values after value-taking options without mixing standalone or
IDE-specific provider code into the shared engine.
