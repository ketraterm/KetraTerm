# KetraTerm

KetraTerm is a Terminal Emulator application as well as a set of Kotlin/JVM terminal-emulation libraries for desktop and
headless hosts. It provides streaming terminal parsing, grid and scrollback
storage, input encoding, session management, and a reusable Swing component.
Local PTY hosting, shell integration, and command completion are optional.

The libraries target Java 25. Kotlin consumers need Kotlin 2.4 or newer; Java
consumers can use the same public APIs. See [library compatibility](docs/library/compatibility.md)
for the supported compiler, runtime, and publication contracts.

## Features

- Unicode grapheme clusters, combining marks, wide characters, and emoji sequences
- Scrollback with resize reflow and wrap-aware text selection
- 256-color and 24-bit true color rendering
- Synchronized output for coordinated screen updates
- Kitty keyboard encoding, bracketed paste, and pixel-coordinate mouse reporting
- OSC 8 hyperlinks and asynchronous scrollback search
- Shell integration with command navigation, exit status, and output extraction
- Extensible command completion with contextual ranking and bounded learning
- Host-controlled permissions for clipboard access and terminal actions
- Customizable Swing rendering, font fallback, themes, and key bindings
- Headless sessions, custom transports, and optional local PTY hosting
- Allocation-conscious primitive storage and reusable render buffers

## Using the libraries

Choose an entry point and add optional integrations as needed:

| Dependency | Use |
| --- | --- |
| [ketraterm-headless](ketraterm-headless/README.md) | Terminal pipeline and session with a host-owned transport. |
| [ketraterm-swing](ketraterm-swing/README.md) | Swing terminal component and its headless dependencies. |
| [ketraterm-pty](ketraterm-pty/README.md) | Local processes through Pty4J. |
| [ketraterm-shell-integration](ketraterm-shell-integration/README.md) | Built-in OSC shell metadata producer. |
| [ketraterm-ui-swing-host](ketraterm-ui-swing-host/README.md) | Host actions and completion-to-Swing adapters. |

The headless and Swing entry points contain dependency metadata rather than
runtime code. Individual modules are also available for custom assembly.
The [BOM](ketraterm-bom/README.md) aligns versions without adding runtime dependencies.

Replace `{version}` with the KetraTerm version you want to use.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("io.github.ketraterm:ketraterm-bom:{version}"))
    implementation("io.github.ketraterm:ketraterm-swing")
    implementation("io.github.ketraterm:ketraterm-pty")
}
```

For snapshot builds, also configure the
[Sonatype snapshot repository](https://central.sonatype.com/repository/maven-snapshots/).

For a headless host, use `ketraterm-headless` and implement
[`TerminalConnector`](ketraterm-transport-api/README.md).

<a id="local-pty-example"></a>

## Local processes

For a local process, create a session with `TerminalSessions.createLocalPty`,
bind a `SwingTerminal` on the Swing event dispatch thread, then start the session.
The host owns the session and must close it when it is no longer needed.

See the [PTY guide](ketraterm-pty/README.md#how-to-use) for process startup and
the [Swing example](ketraterm-ui-swing/README.md#how-to-use) for view setup.

## Documentation

Browse the [documentation](docs/README.md) for feature catalogs, embedding guides,
protocol references, and contributor resources. The [feature map](docs/terminal-feature-map.md)
describes supported capabilities; the [gap map](docs/terminal-feature-gap-map.md)
tracks remaining work. Consumer-visible changes are in the [library changelog](CHANGELOG.md).

## Development

Use JDK 25 and the included Gradle wrapper (`gradlew` / `gradlew.bat`).

```bash
./gradlew :ketraterm-app:run
./gradlew test
./gradlew publicationChecks
```

`publicationChecks` validates build-local Maven artifacts, public ABI, formatting,
tests, and isolated consumers; it does not upload artifacts. The IntelliJ plugin
has a separate Gradle build. See [Contributing](CONTRIBUTING.md) for focused
checks and product development commands.

## Authors

* **Gagik Sargsyan** - Creator & Core Maintainer

---

## Links & Resources

* **Terminal Protocol Details**: [xterm control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html)
* **Parser FSM Inspiration**: [Paul Williams' ANSI Parser State Machine](https://vt100.net/emu/dec_ansi_parser)
* **Unicode Segmentation Standard**: [Unicode Standard Annex #29 (UAX #29)](https://www.unicode.org/reports/tr29/)
* **Advanced Keyboard Input Specs**: [Kitty Keyboard Protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/)
* **Native PTY Dependency**: [Pty4J Github Repository](https://github.com/traff/pty4j)
* **Terminal Testing Reference**: [vttest suite homepage](https://invisible-island.net/vttest/)

---

## License

Copyright 2026 Gagik Sargsyan. Licensed under the
[Apache License, Version 2.0](LICENSE).
