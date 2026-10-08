# Command-Line Completion Architecture & Developer Reference

> *Looking for end-user features, keyboard controls, and shortcuts? See the [User Guide & Feature Showcase](completion-features.md).*

This document provides the technical architecture and implementation reference for developers and contributors across `ketraterm-completion`, `ketraterm-completion-host`, `ketraterm-completion-persistence`, `ketraterm-ui-swing-host`, and `ketraterm-intellij-plugin`.

---

## 1. Architecture & Layering

The completion system is built on strict layer boundaries:

- **`ketraterm-completion`**: Kotlin completion engine without Swing, IntelliJ, session, or process dependencies. It owns lexical tokenization, command specification models, parallel source evaluation via structured concurrency, CamelHump/prefix matching, and evidence-fusion ranking. Host sources may perform bounded suspending I/O in the request scope.
- **`ketraterm-completion-host`**: Host-neutral suspending abstractions for local path resolution and bounded directory scanning (`Files.newDirectoryStream`).
- **`ketraterm-completion-persistence`**: Optional bounded local storage (`command-completion-learning-v3.tsv`) for opaque exact-command ranking evidence and separately approved replay rows. Learning updates memory synchronously; one worker checkpoints the latest dirty state every 30 seconds and at shutdown.
- **`ketraterm-ui-swing`**: Public request-scoped completion interaction, immutable publications, and optional embedded presentation using a Swing `JList`. The interaction owns selection, admission, feedback, and closure; the presentation adapter owns navigation gestures and the visible viewport. Sources and ranking remain external.
- **`ketraterm-ui-swing-host`**: Reusable adapter converting engine results into immutable renderer-neutral suggestions. It resolves semantic accent roles, match ranges, and source display labels once before either UI sees them.
- **`ketraterm-intellij-plugin`**: IntelliJ Platform adapters delegating path, Git, and Gradle completion to IntelliJ project models and bounded Git history queries (`GotoFileModel`, `GitRepositoryManager`, `GitHistoryUtils`, `ChangeListManager`, `ProjectDataManager`, and `VirtualFileManager`). It owns a separate platform-native `JBList` completion renderer.

---

## 2. Shell Dialect Support

Shell capability contracts define tokenization, quote handling, and command separator semantics for each launch profile.

| Shell Family | Dialect Mode | Command Chaining | Quoting Rules | Prompt Markers | Working Directory |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Bash, Zsh, Dash (POSIX)** | `POSIX` | `&&`, `\|\|`, `;`, `\|` | Single (`'...'`), Double (`"..."`), Backslash (`\`) | Supported (OSC 133) | Supported (OSC 7) |
| **PowerShell (pwsh, Windows PowerShell)** | `POWERSHELL` | `&&`, `\|\|`, `;`, `\|` | Single (`'...'`), Double (`"..."`), Backtick (``` ` ```) | Supported (OSC 133) | Supported (OSC 7) |
| **Fish** | `POSIX` | `&&`, `\|\|`, `;`, `\|` | Single (`'...'`), Double (`"..."`), Backslash (`\`) | Supported (OSC 133) | Supported (OSC 7) |
| **Command Prompt (cmd.exe)** | `PLAIN` | Disabled (Conservative) | Double (`"..."`) | Not Supported | PTY Working Dir |
| **Generic / Unknown** | `PLAIN` | Disabled (Conservative) | Standard | Not Supported | PTY Working Dir |

### Lexical Rules

- **Command Separators**: When the cursor is positioned directly on a command operator (`&&`, `||`, `;`, `|`), completion returns empty to avoid syntax corruption. When typing after an operator, only the active command segment is completed; full-command history is suppressed to prevent overwriting earlier piped segments.
- **End-of-Options Terminator (`--`)**: The exact `--` argument switches the parser into positional mode. All subsequent tokens are completed as file or positional arguments, disabling subcommand and flag completion.
- **Attached vs. Separate Options**: Option values typed as `--flag=value` or `--flag value` share identical value-domain resolution. Attached options preserve the option prefix and equal sign during replacement.

---

## 3. Host Capabilities: Standalone vs. IntelliJ Plugin

| Capability / Source | Standalone (`ketraterm-app`) | IntelliJ Plugin (`ketraterm-intellij-plugin`) | Responsible Layer |
| :--- | :--- | :--- | :--- |
| **Static CLI Specs** | 18+ Developer tools | 18+ Developer tools | `ketraterm-completion` |
| **Immediate Directory Scan** | Suspending NIO scan (`Files.newDirectoryStream`) | In-memory VFS project scan (0 disk I/O) + NIO fallback | `ketraterm-completion-host` / IDE VFS |
| **Whole-Project Fuzzy Files** | Scoped to active directory path | Global Search Everywhere index (`GotoFileModel`) | `IntellijProjectFileCompletionSource` |
| **Git Branches & Tags** | Static spec / argument values | Live Git4Idea branches and tags (`GitRepositoryManager`) | `IntellijGitCompletionSource` |
| **Recent Git Commits** | No dynamic commit source | Up to 50 recent commits across HEAD, local branches, remotes, and tags (`GitHistoryUtils`) | `IntellijGitCommitCompletionSource` |
| **Git Modified / Staged Paths** | Directory path completion | Live VCS changelist paths (`ChangeListManager`) | `IntellijGitStatusPathCompletionSource` |
| **Gradle Tasks** | Universal lifecycle tasks | Universal tasks + dynamic `:module:task` from imported project model | `IntellijGradleTaskCompletionSource` |
| **Fuzzy Matching** | CamelHump, Acronyms, Prefix, Exact | CamelHump, Acronyms, Prefix, Exact | `CompletionMatcher` |
| **Match Highlighting** | Precomputed bold matched fragments + contrast-safe accent | Precomputed bold matched fragments + IDE theme accent | Renderer-neutral `SwingShellSuggestion.matchedRanges` |
| **Completion Surface** | Swing `JList` with themed cells, icons, and footer | IntelliJ-native `JBList` with platform icons and footer | Shared interaction; optional bounded `SwingShellSuggestionViewSnapshot` adapter |
| **Keymap Integration** | Standard keys (Tab, Enter, Arrows, Esc) | Standard fallback plus actions resolved from the active IntelliJ Keymap | `SwingShellSuggestionKeymap` / `KetraTermShellSuggestionKeymap` |
| **Stats Persistence** | Opt-in; in-memory by default | Opt-in; in-memory by default | `ketraterm-completion-persistence` |

---

## 4. Matching & Scoring Engine

Completion matching and ranking use bounded in-memory candidate snapshots and a parsed context shared by every source:

### Matching Tiers

1. **Exact Match**: The candidate matches the typed token completely.
2. **Prefix Match**: The candidate starts with the typed token (e.g. `car` matches `cargo`).
3. **CamelHump Match**: Matches uppercase capital word boundaries (e.g. `ctk` matches `compileTestKotlin`, `sA` matches `spotlessApply`, `bRel` matches `buildRelease`).
4. **Delimiter-Separated Acronym Match**: Matches words separated by hyphens or underscores (e.g. `d-c` or `dc` matches `docker-compose`, `k-g` matches `kubectl-get`).
5. **Substring Match**: Fallback match when internal substrings align.

### Primitive Match Ranges

`CompletionMatcher` returns packed `IntArray` pairs (`[start0, end0, start1, end1]`) wrapped in `TerminalCompletionMatchRanges`. The Swing adapter copies them into the presentation contract. Both physical renderers consume those exact ranges and prepare bounded display content before cell painting.

### Presentation Contract

The standalone and IntelliJ products use separate physical widgets. Standalone uses `SwingCompletionPopupView`, a Swing `JList` with a custom cell renderer. The plugin uses `IntellijCompletionListView`, an IntelliJ-owned `JBList` that follows IDE colors, icons, scaling, and accessibility conventions.

`SwingShellSuggestionInteraction` is the shared semantic boundary. It retains one original command-line request and captured editing capability. Its immutable `SwingShellSuggestionSnapshot` contains the complete ranking and global selected index. Selection changes reuse candidate storage. Views retain the publication associated with a gesture and pass it to `select`, `tryAccept` or `dismiss`, so delayed actions cannot edit or penalize a different candidate after reranking.

Each `SwingShellSuggestion` supplies authoritative display text, detail, stable source id, source display label, accent role, and validated match ranges. The optional embedded controller derives `SwingShellSuggestionViewSnapshot`: a bounded window with a local selection and absolute overflow metadata. Its default view shows eight rows; a custom view chooses its visible-row limit. Detached UI can consume the complete interaction snapshot directly and own placement and scrolling. Renderers must not reinterpret raw kind or source strings.

A publication is passive when its selected index is `-1`; producers may supply an initial selection. Progressive updates preserve the user's selected replacement outcome by default. The interaction owns acceptance, explicit dismissal, feedback, and closure. The embedded adapter translates keyboard and pointer gestures into those operations.

Capture and source opening run synchronously on the EDT before provider work. `SwingShellSuggestionEditTarget.capture(request)` returns the request's handler, and `SwingShellSuggestionProvider.open(request)` returns a `SwingShellSuggestionSource` containing a cold stream and its original feedback observer. Both operations must remain cheap. The default `open` defers `suggestions(request)` itself until background collection; provider work stays off the EDT. Progressive snapshots are conflated before publication back on the EDT.

### Composing Providers and UI

Hosts choose source production and presentation independently:

| Integration | Composition |
| :--- | :--- |
| Custom provider with embedded UI | Configure the provider and edit target; use the terminal's normal request methods. |
| Existing engine with custom UI | Adapt the engine with `SwingCompletionSuggestionProvider`, capture an interaction, and collect into it while the host surface observes its snapshots. |
| Host-produced results with embedded UI | Capture an interaction before starting work, publish results on the EDT, and call `presentShellSuggestions`. |
| Custom provider and custom UI | Capture an interaction, bind the custom surface, and call `requestShellSuggestions(interaction, provider)` without mounting the embedded view. |
| Completion outside Swing | Consume `TerminalCompletionEngine.completions(request)` directly; the pure engine requires no Swing or terminal component. |

The explicit embedded composition is:

```kotlin
fun requestEmbeddedSuggestions(
    terminal: SwingTerminal,
    provider: SwingShellSuggestionProvider,
) {
    check(SwingUtilities.isEventDispatchThread())
    val interaction = terminal.beginActiveShellSuggestionInteraction() ?: return
    terminal.presentShellSuggestions(interaction)
    terminal.requestShellSuggestions(interaction, provider)
}
```

For detached presentation, pass the captured interaction to the host's UI and omit `presentShellSuggestions`. `requestShellSuggestions(interaction, provider)` only opens and collects the source; it does not mount a popup. A host that already produces candidates can instead call `interaction.publish` on the EDT. Each interaction accepts one provider collection. A newer interaction, input, session rebinding, disposal, or loss of eligibility invalidates old work; closed interactions reject late publication. Stream completion leaves the last usable publication available.

`SwingLiveCompletionBinding` supplies optional debounce, focus, eligibility, and session-lifetime coordination. Its custom `SwingShellSuggestionTarget` receives the already captured interaction rather than a command snapshot requiring another acceptance implementation. A standalone Swing surface without a terminal can construct `SwingShellSuggestionInteraction` with its own captured handler and observer, and owns its collection and disposal lifecycle.

### Admission and Feedback

`SwingShellSuggestionHandler.tryAccept` returns `SwingShellSuggestionAcceptanceResult`. `ACCEPTED` means that the configured editing authority admitted or applied the complete edit; it does not acknowledge transport completion or shell execution. The default session edit target captures a versioned context before provider work and submits the replacement through atomic conditional session admission. Custom editors capture their own capability at the same boundary.

Request observers receive `ACCEPTED`, `REJECTED`, or explicit `DISMISSED` feedback. Acceptance-attempt events carry their result. Rejection is diagnostic and does not change completion learning; passive hiding, supersession, and cancellation also produce no negative preference feedback. Observers remain attached to their original request even if provider resources change reentrantly.

An optional opaque `feedbackToken` travels from `TerminalCompletionCandidate` to `SwingShellSuggestion` and back in feedback. The merged engine preserves the selected presentation representative's token; it does not use it for ranking or outcome grouping. Providers can use the token to identify their original candidate without treating display labels or row indices as routing identities. Deduplicated contributors do not receive automatic broadcast feedback.

### Ranking Evidence

The global ranker combines source rank and priority, semantic context, and bounded exact-command evidence. Repeated executions and accepted suggestions raise a matching outcome, while explicit dismissals lower it. Nonzero exits are usage rather than negative feedback. More specific profile and working-directory rows take precedence over global rows, and recent evidence receives a bounded boost. Candidates are sorted deterministically after score fusion.

---

## 5. Privacy & Persistence Model

Completion learning always works in memory. Disk persistence is separately opt-in in both products and uses one fixed product-owned destination named `command-completion-learning-v3.tsv`; settings enable or disable that destination rather than switching or importing arbitrary paths at runtime.

When enabled, version 3 stores two projections. Opaque ranking rows contain a stable case-sensitive command digest, optional profile and working-directory context, bounded usage/feedback counters, and a last-used timestamp. Counter-free replay rows contain plaintext only for successful commands approved by the replay policy. Accepting or dismissing an existing suggestion updates only its opaque ranking evidence.

Malformed UTF-16 is not learned. For otherwise recordable commands, the best-effort replay policy rejects leading-whitespace, blank, multiline, control-bearing, and oversized text before plaintext enters retained memory. It also recognizes a small set of common credential options and password-bearing URI user-info. Approval is not proof that a command contains no secret. Well-formed commands rejected by the replay policy may still update opaque ranking evidence, but cannot feed history replay or observed-token inference. The file boundary rechecks the policy. Text fields use Base64URL so they fit safely in TSV fields; this is encoding, not encryption. Deterministic digests of common commands remain guessable. Users should treat the file as local command-derived data.

When persistence is disabled, the product neither loads nor writes the file. Product-lifetime learning remains in memory. One bounded aggregate publishes opaque ranking evidence and an optional replay projection; only the latter feeds history candidates and observed tokens for unknown commands.

---

## 6. Built-In CLI Specification Catalog

`TerminalCommandSpecs` bundles zero-latency specifications for standard developer tools:

| Command | Subcommands, Options, and Domains |
| :--- | :--- |
| **`git`** | Subcommands (`commit`, `checkout`, `switch`, `merge`, `rebase`, `pull`, `push`, `status`, `diff`, `log`, `branch`, `tag`, `stash`, `clone`, `fetch`, `reset`, `restore`, `remote`, `cherry-pick`, `revert`, `show`), option flags, and dynamic value domains for branches (`GIT_BRANCH`) and commits (`GIT_COMMIT`); the IntelliJ host also supplies changed-path candidates. |
| **`gradle` / `gradlew`** | Universal tasks (`build`, `test`, `check`, `clean`, `tasks`, `run`, `assemble`, `help`, `projects`, `properties`, `dependencies`, `dependencyInsight`, `wrapper`), CLI flags (`--configuration-cache`, `--build-cache`, `--daemon`, `--parallel`, `--continuous`, `--scan`, `--info`, `--debug`, `--stacktrace`, `--project-dir`, `--exclude-task`), repeatable sibling task lists. |
| **`kotlin` / `kotlinc`** | Standalone Kotlin compiler CLIs (`kotlinc`, `kotlinc-jvm`, `kotlinc-js`, `kotlinc-native`) with compiler options (`-jvm-target`, `-language-version`, `-api-version`, `-opt-in`, `-Xcontext-parameters`, `-Xmulti-platform`, `-Werror`, `-verbose`, `-d`, `-cp`). |
| **`adb`** | Android Debug Bridge commands (`devices`, `logcat`, `install`, `uninstall`, `shell`, `push`, `pull`, `reboot`, `reverse`, `forward`, `start-server`, `kill-server`, `connect`, `disconnect`, `tcpip`) and device selection flags (`-s`, `-d`, `-e`). |
| **`docker`** | Subcommands (`run`, `build`, `ps`, `exec`, `stop`, `start`, `rm`, `rmi`, `pull`, `push`, `logs`, `compose`, `network`, `volume`, `system`) and CLI flags. |
| **`docker-compose`** | Subcommands (`up`, `down`, `ps`, `logs`, `build`, `exec`, `restart`, `stop`, `start`, `pull`, `config`, `run`) with common flags (`-d`, `--build`, `-f`, `-p`). |
| **`kubectl`** | Subcommands (`get`, `describe`, `logs`, `apply`, `delete`, `exec`, `port-forward`, `top`, `config`, `cluster-info`, `explain`, `rollout`), Kubernetes resource types (`pods`, `services`, `deployments`, `configmaps`, `secrets`, `nodes`, `namespaces`, `ingress`, `statefulsets`), and flags (`-n`, `-A`, `-o`, `-l`). |
| **`cargo`** | Subcommands (`build`, `test`, `check`, `run`, `clippy`, `fmt`, `add`, `remove`, `update`, `clean`, `doc`, `publish`, `bench`, `new`, `init`, `metadata`) and build flags (`--release`, `--all-targets`, `--workspace`, `--package`). |
| **`npm` / `pnpm` / `yarn` / `bun` | Common package management commands (`install`, `add`, `remove`, `run`, `test`, `build`, `start`, `init`, `update`, `outdated`, `publish`, `why`, `create`, `exec`) and CLI options. |
| **`gh`** | GitHub CLI subcommands (`pr`, `issue`, `repo`, `auth`, `workflow`, `run`, `release`, `gist`, `secret`, `variable`, `api`, `browse`) with nested actions. |
| **`pip`** | Python package commands (`install`, `uninstall`, `list`, `freeze`, `show`, `check`, `cache`, `config`, `search`, `wheel`, `download`) and option flags (`-r`, `-e`, `-U`, `--index-url`). |
| **`go`** | Go toolchain commands (`build`, `test`, `run`, `get`, `install`, `mod`, `fmt`, `vet`, `generate`, `clean`, `env`, `version`) and subcommands (`mod tidy`, `mod download`, `mod vendor`). |
| **`aws`** | AWS CLI service subcommands (`s3`, `ec2`, `lambda`, `ecs`, `sts`, `iam`, `logs`, `ssm`, `sqs`, `sns`, `cloudformation`, `configure`) and core flags (`--profile`, `--region`, `--output`). |
| **`ketra`** | KetraTerm CLI commands (`open`, `profile`, `tab`, `split`, `config`, `version`). |
