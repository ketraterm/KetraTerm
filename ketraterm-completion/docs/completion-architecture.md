# Completion Module Architecture

`ketraterm-completion` turns an immutable command-line request into progressive
ranked suggestions. It owns semantic parsing, source coordination, ranking, and
bounded in-memory learning. Hosts own environment access and interaction. Start
with the [README](../README.md) for construction and a runnable example.

## Public Surface

Consumers use `io.github.ketraterm.completion.api` and
`io.github.ketraterm.completion.model`.

| Contract | Purpose |
|----------|---------|
| `TerminalCompletionRequest` | Captured command text, UTF-16 cursor, working-directory URI, profile, and shell capabilities. |
| `TerminalCompletionContext.resolve` | Public request resolution for custom engines and direct source evaluation. |
| `TerminalCompletionEngine` | Cold `Flow` of best-first candidate snapshots. |
| `TerminalCompletionSource` | One suspending provider evaluated against the shared semantic context. |
| `TerminalCompletionEngines` / `TerminalCompletionSources` | Engine composition and adapters for paths, Gradle tasks, and dynamic values. |
| `TerminalCommandSpec`, `TerminalOptionSpec`, `TerminalArgumentSpec` | Declarative command grammar and value metadata. |
| `TerminalCompletionLearningStore` | Bounded learning, immutable snapshots, and host-recorded outcomes. |

Candidate replacement offsets address the original request's command line.
They are inclusive at the start, exclusive at the end, and must preserve UTF-16
scalar boundaries. The engine filters edits outside the request or not spanning
its cursor before ranking. `displayText`, `detail`, and `matchedRanges` describe
presentation; `replacementText` supplies insertion. `TerminalCompletionMatchRanges`
defensively copies its primitive storage and validates display boundaries.

`feedbackToken` is optional provider-owned runtime context. Fusion preserves the
chosen presentation candidate's token without interpreting it. Source labels
and row indices are not provider identities; feedback is not broadcast to
contributors hidden by deduplication.

`TerminalCompletionMessages` localizes generated descriptions.
`TerminalCommandSpecs.defaults(locale)` and its bundle/callback overloads
localize catalog descriptions and argument labels while preserving canonical
tokens and metadata. Engine and path-source construction resolve static labels;
parameterized descriptions, such as Gradle task fallback text, may be formatted
during evaluation. Message providers must be thread-safe. See the shared
[configuration contract](../../docs/library/configuration.md#optional-host-chrome-and-labels).

## Internal Implementation

Implementation packages (`commandline`, `engine`, `internal`, `matching`,
`ranking`, `source`, `spec`, and `stats`) contain internal declarations.
External modules do not import them.

One token pass selects the active command segment, and one context resolver
uses the engine's command catalog to classify the cursor. The engine shares the
result with static and host sources and the global ranker. The same catalog
supplies the static source automatically.

Each request captures one compiled learning-index set before source evaluation.
The set contains opaque ranking lookup, eligible replay history, and derived
observed tokens. A snapshot-identity and shell-syntax cache reuses compiled
indexes across requests. Learning mutations during evaluation affect the next
request, keeping one request internally consistent.

### Custom engines and direct source evaluation

`TerminalCompletionContext.resolve(request, commandSpecs)` uses the same parsing
and spec resolution as the stock engine. A custom engine resolves once and
passes that context with the original request to `TerminalCompletionSource.complete`
or `TerminalCompletionSources.valueDomainCandidates`. The default catalog is
bundled; an empty catalog provides lexical context without inferred command,
option, or positional metadata.

Resolution performs no host I/O, scheduling, ranking, or caching. Empty, unknown,
and incomplete lines produce partial contexts. At an `OPERATOR` position,
callers should skip source evaluation. Custom engines own candidate limits,
replacement validation, ranking, source concurrency, failure handling, and
cancellation.

Context-owned collections are immutable and can be retained across suspension
or overlapping requests. Command specs are referenced rather than deep-copied:
keep their nested collections unchanged while any context uses them. A new
catalog for later requests does not invalidate an earlier context.

### Context argument history

Sources can query decoded arguments before the active word without tokenizing
again. These request-owned immutable values include only the cursor's command
segment; the active word is excluded even at its end, as are all later words.
Quotes and escapes follow the captured shell syntax, empty quoted words remain
empty strings, and no variable, glob, or command expansion occurs.

- `precedingArguments` includes subcommands, option names, values, and `--` in
  input order. It excludes leading environment assignments and the executable,
  works for unknown commands, and is empty at command or operator positions.
- `precedingPositionalArguments` uses the matched spec to exclude resolved
  subcommands, options, option values, and the terminator. After `--`, words are
  positional. It is empty for unknown commands; unknown options do not acquire
  inferred value ownership.
- `precedingOptionValues(optionName)` returns all completed values for a known
  option, including repeats, inherited options, and empty values. Any declared
  alias is accepted using trimmed, case-insensitive spec lookup. Separate and
  `--name=value` forms share decoded value semantics. Unknown options, valueless
  flags, pending values, and the active word are excluded. The caller chooses
  first, last, or all values; no last-value-wins policy is imposed.

### Evaluation and failure behavior

Collection starts a cold structured coroutine flow. The engine evaluates specs
and retained learning first, then launches one child per host source. Completed
sources are incorporated serially, and a changed global ranking is emitted
without waiting for slower sources. Each emission replaces the preceding
snapshot; unchanged rankings are not emitted again. Normal completion leaves
the final result available to the caller.

Every source receives an output limit of 256. The engine also bounds each source
result and returns at most 256 globally ranked candidates per snapshot. These
are safety limits independent of the number of rows a UI presents. Source
implementations match, filter, and encode before applying their output limit.
Their host loaders need separate enumeration or time budgets; the candidate
limit is not an I/O budget or a request deadline.

Ordinary source exceptions reach `TerminalCompletionSourceFailureHandler` and
contribute an empty result. Unexpected errors are reported, fail collection,
and cancel sibling sources. Independent source cancellation contributes an
empty result; cancelling the request collection cancels all source work.
Failure handlers may run concurrently and must return promptly without
throwing. Sources must cooperate with cancellation and must not launch their
own jobs. A shared engine can evaluate concurrent requests, so host providers
must safely support that use.

## Host Ownership

Hosts capture command text and context together, select authoritative shell
capabilities, and retain the original request while candidates are displayed.
They cancel obsolete collections and admit edits only against the matching
editing state. Applying an edit, sending terminal input, and executing a command
are separate host responsibilities.

Environment-specific providers may perform bounded suspending I/O. They use the
request's captured working directory rather than resampling mutable session
state. Blocking APIs need an appropriate dispatcher; normal absence or
unsupported context may return no matches, while operational failures propagate
to the engine's diagnostic boundary.

See the [module guide](../Module.md#integration-boundaries) for host adapters.

### Learning and privacy

`TerminalCompletionLearningStore` serializes mutations around one bounded exact
aggregate; its default capacity is 2,048 rows. Published snapshots are immutable
and retain identity while their contents are unchanged. `mergeSnapshot` adds
aggregate events with saturating counters; merging the same event set twice
would count it twice. Hosts control hydration and persistence.

Learning has two representations:

- Opaque ranking evidence uses a case-sensitive SHA-256 command identity and
  retains execution and explicit accepted/dismissed feedback statistics.
- Plaintext replay supplies history and observed-token candidates only for
  successful commands approved by `TerminalCompletionReplayPolicy` and the
  store's optional host `replayFilter`.

`TerminalCompletionLearningStore()` and its capacity overload use the built-in
policy alone. The constructor accepting `replayFilter: Predicate<String>` adds
a restriction for execution admission and imported replay rows. Returning
`false` for every command disables plaintext replay while preserving opaque
evidence; returning `true` cannot bypass the built-in policy.

The filter runs synchronously outside the store lock, only after built-in
approval. It may run concurrently and must be thread-safe and consistent for
the store's lifetime. Exceptions propagate before the recording or merge
operation mutates evidence. Admission filtering does not retroactively remove
retained rows. `clear()` removes all retained evidence and replay while
preserving the configured filter and capacity.

Blank, multiline, or malformed UTF-16 command events are ignored. Plaintext
eligibility additionally limits text to 4,096 UTF-16 code units and 8,192 UTF-8
bytes, rejects ISO controls other than internal tabs, respects leading-space or
tab privacy, and applies a conservative credential classifier. Approval cannot
prove that text contains no secret. Opaque identities also allow guessed common
commands to be checked by hashing; they are not an anonymity guarantee.

Replay history and observed tokens require the recorded profile and canonical
working directory to equal the request context, including null. Unknown context
is not a wildcard. Directory canonicalization trims surrounding whitespace and
normalizes a trailing slash; it does not resolve filesystem aliases. Opaque
ranking evidence may fall back from exact context to directory-only,
profile-only, and global evidence.

Suggestion feedback never creates replay rows. Explicit dismissal supplies
negative evidence; passive closure and rejected editing attempts should not be
recorded as dismissal. Successful command executions are the authority for
replay. Hosts choose whether to collect data and where to persist it.

## Command-Line Context Policy

Hosts choose `TerminalShellCapabilities.POSIX`, `POWERSHELL`, or `PLAIN`; the
engine does not infer syntax from command text or profile ids. These are
completion lexical and replacement contracts, rather than full shell parsers.

| Capability | POSIX | PowerShell | Plain fallback |
|------------|-------|------------|----------------|
| Command separators | `;`, `&`, `&&`, `\|`, `\|\|` | `;`, `&&`, `\|`, `\|\|` | None inferred |
| Escape outside single quotes | Backslash | Backtick | Backslash tokenization |
| Quote recovery | Single and double quotes | Single and double quotes, including doubled quotes | Conservative tokenization |
| Unsafe unquoted replacements | POSIX escaping | Single-quoted literals | Omitted |

Operators inside quotes or escaped by the selected dialect do not split
segments. The start of an operator belongs to the left segment; the interior of
a multi-character operator is an `OPERATOR` region with no candidates; after
the operator, completion starts a right segment. Incomplete quotes remain
tokenizable. Learned candidates are suppressed in segments following an
operator.

Trailing whitespace starts a new argument. Completing `cd` targets the command;
completing `cd ` targets an empty argument. A learned `cd project/` is projected
as `project/` in that argument context. History entries that cannot be projected
safely are omitted.

The exact `--` token ends option and subcommand resolution after the cursor has
passed it. While the cursor remains attached to `--`, it is still an option
prefix. Later tokens are positional. Attached option values such as
`--output=text` replace only the value after `=` and share the separate-value
context used by `--output text`.

Specifications describe static values through `valueCandidates`, host-loaded
values through `TerminalCompletionValueDomain`, and paths through
`TerminalPathArgumentKind`. An active option's value metadata is authoritative,
including `NONE`; it does not fall back to positional metadata.
`positionalArguments` takes precedence over scalar positional fields and can
declare an optional argument or a variadic final argument.
`exclusiveGroupIds` suppresses conflicting completed options before the cursor.
`repeatableSubcommands` models sibling task values such as `gradle clean build`.

Path sources activate for explicit path-like command prefixes or declared path
arguments. The expected path kind filters entries; `TerminalHiddenPathPolicy`
controls dot-prefixed entries. Its default hides them for an empty prefix and
reveals them after a leading dot. Shell quoting preserves an existing quote
style when safe and uses the request's replacement policy otherwise.

For an unknown executable, approved replay can derive the first non-option
argument and option names as observed `ARGUMENT` candidates. It does not infer a
command grammar, later positional values, or option values. Those tokens are
compiled from replay and are not a separate persisted learning family.

## Host Dynamic Providers

`TerminalCompletionSources` supplies adapters with distinct loader contracts:

| Factory | Host supplies | Shared source supplies |
|---------|---------------|------------------------|
| `path` | Bounded matching directory children for a lexical request. | Path eligibility, quoting, replacement, and local ranking. |
| `fuzzyPath` | Already-matched, relevance-ordered entries from a bounded query. | Terminal path rules and final output limit. |
| `gradleTask` | Complete host-bounded task snapshot. | Gradle matching, project scoping, replacement, and limit. |
| `valueDomain` | Complete host-bounded values for the declared domain. | Matching, quoting, replacement, and limit. |

Fuzzy providers own fuzzy matching; Gradle and value-domain providers leave
matching to the shared source. These loaders receive the immutable request and
resolved context, not the engine's output limit. They require an independent
visit, input, result, or time budget. A Gradle loader reads its available model;
completion must not start Gradle to discover tasks.

`valueDomainCandidates` projects already-loaded groups for aggregate providers
without constructing a nested adapter per request. Optional canonical command
restrictions narrow a provider's validity. Fuzzy paths require typed path text
by default; a context-specific provider can permit an empty prefix.

Direct path requests preserve a lexical directory prefix and the authoritative
working-directory URI. Hosts interpret home, drive, UNC, and authority semantics
and reject locations they cannot safely map. The completion engine does not
probe filesystems or turn remote authorities into local paths.

## Ranking Policy

Sources rank locally before global fusion. Source-local numeric scores are not
compared across providers. The global ranker projects candidates onto the command
they produce, groups equivalent outcomes, and combines each distinct source's
best local rank using reciprocal-rank fusion. Duplicate rows from one source do
not multiply support. `TerminalCompletionSourceEntry.priority` contributes a
small prior clamped to `[-20, 20]`; named `TerminalCompletionSourcePrior` values
provide the shared baseline for built-in source families.

Outcome comparison accounts for shell quoting. Declared path values also ignore
a redundant trailing separator; it does not resolve `..`, symlinks, URI
authorities, environment variables, or filesystem case.

Semantic context and bounded exact learning evidence adjust the fused result.
Failed executions do not penalize ordering. Ranking evidence never becomes an
extra provider vote. Learned candidates act as a fallback, allowing live semantic
providers to supply presentation while matching learned evidence strengthens
the same outcome.

The edit representative favors semantic fit, narrower replacement ranges, the
source prior, local rank, and stable tie-breakers. Presentation selection can
choose only a contributor with the same edit. Its complete candidate metadata,
including `feedbackToken`, is preserved together; the engine replaces only its
score with the fused global score. Presentation selection does not change the
admitted edit or final ordering. Ordering is deterministic for the same request,
source results, learning snapshot, and evaluation time.

## Ranking Calibration

Use `CompletionRankingReplayTest` as the deterministic gate for changes to
priors, recency, smoothing, and evidence clamps. Its representative path, Git,
and Gradle cases check top-one rate, top-three rate, and mean reciprocal rank.
Add anonymized failure cases before changing weights.

Performance changes should compare `TerminalCompletionBenchmark` workloads in
`ketraterm-benchmarks`. They include multi-provider fusion, duplicate-heavy
evidence, hostile output bounds, and learned history backed by a full snapshot.
The prewarmed history case measures the compiled-index cache hit; first-use
compilation is separate work.
