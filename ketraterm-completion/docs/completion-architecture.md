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
- `TerminalCompletionPersistenceDecision` and its reason/location vocabulary
- `TerminalCommandCompletionStatsSnapshot`
- `TerminalCommandCompletionStatsSnapshotCodec`

`TerminalCompletionPersistencePolicy` is the reviewed host-facing privacy facade. It evaluates exact commands and
structural statistics and sanitizes a complete snapshot before a host crosses a storage boundary. Its keyword matching
and filtering implementation remains internal.

Model constructors expose durable host-owned fields only. Derived matching keys,
such as normalized command text and normalized command-shape keys, are computed
by completion internals and snapshot codecs instead of being caller-owned
constructor state.

Types used only to tokenize, classify, rank, merge, or index suggestions belong
in implementation packages and must stay `internal`.

`TerminalCompletionSources.valueDomain(...)` adapts a bounded immutable host snapshot for one declared
`TerminalCompletionValueDomain`. It resolves the active spec context through the shared tokenizer, applies the request's
shell quoting policy, and emits domain-tagged argument candidates. Its snapshot supplier is a pure ready-state read and
must never perform host I/O.

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
- `history`
- `internal`
- `ranking`
- `source`
- `spec`
- `stats`

Top-level declarations in those packages should be `internal` unless a product
decision explicitly promotes a type into `api` or `model`.

## Host Ownership

Hosts are responsible for applying `TerminalCompletionPersistencePolicy` to authoritative command records and for
choosing whether and where persistence is enabled. Completion sources accept compact stats snapshots and live feedback
events, but they never read files, scan raw shell history, spawn shells, or talk to UI frameworks.

Optional disk I/O belongs to the separately published
`ketraterm-completion-persistence` module. Its
`TerminalCompletionStatsStore` sanitizes again at the storage boundary, applies byte/line/row bounds before decoding or
encoding, serializes through the shared versioned codec, and coalesces atomic file replacements on a private worker.
Product hosts own the destination path, load scheduling, diagnostics, and store lifecycle. Completion persistence is not
a workspace responsibility.

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

Session MRU also maintains a separate bounded, in-memory observed-token index
for executables that have no static `TerminalCommandSpec`. Successful commands
such as `abc de -g`, `abc de -f`, and `abc as` can therefore offer `de` and
`as` after `abc `, and `-g`/`-f` after `abc de `. These are `ARGUMENT`
candidates labeled as observed session usage, not inferred subcommands or a
claimed command grammar. The index learns only the first non-option token after
an unknown executable and option names; it never learns later positional values
or option values. It is cleared with the session MRU and is never part of
persisted command statistics.

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

Path interpretation is host-owned. The pure source emits a
`TerminalDirectoryListingRequest` containing the authoritative working-directory
URI, a transport-neutral lexical directory prefix, and the active entry-name
prefix. It does not discard URI authorities, expand `~`, or interpret drive and
UNC roots. Hosts must reject remote authorities they cannot map safely and
return only bounded, already-published snapshots from the synchronous provider
callback.

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

Reusable ready-snapshot, local-path, and bounded directory-scanning machinery belongs to `ketraterm-completion-host`; it
may perform bounded host work but does not parse, rank, or prioritize completion candidates. Standalone and IntelliJ
share its generation and failure semantics while retaining only their environment-specific loaders and scanners. The
standalone app uses session-local, immutable directory snapshots fed by a window-owned instance of the shared coroutine
service with a bounded channel and two IO workers.
Enumeration has visit, result, and elapsed-time caps; caches have capacity and
expiry bounds; request generations prevent stale work from refreshing the popup. A failed load clears only its matching
in-flight generation, retains any previous ready snapshot, and can be retried by the next request. The app resolves
local and `localhost` file URIs, explicit home paths,
Windows drive roots, and Windows UNC roots while rejecting non-local OSC 7 authorities. The IntelliJ plugin uses
project-aware VFS directory snapshots for paths inside project content and bounded local scanning elsewhere. Its first
dynamic value provider reads local branches from the Git4Idea repository that contains the terminal working directory
and publishes generation-safe, failure-retryable snapshots for `git switch`, `checkout`, `merge`, and `rebase`. Remote
refs, changelists,
whole-project fuzzy paths, SDKs, and run configurations remain follow-up work.

IntelliJ dynamic providers are composed through additive provider factories. Each factory returns one prioritized source
plus the closeable snapshot resources owned by that source. Adding a new value domain therefore does not require another
field or close branch in the central session registry. The registry owns session composition; a separate statistics
coordinator owns privacy filtering, serialized learning mutations, persistence, and shutdown. Standalone uses the same
coordinator split so completion files are never loaded on the Swing event-dispatch thread.

The engine-to-Swing request/candidate bridge and Swing-feedback-to-statistics mapping live in `ketraterm-ui-swing-host`.
Product hosts inject context, privacy, scheduling, and persistence policy instead of copying the vocabulary conversion
logic.

Both hosts should map their data into the shared request/candidate/source
contracts and let the shared engine merge, deduplicate, and rank candidates.
`ketraterm-completion` must stay pure: it should not shell out to Git, read IDE
indexes, watch files, or block on host I/O.

Learned statistics publish immutable list snapshots for persistence together
with internal ranking indexes built at mutation time. Repeated completion
requests therefore reuse direct source/kind/position/context feedback lookup
and executable-family shape lookup instead of copying or scanning all retained
rows. External snapshot suppliers retain the same public list contract and are
indexed lazily once per stable list identity.

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

Candidate ordering remains deterministic. Completion candidate sets are
deliberately small, so decorators and the merged engine use auditable standard
collection sorting and deduplication rather than custom allocation-free data
structures. Benchmarks track learned-ranking and hostile-provider costs so a
more specialized selector is introduced only if measurements justify it.

Source collection and final presentation limits are deliberately distinct. The engine requests a bounded surplus from
collecting sources, shape and feedback decorators rerank that shared surplus without multiplying nested budgets, and the
merged engine applies `request.maxCandidates` only after context ranking and deduplication. The collection budget is
four times the visible limit with an absolute surplus cap of 256 and overflow-safe arithmetic; learned ranking can
therefore promote a candidate that began just outside the visible result without permitting unbounded host work.
