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

### Standalone Application (`ketraterm-app`)

Run these commands from the repository root:

```bash
./gradlew :ketraterm-app:run
./gradlew :ketraterm-app:test
```

### IntelliJ Platform Plugin (`ketraterm-intellij-plugin`)

The plugin is a separate Gradle build with its own wrapper:

```bash
cd ketraterm-intellij-plugin
./gradlew runIde
./gradlew test
```

Its [README](ketraterm-intellij-plugin/README.md) describes IDE requirements and
library integration. Root build commands do not run the plugin's checks.

### Code Formatting

Run `./gradlew spotlessApply` in the owning build before submitting Kotlin or
Gradle changes. For plugin changes, run its wrapper from the plugin directory.

## Verification

Start with the affected module's tests, then add checks for the boundaries you
changed:

```bash
./gradlew :ketraterm-core:test
./gradlew :ketraterm-session:test
./gradlew :ketraterm-ui-swing:test
./gradlew test
./gradlew publicationChecks
```

Use exact byte-stream tests for parser-to-core behavior and explicit handshakes
or controlled schedulers for concurrent behavior. Cover relevant malformed input,
bounds, recovery, and chunking. Native PTY and external-oracle tests have separate
requirements documented by their modules.

Public API changes need KDoc, an intentionally reviewed ABI baseline, and Java/Kotlin
consumer verification. `publicationChecks` runs the release verification against
a build-local Maven repository without publishing remotely. Update the canonical
feature and gap maps when supported behavior or scope changes.

## Rules for Commit Messages

Use a short imperative subject that identifies the change. Explain its reason
and relevant tradeoffs in the body, and reference an issue when one exists.
Describe the final implementation rather than the sequence of experiments.
