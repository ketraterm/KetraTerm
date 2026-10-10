# Documentation

## Features and products

- [Feature map](terminal-feature-map.md): terminal, shell, desktop, completion, and embedding capabilities.
- [Shell support](features/shells.md): automatic discovery, prompt hooks, and startup commands.
- [Completion](features/completion.md): availability, supported commands, and host-specific sources.
- [Standalone app](../ketraterm-app/README.md) and [IntelliJ plugin](../ketraterm-intellij-plugin/README.md): installation and use.
- [Feature gaps](terminal-feature-gap-map.md): open work and intentional limitations.

## Embedding the library

- [Getting started](../README.md#using-the-libraries): dependencies and entry points.
- [Configuration](library/configuration.md): settings, host services, and localization.
- [Compatibility](library/compatibility.md): supported APIs, runtime requirements, and migrations.
- [Render ownership](library/render-ownership.md): borrowed frames and producer/consumer responsibilities.

Module READMEs provide usage examples. Each module's `Module.md` describes its
dependencies and structure; its `docs/` directory holds detailed contracts.

## Reference

- [Terminal protocols](reference/protocol.md): collection limits, replies, and streaming text behavior.
- [Desktop notifications](reference/notifications.md): OSC forms and host handling.
- [Persistent storage](reference/storage.md): file locations, completion learning, and privacy.
- [Standalone configuration](../ketraterm-app/docs/profile-config-toml.md): TOML settings and path resolution.

## Development

- [Contributing](../CONTRIBUTING.md): local setup, checks, and contribution workflow.
- [Architecture](../ARCHITECTURE.md): module boundaries and data flow.
- [Conformance testing](development/conformance-testing.md): campaigns and failure reproduction.
- [Benchmarks](../ketraterm-benchmarks/README.md): workloads and measurement guidance.
- [Library changelog](../CHANGELOG.md): consumer-visible changes.
