# Terminal Workspace Agent Guide

`ketraterm-workspace` owns shared product state for local terminal profiles, tabs,
and workspace lifecycle. Read the [root guide](../AGENTS.md) before changes and
use the [README](README.md) for its current contracts.

## Ownership

- Define launch profiles, built-in discovery, and validated workspace options.
- Coordinate local sessions through `ketraterm-pty`; prepare host-neutral shell
  startup hooks without parsing terminal output.
- Track tab identities, selection, presentation metadata, and session observation.
- Route host-neutral events and clipboard requests to the originating tab.

PTY owns process and stream mechanics. Session owns terminal synchronization,
input encoding admission, and outbound writes. Shell integration owns OSC metadata
interpretation. Products own visual containers, preference schemas and paths,
settings persistence, completion services, and companion commands.

Do not import Swing, AWT, IntelliJ, or other UI toolkits, mutate core internals,
parse terminal protocols, or encode terminal input here.

## Invariants

- Publish a registered tab through `tabOpened` before starting session output.
  Failure during publication or startup must remove the tab and close its session.
- Keep local tab removal distinct from process exit or transport failure. The
  host decides how to present a stopped tab.
- Notify lifecycle listeners outside the workspace state lock. Preserve selection
  revision checks so reentrant callbacks cannot select a removed or superseded tab.
- Keep optional presentation observers supervised separately from session-close
  observation. Cancel tab jobs and detach shell-model registrations during cleanup.
- Attempt every required cleanup even when a callback or connector fails. Preserve
  the first failure and suppress later failures; do not silently swallow errors.
- Preserve callback thread and lock contracts. Never synchronously wait for UI
  work from session metadata callbacks or add implicit UI dispatch.
- Resolve clipboard reads for the requesting tab, preserving cancellation,
  deadlines, startup ordering, and explicit unavailable results.
- Treat profile collection inputs as borrowed read-only data. Keep mutable option
  drafts caller-confined and immutable snapshots detached from those drafts.
- Apply shell environment values as data. Preserve explicit script/command entry
  points and fail unsupported startup-command combinations before process creation.

## Validation

Run formatting and `./gradlew :ketraterm-workspace:test` from the repository root
for implementation changes. Add focused lifecycle and failure tests when changing
observation or cleanup. Shell bootstrap changes need profile tests and appropriate
native integration coverage; respect environment assumptions rather than loosening
assertions. See the [module guide](Module.md#validation) for test categories.

Keep consumer examples in README and package/maintainer context in Module.md.
Link shared session, PTY, and shell contracts instead of copying their policies.
