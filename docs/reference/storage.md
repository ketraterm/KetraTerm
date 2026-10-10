# Persistent storage

The standalone app owns its configuration files. IntelliJ uses IDE-managed
application/project settings. Library embedders choose their own persistence.

## Locations

| Data | Location |
| --- | --- |
| Standalone preferences | Resolved `config.toml`; see [path resolution](../../ketraterm-app/docs/profile-config-toml.md#2-directory-resolution-hierarchy). |
| Malformed standalone configuration backup | `<config filename>.broken` beside the configuration; see [recovery behavior](../../ketraterm-app/docs/profile-config-toml.md#3-configuration-backup--fallback-lifecycle). |
| Standalone completion learning | `command-completion-learning-v3.tsv` beside the configuration. |
| IntelliJ completion learning | `ketraterm/command-completion-learning-v3.tsv` under the IDE system directory. |
| IntelliJ tab state | Project workspace storage; see [tab restoration](../features/desktop.md#product-integration). |

Raw terminal output is not saved automatically. Explicit output export is a
separate user action.

## Completion learning

Persistence is opt-in and requires completion to be enabled. Standalone uses
`suggestion_learning_persistence_enabled` under `[behavior]`. Product settings
controls are currently hidden; see [completion availability](../features/completion.md#availability).

A product starting with persistence disabled neither loads nor writes the
learning file. Enabling hydrates the fixed file once. Disabling prevents new
writes but does not clear in-memory learning or cancel a file operation already
in progress. An unreadable/rejected file blocks ordinary writes until an explicit
learning reset or a new coordinator lifecycle.

Dirty state is checkpointed every 30 seconds and on shutdown. Products allow up
to 500 ms for final persistence, so shutdown is not an unconditional durability
guarantee. Files are bounded; replacement is atomic where the filesystem supports
it, with ordinary replacement as a fallback. The
[persistence module](../../ketraterm-completion-persistence/README.md) defines worker,
failure, and lifecycle contracts.

## File format

Version 3 starts with `KetraTerm_COMMAND_COMPLETION_LEARNING<TAB>3`.
Ranking rows precede replay rows:

| Row | Stored fields |
| --- | --- |
| `R` | Command digest, profile, working directory, usage/success/failure/acceptance/dismissal counters, and last-used time. |
| `H` | Matching digest and context plus policy-approved successful command text. |

Text fields use unpadded Base64URL. Unsupported versions, malformed rows, and
inconsistent ranking/replay pairs reject the whole file. Derived matching data
is recomputed, not persisted.

## Privacy

Replay filtering excludes leading-whitespace commands, multiline/control-bearing
or oversized text, malformed UTF-16, and recognized credential patterns. A host
can further restrict replay with a predicate. Commands rejected for replay may
still contribute opaque ranking evidence; malformed UTF-16 is not learned.

Approved replay contains command text. Base64URL is encoding, not encryption;
deterministic digests can be guessed from candidate commands. Filtering is
best-effort and cannot identify every secret. Treat the learning file as
sensitive local command-derived data.
