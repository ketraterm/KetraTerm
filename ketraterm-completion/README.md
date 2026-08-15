# KetraTerm Completion (`:ketraterm-completion`)

Pure Kotlin command-line completion models and spec-backed evaluation for
KetraTerm hosts.

The module is intentionally independent of Swing, IntelliJ, PTY, sessions, and
terminal rendering. Hosts provide command-line text, cursor position, and
context; the engine returns bounded candidates with explicit replacement ranges.
