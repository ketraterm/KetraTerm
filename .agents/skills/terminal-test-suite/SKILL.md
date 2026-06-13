---
name: terminal-test-suite
description: Test design guidance for this terminal emulator. Use when adding, reviewing, hardening, or refactoring tests across parser, core, host, Unicode, ANSI protocols, mode handling, SGR/OSC/DCS, or terminal feature work.
---

# Terminal Test Suite

Use this skill when adding or reviewing tests.

## Principle

Tests must assert real terminal semantics. They must fail when implementation is
incorrect. Never rewrite tests to document wrong current behavior.

## Structure

- Use nested test classes for large protocol areas.
- Prefer explicit event assertions over vague `doesNotThrow` coverage.
- Use fixtures for setup only; keep semantic expectations visible.
- Add full-path tests for user-observable parser behavior.
- Use public APIs for core behavior unless testing a narrow internal invariant.

## Coverage Checklist

Cover:

- normal paths.
- omitted/default parameters.
- malformed input and recovery.
- overflow and max-capacity behavior.
- hostile sequences.
- boundary values.
- chunking around structural bytes.
- state reset and cleanup after flush/abort/end-of-input.

## Useful Layers

- generated table/signature tests.
- small pure helper tests.
- FSM matrix tests.
- action engine tests.
- command dispatcher recording-sink tests.
- parser full byte-stream tests.
- core public API and invariant tests.
- parser-to-core host tests.

DRY is good only while expectations remain readable. A little repetition is
better than hiding the behavior under test.
