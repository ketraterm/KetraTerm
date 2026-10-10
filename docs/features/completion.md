# Command completion

KetraTerm's optional completion engine combines command specifications, paths,
host-provided sources, and bounded local learning. It can run independently of
a terminal or use the Swing/IntelliJ suggestion UI.

## Availability

Completion is **disabled by default in both products**. Its settings controls
are currently hidden. Standalone users can enable `smart_suggestions_enabled`
in the [TOML configuration](../../ketraterm-app/docs/profile-config-toml.md);
the IntelliJ plugin retains persisted preferences but exposes no settings toggle.
Library hosts enable and configure their own provider and presentation.

Terminal suggestions require an editable command context, normally supplied by
[shell hooks](shells.md#prompt-hooks-and-completion-dialects).
Unsupported shells do not gain command tracking merely by enabling completion.
Custom hosts can supply their own context and editing authority.

## Sources by host

This table describes the sources available when completion is enabled.

| Source | Standalone | IntelliJ plugin |
| --- | --- | --- |
| Built-in command specifications | Yes | Yes |
| Files and directories | Bounded local directory scans. | IDE virtual filesystem with local scan fallback. |
| Project-wide files | No project index. | Project filename lookup and matching. |
| Git branches and tags | No dynamic source. | Local/remote branches and tags from IDE repositories. |
| Recent Git commits | No dynamic source. | Up to 50 commits for supported commit arguments. |
| Git changed paths | Ordinary path completion. | IDE change-list paths for supported Git commands. |
| Gradle tasks | Built-in lifecycle tasks. | Also tasks from the imported Gradle model. |
| Learned commands and tokens | In-memory learning when enabled. | In-memory learning when enabled. |

Local filesystem sources require locally resolvable paths. WSL or remote shell
metadata does not provide a remote filesystem completion service.

## Built-in command catalog

The catalog provides selected subcommands, options, and argument domains; it is
not an exhaustive schema for every version of these tools.

| Group | Commands |
| --- | --- |
| Files and navigation | `cd`, `pushd`, `ls`, `cat`, `mkdir`, `rm`, `cp`, `mv` |
| Version control | `git`, `gh` |
| Build and languages | `gradle` / `gradlew`, `cargo`, `go`, `kotlin`, `kotlinc` |
| Package managers | `npm`, `pnpm`, `yarn`, `bun`, `pip` |
| Containers and cloud | `docker`, `docker-compose`, `kubectl`, `aws` |
| Developer tools | `code`, `adb`, `ketra` |

## Matching and interaction

| Feature | Description |
| --- | --- |
| Matching | Exact, prefix, CamelHump, word-boundary/acronym, and substring matching. |
| Context | Active command segment, preceding arguments, option values, and end-of-options handling. |
| Command specifications | Aliases, nested/repeatable subcommands, ordered/variadic arguments, literal values, path kinds, and mutually exclusive option groups. |
| Shell syntax | POSIX and PowerShell subsets, plus conservative plain parsing; see the [shell matrix](shells.md). |
| Paths | Directory traversal, shell-aware escaping, and hidden entries when explicitly requested. |
| Ranking | Merged sources, deterministic ordering, contextual priority, and bounded learning boosts. |
| Progressive results | Sources can update results asynchronously; obsolete requests are cancelled. |
| Presentation | Matched-text highlighting, descriptions, source labels, keyboard/pointer navigation, and custom UI support. |
| Acceptance | A stale editing context is rejected before replacement is admitted. |

With completion enabled, Ctrl+Space requests suggestions. The embedded view uses
Tab to accept, arrows/Page Up/Page Down to navigate, and Escape to dismiss.
Enter acceptance and automatic popups are preferences; IntelliJ also uses its
active keymap. Shell-native Tab completion remains a separate facility.

## Library customization

| Extension | Host control |
| --- | --- |
| Catalog | Replace or extend command specifications and localize descriptions without changing command tokens. |
| Sources | Register custom asynchronous sources with bounded priorities and a failure-reporting callback. |
| Source adapters | Supply directory listings, query-aware fuzzy paths, imported Gradle tasks, or dynamic argument values. |
| Learning | Supply a shared learning store and replay policy; enable ranking evidence independently of command replay. |
| Presentation | Replace the provider, popup view, suggestion keymap, or entire presentation target; customize source labels. |
| Editing and feedback | Supply request-scoped edit authority and observe acceptance/dismissal outcomes. |

The engine performs no host I/O itself. Source adapters use host-supplied data;
the Gradle adapter does not run Gradle to discover tasks. Ordinary source
exceptions are reported while other sources continue; request cancellation
cancels source work.

## Learning and privacy

Learning adjusts matching candidates using executions, acceptance, and explicit
dismissal. Successful commands admitted by the replay policy can also supply
history and observed-token suggestions. Hosts may independently disable replay
retention while keeping ranking evidence.

Persistence is opt-in. Approved replay may contain plaintext command data;
filtering cannot identify every secret. See the [storage and privacy contract](../reference/storage.md)
for location, retention, and encoding.

For integration, use the [completion README](../../ketraterm-completion/README.md),
[Swing adapter guide](../../ketraterm-ui-swing-host/README.md#completion-bridge),
and [engine architecture](../../ketraterm-completion/docs/completion-architecture.md).
