# Command completion

KetraTerm's optional completion engine combines command specifications, paths,
host-provided sources, and bounded local learning. It can run independently of
a terminal or use the Swing/IntelliJ suggestion UI.

## Stability: experimental

Completion and suggestions are **experimental**, including their engine, sources,
learning, persistence, and UI APIs. Suggestions may be incomplete, rank poorly,
or behave unexpectedly in edge cases. APIs may change before stabilization.
Known issues and remaining work are tracked in the [gap map](../terminal-feature-gap-map.md#completion-and-suggestions).

Kotlin callers can opt in to `ExperimentalTerminalCompletionApi` for the engine
and `ExperimentalShellSuggestionsApi` for Swing suggestions. Both use
`RequiresOptIn.Level.WARNING`: calls compile without opt-in unless warnings are
errors. The Swing completion adapter uses both markers. Opt-in acknowledges the
experimental contract; suggestions are enabled separately. Java callers follow
the same stability contract.

## Availability

Completion and automatic suggestions are **enabled by default in both products**.
Settings expose the master switch, automatic popups, Enter acceptance, and optional
learning persistence. Automatic popups can appear **Always** (the default) or
**Only for parameters**. Parameter-only mode includes subcommands, options, and
arguments, while Ctrl+Space can still complete an executable name. Standalone users can also set `smart_suggestions_enabled`
in the [TOML configuration](../../ketraterm-app/docs/profile-config-toml.md).
Explicit saved preferences remain effective.
Library hosts enable and configure their own provider and presentation.

Use **Reset learned suggestions...** in settings to clear learned commands and
ranking preferences. See [saved learning and reset behavior](../reference/storage.md#completion-learning).

Terminal suggestions require an editable command context, normally supplied by
[shell hooks](shells.md#prompt-hooks-and-completion-dialects).
Unsupported shells do not gain command tracking merely by enabling completion.
Custom hosts can supply their own context and editing authority.

## Using suggestions

With completion enabled, Ctrl+Space requests suggestions. The embedded view uses
Tab to accept, arrows/Page Up/Page Down to navigate, and Escape to dismiss.
Automatic refreshes cannot replace an active Ctrl+Space request.
After Escape, automatic suggestions remain dismissed across cursor movement and
focus changes until the command text changes. Completion candidates that leave the
command text unchanged are omitted; explicit completion remains available.
Enter acceptance and automatic popups are preferences; IntelliJ also uses its
active keymap. Shell-native Tab completion remains a separate facility.

### Argument hints

Command and option rows show argument placeholders when command specifications
provide metadata, for example `switch <branch>` and `-m <message>`. Named optional
arguments use `[name]`. Repeated arguments use the same compact placeholder.
Subcommand option overrides supply their own descriptions and hints.
Git's `checkout` distinguishes
its optional reference/file argument from subsequent pathspecs; `add` and
`cherry-pick` describe pathspecs and commits. Hints are never inserted, matched,
or saved as command text. Native results without specification metadata may omit them.

The shared Swing popup exposes normal and selected hint colors through
`SwingShellSuggestionStyle`; the IntelliJ view follows the IDE theme. Custom
providers can set `argumentHint`, and custom views choose how to display it.

## Sources by host

This table describes the sources available when completion is enabled.

| Source | Standalone | IntelliJ plugin |
| --- | --- | --- |
| Built-in command specifications | Yes | Yes |
| Live PowerShell completion | Commands, session functions, aliases, and installed argument completers through the current PSReadLine session. | Same native bridge. |
| Bash command inventory | Commands, alias definitions, functions, and builtins discovered in the shell at each prompt. Requires Bash 5.3+ and `base64`. Literal aliases inform argument semantics. | Same inventory and command authority. |
| zsh command inventory | Enabled commands, ordinary alias definitions, functions (including autoloads), and builtins discovered at each prompt. Requires `zsh/parameter` and `base64`; verified with zsh 5.9. Literal aliases inform argument semantics. | Same inventory and command authority. |
| Files and directories | Bounded local directory scans. | IDE virtual filesystem with local scan fallback. |
| Project-wide files | No project index. | IDE fuzzy filename lookup with match highlighting, while the terminal is inside the project's content roots. |
| Git branches and tags | Bounded local Git queries for modeled reference arguments. | Local/remote branches and tags from IDE repositories. |
| Git remote names | Bounded `git remote` query of local configuration. | Configured remotes from the selected IDE repository. |
| Recent Git commits | No dynamic source. | Up to 50 commits for supported commit arguments. |
| Git changed paths | Ordinary path completion. | IDE change-list paths for supported Git commands. |
| Gradle tasks | Built-in lifecycle tasks. | Also tasks from the deepest imported build root containing the terminal directory or completed `-p`/`--project-dir` target. |
| Learned commands and tokens | In-memory learning when enabled. | In-memory learning when enabled. |

### Working directory and remote shells

Filesystem and Git sources need a locally resolvable working directory. WSL and
remote shell metadata do not provide access to the guest's files. Explicit remote
or malformed directory metadata is never replaced with unrelated local data.

For Git commands, completed root `-C` options select the directory for all applicable
sources; `switch -C` names a branch and does not change that directory.
Relative values build on the preceding directory; an empty value leaves
it unchanged. IntelliJ uses the project directory only when shell directory
metadata is absent.

### Git suggestions

Git suggestions use local repository data, including configured remotes and
locally known tracking branches. They do not query a remote server. Standalone
runs bounded Git queries; IntelliJ uses its repository model. Missing Git or an
unavailable repository leaves other suggestion sources usable. Standalone queries
use the host's Git executable and environment, so shell-local toolchain changes
are not reflected in them.

- `checkout`, `merge`, and `rebase` offer applicable branches and tags. After a
  completed `--`, `checkout` and `reset` offer paths only.
- `push`, `pull`, and `fetch` first offer remote names or local repository
  directories, then references. Push offers local branches and tags, including
  the current branch. Fetch/pull offer tracking branches of the chosen remote
  without its prefix: `git fetch origin fe` can insert `feature/x` from
  `origin/feature/x`. Configured remotes are available before fetching refs.
- `push --delete` offers the chosen remote's locally known branches.
  `fetch --multiple` offers remaining remote names. `fetch --all` stops
  repository/ref suggestions; `push --all`, `--branches`, and `--mirror` stop
  ref suggestions after the repository. Options remain available, and
  `push --tags` still accepts explicit refspecs.
- Ordinary `[+]<source>:<destination>` refspecs complete the component at the
  cursor. Push uses local sources and remote tracking destinations; fetch/pull
  reverse those roles. Empty-source push deletion (`:branch`) is supported.
  Edits preserve the force marker, other component, and shell quoting.
  Whole-command history is suppressed while editing a composite refspec.

The current branch is omitted where it would suggest merging or switching to
itself; symbolic remote aliases are omitted. Wildcard, negative, and malformed
multi-colon refspecs are left untouched. Remaining Git coverage is listed in the
[gap map](../terminal-feature-gap-map.md).

### PowerShell suggestions

With prompt hooks and PSReadLine installed, suggestions use `TabExpansion2` in
the running shell. This includes available commands, session functions, aliases,
and installed argument completers. Catalog descriptions enrich commands verified
by the shell. Bare names and executable extensions merge when they resolve to
the same file; shadowing aliases and functions remain distinct.

PowerShell owns parameter names and values after options, including abbreviations
and attached values such as `-Encoding:u`. Aliases and functions therefore do not
inherit Unix options from similarly named catalog commands. Compatible external
applications retain catalog options when the shell returns only generic paths or
no candidates; installed argument completers take precedence.
Other modeled arguments prefer catalog and host sources, with native fallback
when they have no results. Unknown functions and arguments use native completers
before generic path suggestions. Directory arguments exclude files; typed values
such as Git branches exclude unrelated path suggestions. Relevant live values
precede history-only results, while learning can still influence their order.

Acceptance uses the shell's replacement text and range. The PowerShell bridge
restores the edited command prefix through PSReadLine's exact replacement API,
preserving text after the cursor. Slow native completers
can time out. Cancelling a request cannot stop a completer already running inside
PowerShell. Suggestions also require an unambiguous editable command line with
the cursor at its end. See the [bridge protocol](../reference/protocol.md#private-powershell-completion-metadata)
for request limits and validation.

### Bash and zsh suggestions

Prompt hooks discover aliases, functions, builtins, and executables using the
running shell's environment. WSL uses the guest environment. Shell-local names
are available first, while executable discovery runs in the background without
redrawing the prompt. Available commands remain usable while discovery continues;
new executable files may take a later refresh to appear.

Shell-local names take precedence over matching executable names. Alias and
function changes are picked up at the next prompt. PATH changes invalidate the
previous executable inventory; directory changes do so when PATH contains empty
or relative entries. An unchanged PATH can be re-scanned after 60 seconds, with
refresh starting at a later prompt. Failed refreshes retain the last complete
snapshot for the same environment. Large inventories are bounded; an incomplete
overflowed scan is never presented as a complete command list.

Simple aliases also inform argument suggestions. For example, `g='git'` and
`gs='git switch'` inherit the corresponding Git arguments while acceptance keeps
the typed alias. Literal chains and fixed arguments are supported. Complex shell
expansion, cyclic aliases, and quoted command names do not inherit a potentially
incorrect command grammar. zsh global/suffix aliases are excluded, disabled
functions and builtins are excluded, and autoloaded functions are listed without
loading their bodies. On Git Bash/Cygwin, `.exe` names use bare insertion only
when both spellings resolve to the same executable.

Popup arguments use command specifications and host sources. Installed Bash/zsh
programmable argument completers and guest filesystem completion are not
integrated. Normal shell Tab completion remains available. Older Bash and shells
without command inventories retain their existing sources.

See the [inventory protocol](../reference/protocol.md#private-bash-command-inventory)
for wire formats, bounds, and refresh ownership, and the
[engine contract](../../ketraterm-completion/docs/completion-architecture.md#internal-implementation)
for alias interpretation and merging rules.

## Built-in command catalog

The catalog provides selected subcommands, options, and argument domains; it is
not an exhaustive schema for every version of these tools.

| Group | Commands |
| --- | --- |
| Files and navigation | `cd`, `pushd`, `ls`, `cat`, `mkdir`, `rm`, `cp`, `mv` |
| Version control | `git`, `gh` |
| Build and languages | `gradle` / `gradlew`, `mvn` / `mvnw`, `dotnet`, `cargo`, `go`, `kotlin`, `kotlinc`, `python` / `python3` |
| Package managers | `npm`, `pnpm`, `yarn`, `bun`, `pip` |
| Containers and cloud | `docker`, `docker-compose`, `kubectl`, `aws` |
| Network clients | `curl`, `ssh` |
| Developer tools | `code`, `adb`, `ketra` |

## Matching and interaction

| Feature | Description |
| --- | --- |
| Matching | Exact, prefix, CamelHump, word-boundary/acronym, and substring matching. |
| Context | Active command segment, preceding arguments, case-sensitive option identity, option values, and end-of-options handling. |
| Command specifications | Aliases, nested/repeatable subcommands, ordered/variadic arguments, literal values, path kinds, and mutually exclusive option groups. |
| Shell syntax | POSIX and PowerShell subsets, plus conservative plain parsing; see the [shell matrix](shells.md). |
| Paths | Directory traversal, including for file-valued arguments, shell-aware escaping, and hidden entries when explicitly requested. |
| Ranking | Merged sources, deterministic ordering, contextual priority, and bounded learning boosts. |
| Progressive results | Sources can update results asynchronously; obsolete requests are cancelled. |
| Presentation | Compact matched-text rows, keyboard/pointer navigation, description-only hover tooltips, full row context for accessibility, and custom UI support. |
| Acceptance | A stale editing context is rejected before replacement is admitted. |

## Library customization

| Extension | Host control |
| --- | --- |
| Catalog | Replace or extend command specifications and localize descriptions without changing command tokens. |
| Sources | Register custom asynchronous sources with bounded priorities and a failure-reporting callback. |
| Source adapters | Supply directory listings, query-aware fuzzy paths, imported Gradle tasks, Git snapshots, or dynamic argument values. |
| Learning | Supply a shared learning store and replay policy; enable ranking evidence independently of command replay. |
| Presentation | Configure built-in popup colors and source labels, or replace the provider, view, keymap, or entire presentation target. |
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
Completed arguments keep their case-sensitive identity, so learning for `ssh -L`
does not leak into `ssh -l`. Matching the word being typed ignores case.

Persistence is opt-in. Approved replay may contain plaintext command data;
filtering cannot identify every secret. See the [storage and privacy contract](../reference/storage.md)
for location, retention, and encoding.

For integration, use the [completion README](../../ketraterm-completion/README.md),
[Swing adapter guide](../../ketraterm-ui-swing-host/README.md#completion-bridge),
and [engine architecture](../../ketraterm-completion/docs/completion-architecture.md).
