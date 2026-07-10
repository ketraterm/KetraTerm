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
- `TerminalCompletionValueDomain`
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

`TerminalCompletionContextResolver` is the shared internal command-line context
resolver. Sources and ranking decorators should use it instead of independently
guessing command position, subcommand position, option-name position,
option-value position, positional-argument position, active option metadata,
expected path kind, expected dynamic value domain, repeatable subcommand source,
static value candidates, context-aware live trigger state, replacement offsets,
or active quote state from raw command text.

`TerminalShellCapabilities` is the single host-to-engine dialect contract. It
contains `TerminalShellSyntax` for segment lexing and
`TerminalShellQuotingPolicy` for replacement text. The shared engine never
infers either capability from command text or a profile id: hosts select the
tested `POSIX` or `POWERSHELL` capability set from authoritative profile
metadata, and use `PLAIN` for every shell without an implemented contract.

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

For supported POSIX and PowerShell syntax, the tokenizer uses one bounded
single-pass lexical scan per merged-engine request to select the cursor's
command segment. Operators inside quotes or escaped by the dialect do not split
segments. A cursor at the start of an operator belongs to the left segment; a
cursor inside a multi-character operator is an `OPERATOR` region and returns no
candidates; a cursor after the operator belongs to a new right segment.
Unclosed quotes and incomplete command lines remain tokenizable and resolve to
their closest logical segment. Whole-command MRU and exact-stats candidates are
suppressed in segments following an operator because their full-line replacement
range cannot safely express a segment-local completion.

The exact shell option terminator `--` is a command-context boundary once the
cursor has passed the complete token. Tokens after it remain positional: they do
not resolve as options, option values, or subcommands, and static spec option
completion stops. An incomplete prefix such as `--v` remains an option prefix
until the complete terminator token is entered.

Path completion is intentionally conservative. In command position it only
returns candidates for explicitly path-like prefixes. In argument position it
returns bare current-directory entries only when the resolved
`TerminalCommandSpec` or `TerminalOptionSpec` declares path metadata through
`TerminalPathArgumentKind`. Directory-changing commands such as `cd`, `chdir`,
`pushd`, and PowerShell `Set-Location` aliases receive directory-only
candidates, while commands such as `git add` and `kubectl apply` may request
file-or-directory candidates. Dot-prefixed entries are hidden for an empty path
prefix and appear once the user types `.` by default. Command positional and
option path metadata may instead set `TerminalHiddenPathPolicy.INCLUDE` to
always expose hidden entries, or `EXCLUDE` to keep them hidden even after a dot
prefix. When the active path token begins
with a quote, path candidates replace the whole token with a matching quoted
replacement instead of dropping the quote. Unquoted path replacements are
escaped according to `TerminalCompletionRequest.shellCapabilities.quoting`.
POSIX uses backslash escaping, PowerShell uses single-quoted literals where
escaping is necessary, and `PLAIN` omits replacements that would require
dialect-specific escaping. Existing single- and double-quote styles are
preserved when that style can safely represent the candidate.

Live trigger policy is command-context aware. Hyphen, path separator, and
environment-variable triggers remain immediate. A trailing space is immediate
only when the resolved context expects useful candidates, such as paths after
`cd `, domain values after `git switch `, or repeatable tasks after
`./gradlew `. Unknown command arguments do not become live triggers just because
the user typed a space.

Static bounded option domains belong in `TerminalOptionSpec.valueCandidates`.
Examples are output formats, log levels, or other values that are stable and do
not require host I/O. Dynamic domains are declared with
`TerminalCompletionValueDomain` through `TerminalOptionSpec.valueDomain`,
`TerminalCommandSpec.positionalArgumentValueDomain`, and
`TerminalCompletionCandidate.valueDomain`. Git branches, Docker contexts,
Kubernetes namespaces, IDE run configurations, project files, or indexed symbols
must still come from host-owned providers; the shared module only models and
ranks those values.

Options that require a value support both separate and attached forms. For
example, `aws --output text` and `aws --output=text` resolve to the same option
value context. Attached completion replaces only the text after `=`, preserving
the option name and separator. This applies to static values, path values, and
host-provided dynamic domains; a quoted attached path value preserves its quote
style through the normal path replacement policy.

Task-style CLIs that accept several sibling command values on one line should
set `TerminalCommandSpec.repeatableSubcommands`. Gradle is the built-in example:
after `./gradlew clean bu`, the context remains attached to the root Gradle
task set and the spec source can suggest `build` while omitting already-used
tasks such as `clean`.

`TerminalOptionSpec.exclusiveGroupIds` models mutually exclusive option sets
without coupling one option to another option name. Once a completed option
before the cursor claims a group, spec completion suppresses every option that
claims that group. Aliases resolve to the same option and therefore claim the
same groups.

`TerminalArgumentSpec` models ordered positional arguments. It supports static
value candidates, path and dynamic-domain metadata, optional arguments, and a
variadic final argument that applies to every remaining positional token.
`TerminalCommandSpec.positionalArguments` takes precedence when present; the
scalar positional fields remain the fallback for compact specs.

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

The standalone host currently maps PowerShell to `POWERSHELL`, its tested
POSIX-profile categories to `POSIX`, and Command Prompt, Fish, Nushell, and
unknown profiles to `PLAIN`. Native shell completion callbacks and dialect
adapters remain host-owned future work; they must supply authoritative
replacement ranges and never be called from the shared completion hot path.

## Ranking Policy

The merged engine keeps ranking deterministic while applying a small
source-aware context adjustment before candidate score and stable text
tie-breakers. `TerminalCompletionRankingContext` consumes
`TerminalCompletionContext` so command position prefers command candidates,
subcommand position prefers subcommands, option-name position prefers options,
option-value position prefers static value candidates, domain-matching dynamic
value candidates, or paths when the active option declares path metadata, and
path-taking positional arguments prefer path candidates over whole-command
history. Domain-matching dynamic argument candidates receive the strongest
context boost, so a host-owned Git branch provider can outrank generic paths and
whole-command history for `git switch <branch>`.

This base policy is intentionally host-neutral. Standalone and IntelliJ
providers should still choose source priority for their own data quality, while
shared ranking keeps common terminal semantics such as paths over MRU for `cd `
and option names after `-`. Future dynamic providers can extend the same model
with provider-specific data, for example Git branches for `git switch`, without
embedding standalone process calls or IDE service lookups in the shared engine.
