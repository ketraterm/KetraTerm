# Persistent storage

The standalone app owns its configuration files. Library embedders choose their
own configuration and persistence locations.

## Locations

| Data | Location |
| --- | --- |
| Standalone preferences | Resolved `config.toml`; see [path resolution](../../ketraterm-app/docs/profile-config-toml.md#2-directory-resolution-hierarchy). |
| Malformed standalone configuration backup | `<config filename>.broken` beside the configuration; see [recovery behavior](../../ketraterm-app/docs/profile-config-toml.md#3-configuration-backup--fallback-lifecycle). |
| Standalone completion learning — Windows | `%LOCALAPPDATA%\KetraTerm\command-completion-learning.tsv`; without `LOCALAPPDATA`, use `~/AppData/Local/KetraTerm/`. |
| Standalone completion learning — macOS | `~/Library/Application Support/KetraTerm/command-completion-learning.tsv`. |
| Standalone completion learning — Linux and other platforms | `$XDG_STATE_HOME/ketraterm/command-completion-learning.tsv`; default `~/.local/state/ketraterm/`. |

Raw terminal output is not saved automatically. Explicit output export is a
separate user action.

XDG roots must be absolute; unset, blank, relative, or invalid values use the
default. A nondefault configuration path keeps its learning file beside it.
Existing learning moves on first enable if the destination is absent. The current
file takes precedence, followed by its old `-v3.tsv` sibling, then the previous
configuration location. Unsupported files are preserved instead of falling back
to older evidence. Reset clears all known previous locations.

## Completion learning

Saved learning is **opt-in** and requires completion to be enabled. The standalone
app provides a persistence setting and supports
`suggestion_learning_persistence_enabled` under `[behavior]`.

With persistence disabled, startup does not load the saved file and new activity
is kept in memory. Turning persistence off leaves previously saved data in place.
Unreadable or unsupported saved data is preserved rather than silently overwritten.

Use **Reset learned suggestions...** in settings to clear learned commands and
ranking preferences from memory and disk, even when suggestions or persistence
are disabled. The action asks for confirmation and reports success after saved
data is cleared. Errors allow retry, and closing settings does not cancel the
reset. The file keeps an empty format header and previous learning files are
removed. Shell history and terminal output are unaffected; later activity can be
learned according to the current settings.

Learning is saved periodically and at shutdown. The standalone app allows up to 500 ms for
the final write, so the latest activity may be lost if saving cannot finish.
Integrators should follow the [persistence lifecycle contract](../../ketraterm-completion-persistence/README.md#lifecycle-and-failures).

## File format

Version 3 starts with `KetraTerm_COMMAND_COMPLETION_LEARNING<TAB>3`.
The stable filename is independent of the schema version recorded in the header.
Ranking rows precede replay rows:

| Row | Stored fields |
| --- | --- |
| `R` | Command digest, profile, working directory, usage/success/failure/acceptance/dismissal counters, and last-used time. |
| `H` | Matching digest and context plus policy-approved successful command text. |

Text fields use unpadded Base64URL. Unsupported versions, malformed rows, and
inconsistent ranking/replay pairs reject the whole file. Derived matching data
is recomputed, not persisted.

Optional profile and working-directory metadata uses `null` for absence. Empty
strings and malformed UTF-16 are rejected before learning state changes.

## Privacy

Replay filtering excludes leading-whitespace commands, multiline/control-bearing
or oversized text, malformed UTF-16, and recognized credential patterns. A host
can further restrict replay with a predicate. Commands rejected for replay may
still contribute opaque ranking evidence; malformed UTF-16 is not learned.

Approved replay contains command text. Base64URL is encoding, not encryption;
deterministic digests can be guessed from candidate commands. Filtering is
best-effort and cannot identify every secret. Treat the learning file as
sensitive local command-derived data.
