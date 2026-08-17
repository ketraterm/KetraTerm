# Command-Line Completion Architecture & Developer Reference

> *Looking for end-user features, keyboard controls, and shortcuts? See the [User Guide & Feature Showcase](completion-features.md).*

This document provides the technical architecture and implementation reference for developers and contributors across `ketraterm-completion`, `ketraterm-completion-host`, `ketraterm-completion-persistence`, `ketraterm-ui-swing-host`, and `ketraterm-intellij-plugin`.

---

## 1. Architecture & Layering

The completion system is built on strict layer boundaries:

- **`ketraterm-completion`**: Pure Kotlin completion engine with zero external dependencies (no Swing, no IntelliJ SDK, no disk I/O, no process execution). It owns lexical tokenization, command specification models, parallel source evaluation via structured concurrency, CamelHump/prefix matching, and evidence-fusion ranking.
- **`ketraterm-completion-host`**: Host-neutral suspending abstractions for local path resolution and bounded directory scanning (`Files.newDirectoryStream`).
- **`ketraterm-completion-persistence`**: Serialized local storage (`completion-stats.json`) for sanitized command and option shape statistics.
- **`ketraterm-ui-swing-host`**: Reusable Swing adapter converting engine results to immutable suggestion rows and forwarding keyboard/mouse actions to the terminal session.
- **`ketraterm-intellij-plugin`**: IntelliJ Platform adapters delegating path, Git, and Gradle completion to IntelliJ in-memory indices (`GotoFileModel`, `GitRepositoryManager`, `ChangeListManager`, `ProjectDataManager`, and `VirtualFileManager`).

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
| **Git Modified / Staged Paths** | Directory path completion | Live VCS changelist paths (`ChangeListManager`) | `IntellijGitStatusPathCompletionSource` |
| **Gradle Tasks** | Universal lifecycle tasks | Universal tasks + dynamic `:module:task` from imported project model | `IntellijGradleTaskCompletionSource` |
| **Fuzzy Matching** | CamelHump, Acronyms, Prefix, Exact | CamelHump, Acronyms, Prefix, Exact | `CompletionMatcher` |
| **Match Highlighting** | Bold matched characters + accent color | Bold matched characters + IDE theme accent | `SwingShellSuggestionPopupRow` |
| **Keymap Integration** | Standard keys (Tab, Enter, Arrows, Esc) | Resolved from user's active IntelliJ Keymap | `KetraTermActionUtils` |
| **Stats Persistence** | Enabled by default (`~/.ketraterm/completion-stats.json`) | Configurable in IDE Settings (Default: in-memory) | `ketraterm-completion-persistence` |

---

## 4. Matching & Scoring Engine

Completion candidate filtering and ranking runs synchronously in memory without intermediate allocations:

### Matching Tiers

1. **Exact Match**: The candidate matches the typed token completely.
2. **Prefix Match**: The candidate starts with the typed token (e.g. `car` matches `cargo`).
3. **CamelHump Match**: Matches uppercase capital word boundaries (e.g. `ctk` matches `compileTestKotlin`, `sA` matches `spotlessApply`, `bRel` matches `buildRelease`).
4. **Delimiter-Separated Acronym Match**: Matches words separated by hyphens or underscores (e.g. `d-c` or `dc` matches `docker-compose`, `k-g` matches `kubectl-get`).
5. **Substring Match**: Fallback match when internal substrings align.

### Zero-Allocation Match Ranges

`CompletionMatcher` returns bit-packed `IntArray` pairs (`[start0, end0, start1, end1]`) wrapped in `TerminalCompletionMatchRanges`. The Swing popup renderer uses these offsets to paint matching characters in bold with the active accent color without allocating substring objects during paint cycles.

### Ranking Formula

$$
\text{Score} = \text{BaseScore}(\text{Kind}) + \text{PrefixBonus} + \text{ExactBonus} + \text{MatchedLength} - \text{LengthPenalty} + \text{FeedbackBias}
$$

Candidates are sorted deterministically by score descending, then alphabetically by display text.

---

## 5. Privacy & Persistence Model

The completion learning engine is designed to prevent sensitive data leaks:

### Persisted to Disk (`completion-stats.json`)

- Exact CLI command names (e.g. `git`, `docker`, `gradle`).
- Option shape frequencies (e.g. usage of `--output` vs `-o`).
- Source feedback statistics (acceptance and dismissal counters per source).

> **Privacy Guarantee**: Arguments, file paths, branch names, passwords, access tokens, URLs, and environment variables are **never written to disk**.

### In-Memory Only (Per-Session)

- Most Recently Used (MRU) commands executed in the active terminal tab.
- Observed tokens for unknown command-line tools (`abc de -g` teaches the session that `abc` accepts `de` and `-g`).
- All in-memory session data is discarded when the tab or terminal process is closed.

---

## 6. Built-In CLI Specification Catalog

`TerminalCommandSpecs` bundles zero-latency specifications for standard developer tools:

| Command | Subcommands, Options, and Domains |
| :--- | :--- |
| **`git`** | Subcommands (`commit`, `checkout`, `switch`, `merge`, `rebase`, `pull`, `push`, `status`, `diff`, `log`, `branch`, `tag`, `stash`, `clone`, `fetch`, `reset`, `restore`, `remote`, `cherry-pick`, `show`), option flags, value domains (`GIT_LOCAL_BRANCH`, `GIT_REMOTE_BRANCH`, `GIT_TAG`, `GIT_STATUS_PATH`). |
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
