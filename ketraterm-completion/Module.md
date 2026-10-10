# Module ketraterm-completion

This module owns command-line tokenization, semantic context resolution,
structured source evaluation, deterministic ranking, and bounded in-memory
learning. It depends on Kotlin coroutines and has no terminal-module
dependencies; source evaluation does not require a terminal session or UI.
The [README](README.md) supplies the consumer entry point.

<a id="packages"></a>

## Source layout

| Package | Responsibility |
|---------|----------------|
| `io.github.ketraterm.completion.api` | Engine and source contracts, source factories, requests, candidates, shell capabilities, messages, and the learning store. |
| `io.github.ketraterm.completion.model` | Command specifications, dynamic value domains, and immutable learning data exchanged with hosts. |
| Implementation packages | Tokenization, context resolution, matching, indexing, candidate projection, and ranking. Their declarations are internal. |

External consumers use `api` and `model`. The engine resolves one semantic
context per request and shares it across sources; providers must not reconstruct
that context with their own tokenizer. Its command catalog also supplies the
static source, keeping parsing and static suggestions consistent. Custom engines
use the public `TerminalCompletionContext.resolve` operation with their own
catalog and retain the resulting request-owned context across suspension.

## Integration boundaries

Hosts supply immutable request metadata, bounded suspending providers, command
outcomes, and explicit feedback. They own I/O dispatching, request cancellation,
editing admission, presentation, and persistence policy.

Related modules own the adapters:

- `ketraterm-completion-host`: path resolution and bounded host filesystem access.
- `ketraterm-ui-swing-host`: completion request/candidate and feedback conversion.
- `ketraterm-completion-persistence`: product storage and write coordination.

The detailed privacy, context, evaluation, and ranking contracts live in the
[completion architecture guide](docs/completion-architecture.md).
