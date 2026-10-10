# KetraTerm Headless

`ketraterm-headless` is the dependency entry point for a terminal host that
supplies its own transport and presentation. It brings in the terminal pipeline
and `TerminalSession` through dependency metadata; it contains no runtime code.

The libraries require Java 25. See [library compatibility](../docs/library/compatibility.md)
for compiler and runtime requirements.

## Installation

Import the BOM and select the headless entry point:

```kotlin
dependencies {
    implementation(platform("io.github.ketraterm:ketraterm-bom:{version}"))
    implementation("io.github.ketraterm:ketraterm-headless")
}
```

See the [root setup guide](../README.md#using-the-libraries) for repositories and version selection.

For Maven, import the [BOM](../ketraterm-bom/README.md#maven) in
`dependencyManagement`, then declare this dependency with POM type:

```xml
<dependency>
  <groupId>io.github.ketraterm</groupId>
  <artifactId>ketraterm-headless</artifactId>
  <type>pom</type>
</dependency>
```

## Included modules

The entry point brings in [session](../ketraterm-session/README.md) and its
parser, core, input, transport, and rendering dependencies.

For a running terminal, implement a `TerminalConnector` and follow the
[session example](../ketraterm-session/README.md#usage). The connector delivers
ordered raw bytes; the session owns terminal parsing, mutation, and outbound
ordering. A renderer copies borrowed frame data during session read callbacks.

Add [PTY support](../ketraterm-pty/README.md) for local processes and
[shell integration](../ketraterm-shell-integration/README.md) for KetraTerm's
OSC metadata producer when needed. For a Swing view, choose the
[Swing entry point](../ketraterm-swing/README.md). Individual modules remain
available for hosts that assemble a smaller pipeline.
