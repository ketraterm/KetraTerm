# Contributing to KetraTerm

Thanks for your interest in contributing to KetraTerm!

## Contributing Workflow

Check existing issues and work before starting a substantial change. Discuss new
capabilities or public API changes with the maintainers, then submit a focused
pull request against the repository's default branch.
Keep pull request branches free of merge commits.

For a bug fix, include a reproducer and a regression test that asserts the
expected result. For an API change, describe the consumer workflow, ownership,
threading, failure behavior, and compatibility impact. Avoid unrelated refactors.

## Building & Running

Follow the [development JDK setup](README.md#development) before running the builds.

### Standalone Application (`ketraterm-app`)

Run these commands from the repository root:

```bash
./gradlew :ketraterm-app:run
./gradlew :ketraterm-app:test
```

### IntelliJ Platform Plugin

The [IntelliJ plugin](https://github.com/ketraterm/KetraTerm-IntelliJ) has its own repository,
Gradle wrapper, CI, and releases. Clone and open that repository independently.
This repository publishes the shared libraries consumed by the plugin.

### Code Formatting

Run `./gradlew spotlessApply` from the repository root before submitting Kotlin
or Gradle changes.

## Verification

Start with the affected module's tests, then add checks for the boundaries you
changed:

```bash
./gradlew :ketraterm-core:test
./gradlew :ketraterm-session:test
./gradlew :ketraterm-ui-swing:test
./gradlew test
./gradlew nativeTest
./gradlew desktopTest
./gradlew publicationChecks
```

Use exact byte-stream tests for parser-to-core behavior and explicit handshakes
or controlled schedulers for concurrent behavior. Cover relevant malformed input,
bounds, recovery, and chunking. Native PTY and external-oracle tests have separate
requirements documented by their modules. The [test organization guide](docs/development/testing.md)
defines JVM, native-process, and desktop suites, tags, naming, fixtures, and CI.
`test` is headless; run `desktopTest` on an interactive or virtual display.

Public API changes need KDoc, an intentionally reviewed ABI baseline, and Java/Kotlin
consumer verification. `publicationChecks` runs the release verification against
a build-local Maven repository without publishing remotely. Update the canonical
feature and gap maps when supported behavior or scope changes.

## Rules for Commit Messages

Use a short imperative subject that identifies the change. Explain its reason
and relevant tradeoffs in the body, and reference an issue when one exists.
Describe the final implementation rather than the sequence of experiments.
