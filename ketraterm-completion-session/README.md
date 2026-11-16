# Session completion adapter

Use this optional library to connect a terminal session's native shell authority
with the completion engine. It does not depend on Swing or a product workspace.
The completion API remains experimental.

```kotlin
dependencies {
    implementation(platform("io.github.ketraterm:ketraterm-bom:{version}"))
    implementation("io.github.ketraterm:ketraterm-completion-session")
}
```

Create the adapter for the session whose shell should answer completion requests:

```kotlin
@file:OptIn(io.github.ketraterm.completion.api.ExperimentalTerminalCompletionApi::class)

import io.github.ketraterm.completion.api.TerminalCompletionEngines
import io.github.ketraterm.completion.session.SessionShellCompletionProvider
import io.github.ketraterm.session.TerminalSession

fun completionFor(session: TerminalSession) =
    TerminalCompletionEngines.fromSources(
        emptyList(),
        shellProvider = SessionShellCompletionProvider(session),
    )
```

Supply the actual shell capabilities in each completion request. POSIX requests
use the session's command inventory and current aliases. PowerShell requests use
the installed native completion hook. The host installs these services through
[shell launch](../ketraterm-shell-launch/README.md) or its own integration.
An unavailable service returns no native answer. Other completion sources retain
their configured behavior.

The provider borrows the session and owns no jobs. The caller owns each suspending
request. The session controls native request admission, correlation, cancellation,
and stale-context checks. Closing the provider's host must close its session
through the host's existing lifetime.

The adapter preserves native quoting, replacement ranges, descriptions, source
identity, and candidate order. It applies directory constraints before the output
limit. It does not choose filesystem access, source priorities, learning storage,
or a popup. Add those through the [completion engine](../ketraterm-completion/README.md).

Existing consumers can keep `io.github.ketraterm.ui.swing.host.SessionShellCompletionProvider`.
That class delegates to this implementation and retains its public API.
