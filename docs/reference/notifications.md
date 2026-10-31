# Desktop notifications

Terminal programs can request notifications through OSC 9 or OSC 777. Delivery
requires host permission and an installed notification handler; its appearance
and availability depend on the host platform.

| Form | Meaning |
| --- | --- |
| `OSC 9 ; body ST` | Body-only notification, with a host-selected title. |
| `OSC 777 ; notify ; title ; body ST` | Explicit title and body. |
| `OSC 777 ; notify ; title ; body ; level ST` | Optional KetraTerm severity extension. |

OSC begins with `ESC ]`. These commands end with ST (`ESC \`) or BEL.
Levels are case-insensitive `info`, `warning`, `error`, and `none`.
An unrecognized final field stays in the body and uses info severity. Semicolons
within the body are retained.

ConEmu OSC 9 subcommands 0–4 and 9 are excluded from notification handling.
Subcommand 4 instead updates [retained progress presentation](protocol.md#progress-reporting).
Default host limits are 256 UTF-16 code units for the title and 1,024 for the body,
clamped without splitting a valid surrogate pair. Parser
[payload limits](protocol.md#payload-limits) apply first.

The standalone host reuses a tray icon and removes it after ten seconds of
inactivity. This is icon cleanup, not notification rate limiting. IntelliJ uses
its own notification service. Severity is a hint to the platform, not a guarantee
of a particular icon or visual style.

## Command-finished alerts

Both products offer opt-in alerts when shell metadata reports a completed command
whose observed runtime reaches a configurable threshold. Alerts default to off;
the initial threshold is 10 seconds, configurable from 1 to 86,400 seconds.
The standalone settings dialog and IntelliJ application settings expose both
controls. Standalone TOML uses `behavior.command_finished_alerts_enabled` and
`behavior.command_finished_alert_threshold_seconds`.

Timing follows accepted command-start and completion markers, independently of
completion suggestions. The shell model measures elapsed time using the JVM's
[monotonic clock](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/lang/System.html#nanoTime());
wall-clock metadata is retained for history. Missing timing, abandoned commands,
and unmatched or duplicate finish markers do not generate alerts.

Each tab retains at most one pending alert; further qualifying completions are
coalesced until that alert is delivered or discarded. At delivery, the host
checks current settings and the originating session's notification permission, the
originating pane's keyboard focus, and that the pane/session still exists.
Focus in another tab, split pane, or IDE editor permits an alert. Notification
text reports completion and exit status without including captured command text
or directories. The standalone desktop-notification master switch and native
IDE notification controls also apply. Platform delivery remains best effort.
