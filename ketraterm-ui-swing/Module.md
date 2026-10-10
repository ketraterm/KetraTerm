# Module ketraterm-ui-swing

Reusable Swing rendering and interaction for a host-owned `TerminalSession`.
The public component is `io.github.ketraterm.ui.swing.api.SwingTerminal`.
See the [README](README.md#how-to-use) for construction and lifecycle.

## Ownership

This module owns Java2D painting, visual cell geometry, selection, search
presentation, viewport interaction, input collection, hyperlink discovery, and
suggestion UI coordination. Session creation, transport choice, connection
lifetime, completion engines, and product action policy belong to the host.

The Gradle API dependencies are `ketraterm-session` and Kotlin immutable
collections. Input encoding, render API/cache, and coroutine support are
implementation dependencies. Standard JDK Swing/AWT provides the UI toolkit.
There is no PTY, application, or IntelliJ dependency.

## Data flow and threading

Session render publication is copied into an EDT-owned cache. Painting and hit
testing share visual geometry; routine painting does not read a live session
frame. Search, selection extraction, and discovery may take synchronized source
reads. Background search and hyperlink workers publish results through binding
and revision checks; they never access Swing state directly.

The EDT owns components, settings installation, painters, and UI controllers.
Public methods document any asynchronous dispatch or snapshot exceptions.
Binding applies view settings and geometry to the session. One controlling view
per session is supported. Unbinding and disposal release view work without
closing the session.

## Maintainer entry points

| Area | Package / contract |
| --- | --- |
| Component and host services | `api`; [binding contract](README.md#binding-and-configuration-ownership) |
| Settings and palette | `settings`; immutable snapshots with detached builders |
| Input collection | `input`; Swing gestures routed to session input |
| Painting and font caches | `render`; [text rendering](docs/bifurcated-text-rendering.md) |
| Viewport and damage | `viewport`; [repaint contract](docs/swing-repaint-optimization.md) |
| Search / suggestions | `search` / `suggestion`; cancellable component-owned work |
| Hyperlink discovery | `api`; [detector contract](docs/hyperlink-detection.md) |

Internal helper benchmarks are compiled in this module's associated `jmh`
source set. `benchmarkElements` exports them to the central
`ketraterm-benchmarks` harness. Tests use deterministic frames and fake sessions;
public consumption and ABI checks are described below.

Supported capabilities and deferred work are recorded in the root
[feature map](../docs/terminal-feature-map.md) and
[gap map](../docs/terminal-feature-gap-map.md).

## Consumer and ABI verification

The [README example](README.md#how-to-use) is compiled by the [consumer fixtures](../ketraterm-testkit/src/consumerTest/README.md).

```shell
./gradlew :ketraterm-ui-swing:test :ketraterm-ui-swing:checkKotlinAbi
./gradlew :ketraterm-testkit:publishedConsumerTest
```

See the [compatibility contract](../docs/library/compatibility.md) before updating ABI baselines.
