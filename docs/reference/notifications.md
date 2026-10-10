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
Default host limits are 256 UTF-16 code units for the title and 1,024 for the body,
clamped without splitting a valid surrogate pair. Parser
[payload limits](protocol.md#payload-limits) apply first.

The standalone host reuses a tray icon and removes it after ten seconds of
inactivity. This is icon cleanup, not notification rate limiting. IntelliJ uses
its own notification service. Severity is a hint to the platform, not a guarantee
of a particular icon or visual style.
