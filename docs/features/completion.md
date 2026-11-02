# Command completion

KetraTerm's optional completion engine combines command specifications, paths,
host-provided sources, and bounded local learning. It can run independently of
a terminal or use the Swing/IntelliJ suggestion UI.

## Stability: experimental

The entire completion and suggestion subsystem is **experimental**.
This includes the engine, command catalog, source and shell integrations,
ranking and learning, persistence, and suggestion UI extension points.
Suggestions may be incomplete, rank poorly, or behave unexpectedly in edge cases.
Experimental status applies even when suggestions are enabled by default.
APIs may also change incompatibly before stabilization, requiring consumers to
adapt integrations when upgrading.

Kotlin consumers receive warnings and can opt in to `ExperimentalTerminalCompletionApi`
for completion contracts and `ExperimentalShellSuggestionsApi` for Swing suggestion
providers, views, and interactions. Both markers use `RequiresOptIn.Level.WARNING`.
Using these APIs without opt-in still compiles unless the consumer treats warnings
as errors (for example, with `allWarningsAsErrors`).
The Swing completion adapter uses both APIs. Opting in acknowledges these quality
limitations and the experimental API contract; it does not enable suggestions or
change runtime behavior.
Java callers receive the documented stability contract but Kotlin's compiler
opt-in checks do not apply to Java compilation.

## Availability

Completion and automatic suggestions are **enabled by default in both products**.
Settings expose the master switch, automatic popups, Enter acceptance, and optional
learning persistence. Standalone users can also set `smart_suggestions_enabled`
in the [TOML configuration](../../ketraterm-app/docs/profile-config-toml.md).
Explicit saved preferences remain effective.
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
| Live PowerShell completion | Commands, session functions, aliases, and installed argument completers through the current PSReadLine session. | Same native bridge. |
| Bash command inventory | Commands, aliases, functions, and builtins discovered in the shell at each prompt. Requires Bash 5.3+ and `base64`. | Same inventory and command authority. |
| Files and directories | Bounded local directory scans. | IDE virtual filesystem with local scan fallback. |
| Project-wide files | No project index. | Project filename lookup and matching. |
| Git branches and tags | Bounded local Git queries for modeled reference arguments. | Local/remote branches and tags from IDE repositories. |
| Recent Git commits | No dynamic source. | Up to 50 commits for supported commit arguments. |
| Git changed paths | Ordinary path completion. | IDE change-list paths for supported Git commands. |
| Gradle tasks | Built-in lifecycle tasks. | Also tasks from the imported Gradle model. |
| Learned commands and tokens | In-memory learning when enabled. | In-memory learning when enabled. |

Local filesystem sources require locally resolvable paths. WSL or remote shell
metadata does not provide a remote filesystem completion service.
Neither product substitutes local data for explicitly unsupported directory
metadata. For Git commands in both products, completed `-C` values select the source directory in
input order for the available reference, commit, changed-path, and ordinary/project path sources;
relative values build on the preceding directory and empty values leave it unchanged.
IntelliJ's captured project directory is a fallback only when shell directory metadata is absent.

Standalone queries the host's `git for-each-ref` for local branches; `checkout`,
`merge`, and `rebase` also receive remote branches and tags. The current branch
and symbolic remote aliases are omitted. Each eligible request refreshes the data,
with at most 8192 refs, 1 MiB of output, and a 1.5-second process deadline.
Cancellation terminates the query. Requests without a resolvable local directory
do not launch Git. Queries use the selected directory rather than inherited
repository-selection variables, and pass fixed arguments directly to Git without
evaluating the user's command line. The executable and remaining environment come
from the host process; shell-local toolchain changes are not mirrored by this source.
Unavailable Git, non-repositories, and exceeded budgets leave the other completion
sources available. IntelliJ continues to use its repository model.

PowerShell's live provider calls `TabExpansion2` inside the running shell. It requires
the installed prompt hooks and PSReadLine. At command positions, the shell supplies
the available commands; catalog descriptions enrich verified matches rather than
advertising uninstalled tools. Empty prompts use bounded `Get-Command` discovery.
Executable extensions merge with bare invocations only when PowerShell resolves
both to the same file; aliases or functions that shadow the bare name stay distinct.
Modeled arguments prefer specs and host sources, with native fallback when they
produce no candidates. Learned history does not suppress that fallback; its
publication waits until a pending native query finishes. Unknown functions/arguments use native completers before
generic file guesses. Directory arguments reject native files, and equivalent
PowerShell path spellings merge into one result.
Escaped replacement text and ranges remain shell-owned. Requests are serialized and
expire after two seconds; cancellation cannot interrupt a completer already running
inside PowerShell. Native results remain subject to bounded protocol validation
and stale-edit checks. The OSC producer still requires an unambiguous visible
command line with the cursor at its end.

Bash starts command discovery at each interactive terminal prompt, after user startup files
and environment overrides have run. Discovery runs in a background shell snapshot
so scanning a large `PATH` does not delay prompt editing. Bare command suggestions use its inventory
as availability authority, while catalog descriptions and existing ranking enrich
the matches. This also applies to explicitly configured Bash inside WSL: command
discovery uses the guest's environment, never the Windows host's `PATH`.
The inventory includes at most 8192 names within a 180 KB raw payload; names over
512 UTF-8 bytes are omitted. Changes to functions, aliases, and `PATH` take effect
after discovery for a subsequent prompt completes. A completed inventory remains
usable while the same environment refreshes. Changes to `PATH`, the directory,
or alias/function/builtin names invalidate it immediately. Only one scan may run
at a time; results for superseded environments are discarded and a later prompt
retries. Initial discovery and invalidated inventories supply no command candidates
until a matching scan completes.
Reading the inventory sends no input and causes no prompt redraw.
On Git Bash/Cygwin, `.exe` commands use the bare insertion only when Bash resolves
both spellings identically. Shadowing aliases and functions remain distinct;
Unix executable suffixes remain literal.

Bash argument and executable-path suggestions continue to use existing specs and
host sources. Installed Bash argument completers, guest filesystem completion, and
zsh native completion are not integrated. Older Bash and shells without an inventory
retain their existing sources. Normal shell Tab completion remains available.

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
| Context | Active command segment, preceding arguments, case-sensitive option identity, option values, and end-of-options handling. |
| Command specifications | Aliases, nested/repeatable subcommands, ordered/variadic arguments, literal values, path kinds, and mutually exclusive option groups. |
| Shell syntax | POSIX and PowerShell subsets, plus conservative plain parsing; see the [shell matrix](shells.md). |
| Paths | Directory traversal, shell-aware escaping, and hidden entries when explicitly requested. |
| Ranking | Merged sources, deterministic ordering, contextual priority, and bounded learning boosts. |
| Progressive results | Sources can update results asynchronously; obsolete requests are cancelled. |
| Presentation | Compact matched-text rows, keyboard/pointer navigation, descriptions and source metadata in tooltips/accessibility, and custom UI support. |
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
