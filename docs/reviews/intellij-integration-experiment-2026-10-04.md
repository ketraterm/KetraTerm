> Evidence received on 2026-10-04 from the user’s IDEA Community integration experiment. The report below is preserved as supplied; its implementation revision, test logs and final measurement report were not supplied here and have not been independently reviewed in this repository. Proposed API changes are evidence for design work, not approved contracts. Current status, ownership and acceptance criteria belong only in the [feature gap map](../terminal-feature-gap-map.md#intellij-integration-experiment-follow-ups). ASCII parsing optimization is excluded from this follow-up work at the user's request.

# KetraTerm integration experiment

## Selection

Set the `terminal.emulator` registry value to `KetraTerm`. Open a new Reworked terminal tab.
The value also accepts `JediTerm` and `Ghostty`. An empty value preserves `terminal.use.ghostty.emulator` and its current default.
`ShellStartupOptions.emulatorType` takes precedence over the registry.

## Ownership

IntelliJ owns the process, PTY connector, tool window, tabs, project scope, and shell scripts.
KetraTerm owns terminal parsing, terminal buffers, input encoding, its ordered writer, and Swing rendering.
The view scope owns its component and session. Cancellation closes both.
The Swing component controls the visible grid and sends size changes through the session.
The adapter reads the process byte stream directly. Test connectors use an incremental UTF-8 encoder.
The adapter never sends input directly to the connector.

IntelliJ shell scripts continue to emit OSC 1341 commands.
An ordered stream filter passes these commands to the existing IntelliJ shell controller.
It records native line identifiers before it parses subsequent bytes.
The adapter publishes prompt state, command context, working directory, and command lifecycle through KetraTerm's neutral shell contracts.
It does not require KetraTerm's OSC shell scripts.

The renderer reads KetraTerm frames directly.
A separate projection updates one IntelliJ document per buffer for existing output and shell APIs.
This projection is adapter work. Its cost must not be attributed to KetraTerm alone.
The adapter does not add a second `StateAwareTerminalSession` document pair.
Native history uses a row limit derived from the host character limit and the initial width.
Wide characters and later width changes can produce different retention between the native buffer and the compatibility document.

## Feature status

| Feature | Integration |
| --- | --- |
| Tool windows, tabs, and separate-session splits | Existing IntelliJ host |
| Process, PTY, environment, and shell scripts | Existing IntelliJ host |
| Parsing, input encoding, buffers, mouse events, and rendering | Published KetraTerm session and Swing component |
| Font, palette, cursor, line spacing, and theme updates | Adapted from existing IntelliJ settings, including live alternate-screen spacing |
| Prompt markers, working directory, and command lifecycle | Ordered OSC 1341 adapter and neutral session contracts |
| Clipboard, clipboard history, Select All, and drag-and-drop | IntelliJ actions and existing content conversion |
| OSC 8 and detected URLs | KetraTerm discovery with IntelliJ navigation and live host styles |
| Completion | IntelliJ argument specifications and generators in a native popup |
| Search | Native IntelliJ popup with KetraTerm literal search |
| Session transfer and reconnection | Unsupported; transfer fails before process startup |
| External range selection | Unsupported; the renderer lacks the required selection contract |
| PowerShell shell completion and arbitrary raw input | Unsupported until the ordered input API exists |

Completion accepts only command endings with a known deletion count.
It checks command snapshots and pending input before admission. It rejects complex deletion spans, control input, and custom caret positions.
The library does not accept an expected input revision atomically, so concurrent input can still race the final admission boundary.
It does not provide first-token executable completion, inline completion, or the PowerShell shell completion trigger.
Custom console filters and regex search remain unconnected.

## API boundaries

This integration belongs to the repository's internal terminal modules.
`TerminalEmulatorType`, the session factories, the shell controller, and the completion implementations are internal IntelliJ APIs.
They do not form a supported external plugin embedding API.
`TerminalView` and the output models are experimental and non-extendable contracts.
The library adapter only calls published KetraTerm APIs.

## Confirmed library gaps

| Priority | Gap | Evidence | Smallest useful change |
| --- | --- | --- | --- |
| P1 | Closed-session rendering | Swing binding and resize call `resizeViewport`, which rejects a closed session. | Allow safe binding and viewport changes after closure while retaining final output. |
| P1 | Ordered raw input | `TerminalWriteBytesEvent` has no equivalent session operation. PowerShell completion sends `ESC[24~e`. | Add a session operation that submits an owned byte range through the existing writer. |
| P1 | Admission and failure feedback | Semantic encode methods return `Unit`. Closure can discard input. | Return an admission result and expose asynchronous write failure through session state. |
| P1 | Custom shell commands | The parser has no public custom OSC observer. IntelliJ uses OSC 1341. | Add an ordered custom OSC callback at the parser boundary. |
| P2 | Programmatic selection | Swing exposes current selection, Select All, and command selection. It lacks range assignment, clear, and change events. | Add selection operations and an observer with stable line identifiers. |
| P2 | Render completion | Publication does not identify the frame last applied or painted by Swing. | Expose the generation with an applied-frame or completed-paint observer. |
| P2 | Clear buffer | The session has no public local buffer clear command. | Add an ordered buffer operation separate from sending Ctrl+L. |
| P2 | Settings parity | SwingSettings lacks mouse reporting, copy-on-selection, middle-button paste, and column-spacing controls. | Add the individual settings with documented defaults. |

### Raw input requirements

The caller owns a supplied byte array until admission completes.
The session must then own immutable bytes or copy the supplied range before it returns.
The session must preserve order with key events, paste, replacement, parser replies, resize, and close.
A compound host operation needs one admission when interleaving changes its meaning.
The API must define queue limits, rejected admission, cancellation, transport failure, and the final accepted write before closure.
No queued write may survive session disposal.

The exact-byte callers have these requirements:

| Caller | Required byte sequence | Ordering and failure requirement |
| --- | --- | --- |
| `TerminalInput.sendBytes` and `sendString` | Exact bytes, or UTF-8 text, from arbitrary session clients | Preserve admission order. Reject unsupported input before acceptance. |
| `TerminalKeyEventsHandlerImpl` | The selected encoder's keyboard report | Preserve negotiated key protocols. Do not encode the report a second time. |
| `TerminalMouseEventsHandlerImpl` | One complete encoded mouse report | Keep each report intact and ordered with key input. |
| `PowerShellCompletionContributor` | The fixed trigger `ESC[24~e` | Preserve all bytes. Report rejected admission so completion can finish without waiting for a response. |
| `TerminalCompletionItemInsertion` | Right, DEL, replacement bytes, then Left | Admit the complete edit as one operation. Reject a stale command context before admission. |
| `TerminalInlineCompletionEditorInsertHandler` | Literal insertion bytes | Preserve literal text and insertion order. Do not add paste delimiters. |
| `TerminalViewImpl` IME handler | Committed text encoded as UTF-8 | Preserve composed text. KetraTerm's renderer owns IME input in this integration. |
| Shell integration tests | Literal Ctrl+C, Ctrl+L, Tab, and Enter | Preserve control bytes. An unsupported submission must fail explicitly. |

All these callers must use the session writer.
The session must define whether admission copies bytes or transfers ownership.
An asynchronous transport failure must reach session state and terminate response-dependent operations.
A compound edit needs one bounded queue entry, or an equivalent atomic admission contract.
The current semantic methods return `Unit`; the adapter cannot guarantee admission across a concurrent close.
The adapter reports unsupported byte events and never writes them through the connector.
The view buffers up to 64 supported semantic commands before attachment.
End, text, and Enter use separate native admissions until the library provides a batch operation.

The Swing view uses KetraTerm's semantic key and mouse handling.
Host paste uses the paste API. Supported host commands use text and Enter semantics.
Unsupported controls fail before submission. Arbitrary bytes are never decoded as paste.
Shell completion that requires the fixed PowerShell trigger remains unavailable until the raw input contract exists.

## Performance endpoints

`JediTermVsGhosttyPerfTest` now measures all three backends.
It rotates backend order and creates a new session for each sample.
It waits for a device-status response before it starts the timer.
One endpoint is receipt of the content event that contains the final marker.
The test reports nine measured samples after two warmup runs per workload.
It waits for session disposal before it starts the next sample.
The history workload preloads 6,000 short lines and awaits its marker before the timed cursor updates.
It also reports projected character counts and event counts.

This test includes the connector, emulator, projection schedule, projection work, and output collector.
The loopback connector supplies characters. The KetraTerm test adapter encodes them as UTF-8; its real local process path reads bytes directly.
The content-event endpoint excludes document application.
A second test uses the real view and ends when its model listener runs on the EDT.
These marker endpoints exclude painting, compositor presentation, and a real shell process.
Ghostty can publish the final marker before it restores deferred history.
Its projector defers history when one interval finalizes more than 1,000 rows.
The marker results therefore do not measure complete history application or equal projection work.
A third endpoint waits for the complete frontend text and verifies it after recording the arrival time.
Its scrolling payloads fit all three history limits. This endpoint includes deferred history restoration.
For KetraTerm, this endpoint observes the compatibility document. It does not establish when the native Swing frame changes.
The complete-history log payload has fewer lines than the marker payload. Compare backends within each endpoint.
A result near a projection interval does not establish equal emulator throughput.
Projected character counts are not allocation measurements.
A history preload also changes the projection phase. Compare backends within each workload.

The current Swing API cannot establish a generation-specific completed-paint endpoint through a supported observer.
The rendering benchmark verifies a new color marker in the completed software paint.
It measures the production renderer and excludes screen presentation.

## Evaluation

Dependency setup needs a BOM-aware graph check and the repository's snapshot staging step.
The locked checksums make the selected snapshot reproducible.
The library uses JVM 25, which matches the current Bazel runtime.

The neutral shell contracts accept IntelliJ metadata without KetraTerm's OSC producer.
The Swing renderer removes the need to reproduce terminal painting in an IntelliJ editor.
The main adaptation cost comes from IntelliJ features that assume an editor or exact byte input.
The compatibility document also adds projection work that the Swing renderer does not need.

The published APIs define clear ownership for borrowed render frames and Swing disposal.
The examples cover the basic session and renderer lifecycle.
They need explicit closed-session, input admission, and failure examples for IDE embedding.

The final allocation profiles identify eager history storage as a large startup cost.
At 80 columns, the configured buffers allocate 21,048,000 bytes for row arrays before row objects and other storage.
These arrays are created once per session. Their capacity does not establish recurring allocation pressure.
The smallest library improvement is lazy history row creation with reuse after the ring fills.
The execution samples also identify Unicode classification during ASCII parsing as a repeated library cost.
An ASCII path needs to preserve character-set mapping and grapheme rules before it can replace those lookups.
Each recording includes session creation, warmup, history preload, measured work, and disposal across all three backends.
The marker recordings can end before Ghostty restores deferred history.
Allocation sample weights estimate allocated bytes across those mixed phases.
They do not establish sustained allocation pressure, retained heap, or Ghostty's native allocations.
The separate allocation benchmark measures first use, startup after warmup, repeated workload cycles, and disposal.
Its recurring results include cycle controls, platform tasks, and the benchmark itself.
Use allocated bytes per cycle and observed allocation rate separately from the startup results.
The allocation benchmark also separates reset and output windows. Reset costs must remain visible in comparisons.
The final validation report records the measured values separately from these API findings.
