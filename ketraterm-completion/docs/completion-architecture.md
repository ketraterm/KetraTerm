# Completion Module Architecture

`ketraterm-completion` is a suspending completion engine module. It owns
request/candidate contracts, static command specs, command-line parsing,
structured parallel source evaluation, ranking, and bounded in-memory learning indexes. It does not own UI popup
behavior, persistence, shell processes, PTY/session lifecycle, or IntelliJ APIs.

## Public Surface

External modules should import only:

- `io.github.ketraterm.completion.api`
- `io.github.ketraterm.completion.model`

The `api` package exposes host-facing engines, source factories, request and
candidate contracts, a mutable session-history source, a shared session registry,
and one concrete bounded learning store. The registry owns the lifecycle and
composition of each session MRU, path source, and merged engine while hosts
supply file-system access and additional product sources. The engine automatically evaluates its
command specs as one static source, so hosts cannot wire parsing specs and
static candidates inconsistently. Statistics are
ranking evidence and persisted fallback data; they are never composed as an
independent candidate provider or ranking vote.

`TerminalCompletionMatchRanges` is the immutable primitive-backed display-range
contract carried by completion candidates. Construction validates ordered,
non-overlapping UTF-16 scalar boundaries and takes ownership through defensive
copying at public boundaries; hosts use indexed access when adapting or painting
to avoid exposing mutable array state.

`TerminalCompletionSourcePrior` is the single reviewed cold-start policy for
built-in source families. Standalone and IntelliJ composition use these named
values instead of maintaining duplicated numeric constants. They remain small
inputs to evidence fusion and never become a priority-first sorting layer.

The `model` package contains durable public data models that hosts may persist
or construct:

- `TerminalCommandSpec` and `TerminalOptionSpec`
- `TerminalPathArgumentKind`
- `TerminalCompletionValueDomain`
- `TerminalCommandSpecs`
- `TerminalCommandCompletionStats`
- `TerminalCompletionFeedbackKind`
- `TerminalCommandCompletionStatsSnapshot`

`TerminalCompletionPersistencePolicy` is the reviewed host-facing privacy boundary. It answers whether an exact
command may be persisted and sanitizes a complete snapshot before a host crosses a storage boundary. Its conservative
keyword list remains internal because hosts do not need to branch on individual rejection reasons.

Model constructors expose durable host-owned fields only. Derived matching keys,
such as normalized command text, are computed by completion internals instead
of being caller-owned constructor state.

Types used only to tokenize, classify, rank, merge, or index suggestions belong
in implementation packages and must stay `internal`.

`TerminalCompletionSources.valueDomain(...)` adapts a suspending host loader for one declared
`TerminalCompletionValueDomain`. It resolves the active spec context through the shared tokenizer, applies the request's
shell quoting policy, and emits domain-tagged argument candidates. A provider may perform bounded host I/O and must
cooperate with cancellation. A provider may additionally restrict itself to canonical command/subcommand names when a
value domain has command-specific validity.
Aggregate host sources that load several provider groups in one operation use
`TerminalCompletionSources.valueDomainCandidates(...)` to project each already-loaded group through the identical
matching, quoting, replacement, and scoring policy without constructing nested source adapters per request.

`TerminalCompletionSources.fuzzyPath(...)` adapts either a suspending bounded host path loader or a query-aware
`TerminalFuzzyPathProvider` for context-aware fuzzy path completion. Bounded list loaders use the shared dependency-free
matcher once. Both loader forms receive the same immutable completion request used by the engine, so host-relative
results use its captured working-directory URI instead of resampling mutable session state. Query-aware providers also
receive the decoded active prefix and return already matched, relevance-ordered entries; the source never repeats that
match. The shared source retains path-kind filtering, explicit replacement ranges, and shell-safe quoting. It
requires typed path text by default; a small, context-specific provider such as Git status paths may opt in to
empty-prefix suggestions.

`TerminalCompletionContextResolver` is the shared internal command-line context
resolver. The merged engine parses and resolves once from its one command-spec set, then passes
that same immutable context to every source and the global ranker instead of independently
guessing command position, subcommand position, option-name position,
option-value position, positional-argument position, active option metadata,
expected path kind, expected dynamic value domain, repeatable subcommand source,
static value candidates, replacement offsets,
or active quote state from raw command text.

Swing hosts share one `SwingLiveCompletionBinding`. Its debounced,
text-only predicate is deliberately a cheap UX gate: it never tokenizes,
resolves command specs, or duplicates source eligibility. The merged engine is
the sole semantic authority and returns an empty result when completion is not
valid. The binding retains only the last primitive request identity so equal
render frames do not republish work.

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

Implementation files follow the completion request directly. One semantic
token pass resolves command paths, inherited options, repeatable subcommands,
option values, and positional arguments for live completion. Session MRU coordinates bounded command-history
and observed-token indexes, projects learned commands into the active
replacement range, and recovers positive persisted commands as one learned
fallback stream. The shared learning store publishes one immutable aggregate
snapshot and owns the identity-aware compiled-index cache reused by learned
history and ranking. Snapshot models remain pure persistence data. One scoring
policy owns bounded counter math. Global fusion owns
outcome grouping, explicit score components, semantic relevance,
representative selection, and deterministic final ordering.
Public directory path resolution, scan contracts, and bounded scan
implementations each live in their matching file in `ketraterm-completion-host`.

## Host Ownership

Hosts are responsible for applying `TerminalCompletionPersistencePolicy` to authoritative command records and for
choosing whether and where persistence is enabled. `TerminalCompletionLearningStore` accepts compact snapshots and live feedback
events, but it is not a completion source and never contributes a second visible candidate. Completion components never
read files, scan raw shell history, spawn shells, or talk to UI frameworks.

Optional disk I/O belongs to the separately published
`ketraterm-completion-persistence` module. Its
`TerminalCompletionLearningCoordinator` owns the public fixed-path lifecycle. Learning mutates its bounded in-memory
store synchronously; one bounded worker handles only hydration, enablement, and flush controls, while one debounced,
conflated writer materializes and persists only the latest generation in a burst. Its internal repository performs bounded
load/write I/O without owning another mutex, queue, cache, or scope. The explicit flush barrier lets product disposal wait
for accepted mutations and the latest write before the lifecycle scope is cancelled.
Product hosts own the fixed destination, enablement policy, diagnostics, and the point at which graceful shutdown
becomes blocking.
Completion persistence is not a workspace responsibility.

The standalone app and IntelliJ plugin should compose completion sources through
`TerminalCompletionSources` and `TerminalCompletionEngines`, then adapt returned
candidates to their own UI presentation.

The public API should not grow by convenience. New public functions must be
durable host contracts, used by standalone/plugin integration, or explicitly
documented persistence/model contracts.
Kotlin `internal` visibility and Gradle module dependencies enforce the
implementation boundary. Do not recreate source-scanning architecture tests or
mirror permitted declarations in a second manual allowlist. Public constructors
and methods must document parameters/properties, return values, observable
failure behavior, ownership, threading, and I/O expectations where those
concepts apply.

## Command-Line Context Policy

Completion treats trailing space as a semantic boundary. With the cursor inside
`cd`, sources complete the command token. With the cursor after `cd `, sources
complete a new empty argument at the cursor. Matching learned commands are
projected to that argument, so `cd IdeaProjects/KetraTerm/` is presented and
inserted as `IdeaProjects/KetraTerm/`; the executable is not repeated in the
popup. A full command is retained only when the executable itself is active;
history rows that cannot be projected safely into the active context are not
offered.

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
their closest logical segment. Learned-history candidates are suppressed in
segments following an operator until segment-local history ownership is
implemented; replacing across an operator boundary is never inferred.

The exact shell option terminator `--` is a command-context boundary once the
cursor has moved beyond the token, such as after a following space. While the
cursor remains attached to `--`, it is a long-option prefix and may suggest
options such as `--help`. Tokens after a passed terminator remain positional: they do
not resolve as options, option values, or subcommands, and static spec option
completion stops. An incomplete prefix such as `--v` remains an option prefix
until the terminator token has been passed.

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

In command position, an existing path candidate whose replacement resolves to
a declared top-level command alias is presented with that specification's
command kind and description. The path source remains the existence authority
and retains its ranking contribution; the specification supplies semantic
presentation only. Raw and shell-encoded alias spellings are indexed when the
engine is created, so this enrichment does not re-tokenize candidates or probe
the filesystem.

Path interpretation is host-owned. The pure source emits a
`TerminalDirectoryListingRequest` containing the authoritative working-directory
URI, a transport-neutral lexical directory prefix, and the active entry-name
prefix. It does not discard URI authorities, expand `~`, or interpret drive and
UNC roots. Hosts must reject remote authorities they cannot map safely and
return bounded results from a suspending provider that cooperates with request
cancellation.

Live trigger policy is a cheap presentation check, not a second completion
parser. A small non-whitespace threshold plus common trigger characters keeps
typing responsive, while the merged engine parses once and suppresses invalid
operator, command, option, path, and value-domain requests authoritatively.

Swing hosts share `SwingLiveCompletionBinding` and one EDT-confined
one-shot `Timer` for debouncing. `SwingTerminal` owns exactly one replaceable
`suggestionJob`; a new request or popup hide cancels it. The provider and engine
remain suspending end to end. Provider construction and flow collection execute
off the EDT, and progressive rankings are conflated before the latest immutable
snapshot is published back to Swing.

Presentation is intentionally platform-owned. The standalone host custom-paints
a compact completion list; the IntelliJ plugin owns a separate native `JBList`.
Both consume `SwingShellSuggestionViewSnapshot` and the same authoritative
display text, detail, source label, semantic accent role, and matched ranges.
Physical renderers may follow their platform's visuals and mechanics but do not
reparse engine kinds or provider identifiers.

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
task set and the spec source can suggest `build` while omitting already-used tasks such as `clean`. A host may add a
suspending Gradle-task source for the same context. It completes imported root tasks, canonical module tasks such as
`:app:run`, and short names after `-p app` or `--project-dir app`; `-p`
uses a project directory, not a Gradle colon path.

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

Reusable local-path resolution and bounded directory-scanning machinery belongs to `ketraterm-completion-host`; it
may perform bounded host work but does not parse, rank, prioritize, or schedule completion candidates. Standalone
and IntelliJ retain only environment-specific suspending loaders and scanners. There is no snapshot service, TTL,
publication callback, refresh-after-publication pass, semaphore, or provider-owned job. Blocking local filesystem access
moves to an injected IO dispatcher.
Enumeration has visit, result, and elapsed-time caps. The defaults (8,192 visited entries, 256 matches, and a 50 ms scan
budget) are an explicit desktop baseline covered by JMH directory-scan benchmarks; change them only with representative
local and remote-filesystem measurements.
Direct local and project-VFS scanners retain one replace-only raw snapshot for
the last directory when an authoritative directory identity and modification
version are available. The snapshot is capped at 8,192 sorted entries and is
filtered per prefix to at most 256 source candidates. Incomplete, cancelled,
failed, or version-changing scans are never published into the cache. There is
no TTL, refresh callback, worker, or merged-candidate cache.
`runInterruptible` makes local directory scans cooperatively interruptible. The app resolves
local and `localhost` file URIs, explicit home paths,
Windows drive roots, and Windows UNC roots while rejecting non-local OSC 7 authorities. The IntelliJ plugin uses
write-allowing suspending read actions for project-aware VFS queries and bounded local scanning elsewhere. One Git
source selects the repository for the terminal working directory and reads local branches, remote branches, and tags in
one IntelliJ read action. Local branches apply to `git switch`, `checkout`, `merge`, and `rebase`; remote branches and
tags apply to `checkout`, `merge`, and `rebase`, so `git switch` remains local-branch-only.
Whole-project fuzzy paths use a prefix-keyed suspending query through IntelliJ's Go to File model and item provider.
IntelliJ owns indexed discovery, fuzzy matching, path qualification, and result ordering; the plugin only converts PSI
items into shell-facing paths, while the shared source applies terminal path semantics. These queries use IntelliJ's
suspending `readAction`, so pending write actions restart the read without a blocking-context bridge. Fuzzy paths activate only in declared or
explicitly path-like terminal positions, while direct directory completion remains higher priority for immediate
children. Changelists, SDKs, and run configurations remain follow-up work. IntelliJ also reads its already-imported Gradle external-system
model into a bounded task result; it never starts Gradle from a completion request. Every IntelliJ loader uses the
working-directory URI captured in that immutable request. A separate Git status loader
supplies changed and
untracked paths for `git add`, `restore`, `rm`, and `diff` without starting a Git process.

IntelliJ dynamic completion is composed from ordinary source-producing functions and explicit prioritized source
entries. There is no provider-factory or registration framework. The shared completion session registry owns
session MRU/path/engine composition and strict close semantics; a thin IntelliJ statistics adapter
maps host events into the shared learning coordinator. Standalone uses the same coordinator contract so completion files are
never loaded on the Swing event-dispatch thread.

The engine-to-Swing request/candidate bridge and Swing-feedback-to-statistics mapping live in `ketraterm-ui-swing-host`.
Product hosts inject context, privacy, scheduling, and persistence policy instead of copying the vocabulary conversion
logic.

Both hosts should map their data into the shared request/candidate/source
contracts and let the shared engine resolve outcomes, fuse provider evidence,
deduplicate, and rank candidates.
`ketraterm-completion` must stay pure: it should not shell out to Git, read IDE
indexes, watch files, or block on host I/O.

Learned statistics mutate one bounded exact index and publish its immutable snapshot lazily on the next ranking,
history, persistence, or explicit snapshot read. Multiple events before that read therefore avoid rebuilding the full row
list. No-op or rejected events retain the current snapshot identity.
The ranker builds one direct exact-command lookup per snapshot identity and
shell syntax, then reuses it for subsequent requests. There are no indexed
list wrappers or a second mutation-time ranking-index hierarchy.

Positive persisted command rows also have a snapshot-identity and shell-syntax
index. It groups rows by normalized tokens before the active position and
binary-searches the active-token prefix, so a hot request does not rescan the
bounded 2,048-row snapshot. The index stores pre-tokenized command lines and is
rebuilt only when the immutable snapshot identity or shell syntax changes.

The standalone host currently maps PowerShell to `POWERSHELL`, its tested
POSIX-profile categories to `POSIX`, and Command Prompt, Fish, Nushell, and
unknown profiles to `PLAIN`. Native shell completion callbacks and dialect
adapters remain host-owned future work; they must supply authoritative
replacement ranges and never be called from the shared completion hot path.

The compatibility contract is deliberately explicit:

| Capability                   | POSIX                                 | PowerShell                                  | Plain fallback                        |
|------------------------------|---------------------------------------|---------------------------------------------|---------------------------------------|
| Command separators           | `;`, `&`, `&&`, `\|`, `\|\|`          | `;`, `&&`, `\|`, `\|\|`                     | none inferred                         |
| Escape outside single quotes | backslash                             | backtick                                    | backslash tokenization only           |
| Quote recovery               | single and double                     | single and double, including doubled quotes | conservative tokenization             |
| Safe unquoted path escaping  | backslash                             | single-quoted literal                       | only values needing no dialect escape |
| Native shell callbacks       | host-owned, not invoked synchronously | host-owned, not invoked synchronously       | unavailable                           |

Command Prompt, Fish, Nushell, and unknown dialects remain on the plain
fallback until each has a tested lexical and quoting contract. This avoids
silently applying POSIX rules to incompatible shells.

## Ranking Policy

The merged engine ranks each provider locally, projects every candidate onto the
command it would produce, and groups source-independent outcomes. Shell quoting
is tokenized away for comparison. Declared path values additionally ignore only
a redundant trailing separator, allowing `cd build`, `cd build/`, and a safely
quoted equivalent to share evidence without resolving `..`, symlinks,
authorities, environment variables, or filesystem case.

Provider support uses reciprocal-rank fusion. Candidate scores are meaningful
only within their producing source; an MRU score is never compared numerically
with a path or specification score. Each distinct semantic or learned source
entry contributes its best local rank for an outcome and a source prior clamped to
`[-20, 20]`. Persistent statistics
do not constitute a source entry, so the same command execution cannot gain a
second provider vote merely because it exists in both MRU and persisted stats.
Duplicate candidates from the same source do not multiply support.

The global ranker applies the strongest semantic adjustment among contributors.
Exact outcome statistics then add bounded usage,
success/failure, accepted/dismissed, recency, profile, and working-directory
evidence. Only explicit dismissal is negative; passive popup
closure is neutral.

The edit representative favors semantic fit, a narrow replacement range, the
bounded prior, local rank, and stable declaration order. Presentation selection
cannot change that edit. Among contributors with identical replacement text and
range, it first preserves the strongest semantic fit and then prefers a primary
source over a fallback source before applying the ordinary prior and stable
tie-breakers. The selected contributor supplies the complete candidate atomically;
the engine never mixes display text, match ranges, detail, kind, or source labels
from different candidates. Session and persisted MRU candidates are presentation
fallbacks, so they strengthen matching specification or provider outcomes without
replacing authoritative metadata. Unique learned outcomes remain visible.

Returned candidate scores are the fused global score. Final ordering continues to
use the edit representative, so presentation ownership cannot change ranking.
Ordering and all tie-breakers are deterministic.

Source safety and presentation are independent. Every source receives a fixed
256-candidate safety budget. The engine globally fuses the complete bounded
union and has no popup-size parameter; the Swing controller alone presents an
eight-row sliding viewport across the ranked snapshot. The snapshot also carries
absolute overflow metadata so each physical renderer can expose range and scroll
position without gaining access to ranking state.

Source collection uses one cold structured `channelFlow`. The engine parses
once, resolves one context, launches one child per source under a supervisor,
and serially incorporates completed-source events in the parent. Each changed
global ranking is emitted immediately, so a slow Git or index source cannot
block a fast spec, MRU, or direct-path result. Individual sources remain
ordinary suspending functions and never own scopes or child jobs. A non-cancellation source failure is reported through
`TerminalCompletionSourceFailureHandler` and contributes an empty result; request cancellation reaches every child,
and source declaration order remains the deterministic final-fusion tie-breaker.

## Ranking Calibration

Ranking constants are policy, not universal truth. Changes to priors, smoothing,
recency buckets, or evidence clamps must pass the deterministic representative
replay in `CompletionRankingReplayTest`. The replay reports top-one rate,
top-three rate, and mean reciprocal rank for accepted outcomes covering paths,
Git branches, and imported Gradle tasks. New anonymized failure cases should be
added before tuning a weight so calibration cannot optimize only one provider.

Performance changes must also run `TerminalCompletionBenchmark`. The benchmark
includes eight-provider fusion, 2,048 learned rows, duplicate-heavy evidence,
hostile collection-cap input, and a real session-MRU lookup backed by the full
persisted snapshot. The persisted-history case is prewarmed deliberately: it
measures the normal learning-store-owned compiled-index cache hit, while index
construction stays bounded to first use of a new snapshot or shell syntax.
