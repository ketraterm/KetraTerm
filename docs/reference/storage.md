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

Saved learning is **opt-in** and requires completion to be enabled. Both products
provide a persistence setting; standalone also supports
`suggestion_learning_persistence_enabled` under `[behavior]`.

With persistence disabled, startup does not load the saved file and new activity
is kept in memory. Turning persistence off leaves previously saved data in place.
Unreadable or unsupported saved data is preserved rather than silently overwritten.

Use **Reset learned suggestions...** in settings to clear learned commands and
ranking preferences from memory and disk, even when suggestions or persistence
are disabled. The action asks for confirmation and reports success after saved
data is cleared. Errors allow retry, and closing settings does not cancel the
reset. The file keeps an empty format header. Shell history and terminal output
are unaffected; later activity can be learned according to the current settings.

Learning is saved periodically and at shutdown. Products allow up to 500 ms for
the final write, so the latest activity may be lost if saving cannot finish.
Integrators should follow the [persistence lifecycle contract](../../ketraterm-completion-persistence/README.md#lifecycle-and-failures).

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
