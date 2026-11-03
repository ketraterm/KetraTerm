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
| Bash command inventory | Commands, alias definitions, functions, and builtins discovered in the shell at each prompt. Requires Bash 5.3+ and `base64`. Literal aliases inform argument semantics. | Same inventory and command authority. |
| zsh command inventory | Enabled commands, ordinary alias definitions, functions (including autoloads), and builtins discovered at each prompt. Requires `zsh/parameter` and `base64`; verified with zsh 5.9. Literal aliases inform argument semantics. | Same inventory and command authority. |
| Files and directories | Bounded local directory scans. | IDE virtual filesystem with local scan fallback. |
| Project-wide files | No project index. | Project filename lookup and matching. |
| Git branches and tags | Bounded local Git queries for modeled reference arguments. | Local/remote branches and tags from IDE repositories. |
| Git remote names | Bounded `git remote` query of local configuration. | Configured remotes from the selected IDE repository. |
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

After a completed `--`, Git `checkout` and `reset` suggest only paths, including
`git checkout <ref> -- <path>`. Other commands retain their declared argument
semantics after `--`. `push`, `pull`, and `fetch` first suggest configured remote
names (or local repository directories), then reference arguments. Push offers
local branches, including the current branch, and tags. Fetch/pull suggest locally
known tracking branches of the selected remote without its prefix; for example,
`git fetch origin fe` can insert `feature/x` from `origin/feature/x`. Configured
remotes are available even before any refs have been fetched. These sources make
no network requests and do not claim to enumerate the remote server's current refs.

Git modes refine these arguments: `push --delete` offers the selected remote's
locally known branches; `fetch --multiple` offers remaining configured remotes.
`fetch --all` stops repository/ref suggestions, and `push --all` (or `--branches`)
and `push --mirror` stop ref suggestions after the repository. Options remain
available in these modes. `push --tags` still accepts explicit refspecs.

For `push`, `fetch`, and `pull`, ordinary `[+]<source>:<destination>` refspecs
complete the component at the cursor. Push reads the source locally and the
destination from the selected remote's tracking branches; fetch/pull reverse
those roles. Empty-source push deletion (`:branch`) is supported. Plain tokens
receive a component-only edit, preserving the force marker and other component.
Quoted or escaped tokens use shell encoding to preserve both components' literal
values. Whole-command history is suppressed while editing a composite refspec.
Wildcard, negative, and malformed multi-colon refspecs are left untouched.

Standalone queries the host's `git for-each-ref` for references and `git remote`
for remote names; `checkout`, `merge`, and `rebase` receive remote branches and tags.
The current local branch is included for push sources and local refspec destinations;
otherwise it is omitted. Symbolic remote aliases are
also omitted. Each eligible request refreshes the data,
with at most 8192 values, 1 MiB of output, and a 1.5-second process deadline.
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
Typed non-path arguments, such as Git branch names, exclude generic filesystem
suggestions even when the prefix contains `/`. Specs that accept both paths and
references, including `git checkout` and `git reset`, retain both sources.
For non-path value domains, equally relevant live results precede history-only
suggestions. Favorite paths and command sequences retain learning-based ordering.
Learning still helps order live candidates; duplicate outcomes keep
the live provider's edit, presentation, and feedback together.
Escaped replacement text and ranges remain shell-owned. Requests are serialized and
expire after two seconds; cancellation cannot interrupt a completer already running
inside PowerShell. Native results remain subject to bounded protocol validation
and stale-edit checks. The OSC producer still requires an unambiguous visible
command line with the cursor at its end.

Bash and zsh check command discovery at each interactive terminal prompt, after user startup files
and environment overrides have run. Filesystem discovery runs in a background shell snapshot. Bounded shell-local
metadata is published at the prompt without filesystem enumeration. Bare command suggestions use the inventory
as availability authority, while catalog descriptions and existing ranking enrich
the matches. This also applies to explicitly configured Bash or zsh inside WSL: command
discovery uses the guest's environment, never the Windows host's `PATH`.
Shell-local commands and PATH executables have independent, bounded snapshots. Each
source publishes only after its complete snapshot passes validation. The merged
inventory is limited to 32,768 entries and 2,097,152 retained UTF-16 text units;
each source additionally allows at most 4 MiB decoded wire data. Overflow is
unavailable, never a successful truncated snapshot. Names over 512 UTF-8 bytes
are omitted.

Bash and zsh share a PATH collector that checks executable basenames from inside
each directory, avoiding repeated traversal of mounted or network ancestors.
It preserves executable-permission checks, hidden names, empty and relative PATH
entries, and valid shell command hashes. Directory and glob changes are confined
to a subshell; discovery never evaluates command names or alias definitions.
The collector rejects overflow or incomplete output instead of publishing a
successful partial inventory. Directory glob expansion itself still depends on
the size of each directory; the snapshot limits are not a shell-process heap cap.

Aliases, functions, and builtins publish before expensive PATH discovery. Their
changes refresh shell-local authority while retaining valid executable results.
Shell-local names take precedence over matching executable names. PATH changes
invalidate executable authority immediately; directory changes do so only when
PATH has an empty or relative entry. Ready sources remain usable while another
source is pending or unavailable. READY therefore means verified candidates are
available, not that every discovery source has finished.

At most one background PATH worker runs at a time. An unchanged PATH refresh is eligible
60 seconds after a prompt observes worker completion, and starts at a subsequent
prompt. Shell-local changes publish at the next prompt even while PATH discovery is busy.
A PATH change during a running scan requires another prompt after it finishes. Results for superseded source
epochs are discarded. Failed refreshes retain the previous complete snapshot for
the same source environment. Prefix lookup uses the sorted snapshot and alias
lookup is built at publication. These snapshots are per session and memory-only.
See the [inventory protocol](../reference/protocol.md#private-bash-command-inventory)
for validation, memory bounds, and lifecycle details.
Reading the inventory sends no input and causes no prompt redraw.
Background PATH results use a private per-session file mailbox, not the terminal
byte stream. The worker atomically replaces a complete snapshot; the workspace
checks for replacement every 100 ms on its I/O dispatcher. Shell-local metadata
and environment announcements remain foreground prompt output. Early files wait
for their matching live environment announcement; obsolete files cannot restore
invalidated authority. Files and polling are released on session closure.
The mailbox admits at most 8 MiB of encoded data and retains at most one pending
file in memory, in addition to the decoded inventory budgets above.
On Git Bash/Cygwin, `.exe` commands use the bare insertion only when Bash resolves
both spellings identically. Shadowing aliases and functions remain distinct;
Unix executable suffixes remain literal.

Completed, unquoted Bash and ordinary zsh aliases resolve to catalog commands for argument suggestions.
For example, `g='git'`, `gs='git switch'`, and `gr="git -C 'repo folder'"` share the
existing Git semantics and repository selection. Acceptance edits the original
argument and preserves the typed alias. Literal chains and self aliases with fixed
arguments are supported; quoted or escaped command words bypass alias expansion.
Resolution is bounded to 16 definitions, 64 expanded words, and 4096 UTF-16 units
per definition. Cycles, assignments, operators, substitutions, globbing, incomplete
quotes, and trailing-blank expansion are not interpreted. Such aliases do not inherit
a potentially shadowed catalog command's grammar; ordinary unknown-command sources
remain available. Alias metadata is never executed.
zsh discovery excludes disabled functions/builtins and aliases when alias expansion
is disabled. Global and suffix aliases are not modeled. Autoloaded functions are
listed without loading their bodies. Command names requiring quoting use literal
shell quoting; alias names requiring quoting are omitted because quoting would
bypass their expansion.

Bash and zsh argument and executable-path suggestions continue to use existing specs and
host sources. Installed Bash/zsh argument completers and guest filesystem completion
are not integrated. Older Bash and shells without an inventory
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
After Escape, automatic suggestions remain dismissed across cursor movement and
focus changes until the command text changes. Completion candidates that leave the
command text unchanged are omitted; explicit completion remains available.
Enter acceptance and automatic popups are preferences; IntelliJ also uses its
active keymap. Shell-native Tab completion remains a separate facility.

## Library customization

| Extension | Host control |
| --- | --- |
| Catalog | Replace or extend command specifications and localize descriptions without changing command tokens. |
| Sources | Register custom asynchronous sources with bounded priorities and a failure-reporting callback. |
| Source adapters | Supply directory listings, query-aware fuzzy paths, imported Gradle tasks, Git snapshots, or dynamic argument values. |
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
