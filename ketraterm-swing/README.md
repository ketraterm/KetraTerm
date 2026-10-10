# KetraTerm Swing

`ketraterm-swing` is the dependency entry point for embedding a terminal in a
Swing application. It exports the reusable `SwingTerminal` component and the
headless session pipeline through dependency metadata; it contains no runtime
code of its own. The libraries require Java 25.

## Installation

Import the BOM and select the Swing entry point:

```kotlin
dependencies {
    implementation(platform("io.github.ketraterm:ketraterm-bom:{version}"))
    implementation("io.github.ketraterm:ketraterm-swing")
}
```

See the [root setup guide](../README.md#using-the-libraries) for repositories and version selection.

For Maven, import the [BOM](../ketraterm-bom/README.md#maven) in
`dependencyManagement`, then declare this dependency with POM type:

```xml
<dependency>
  <groupId>io.github.ketraterm</groupId>
  <artifactId>ketraterm-swing</artifactId>
  <type>pom</type>
</dependency>
```

## Embedding a terminal

The entry point directly exports [Swing UI](../ketraterm-ui-swing/README.md),
which exports [session APIs](../ketraterm-session/README.md) and the same terminal
pipeline described by the [headless entry point](../ketraterm-headless/README.md#included-modules).
There is no need to add both entry points.

Supply a `TerminalSession` backed by your own
[`TerminalConnector`](../ketraterm-transport-api/README.md), then construct and
bind `SwingTerminal` on the Swing event dispatch thread. The host owns the
session: disposing the view releases its binding without closing the session.
See the [view lifecycle and settings guide](../ketraterm-ui-swing/README.md#binding-and-configuration-ownership)
and the [session usage guide](../ketraterm-session/README.md#usage).

Add integrations according to the host's needs:

| Dependency | Purpose |
| --- | --- |
| [`ketraterm-pty`](../ketraterm-pty/README.md) | Local processes through Pty4J. |
| [`ketraterm-shell-integration`](../ketraterm-shell-integration/README.md) | Optional OSC shell metadata producer, configured explicitly by the host. |
| [`ketraterm-ui-swing-host`](../ketraterm-ui-swing-host/README.md) | Search chrome, host actions, clipboard consent, and completion adapters. |

For custom assembly, depend directly on individual modules. For a terminal
without Swing, use [the headless entry point](../ketraterm-headless/README.md).
