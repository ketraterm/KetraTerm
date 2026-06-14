plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "JvTerm"
include(
    ":jvterm-core",
    ":jvterm-render-api",
    ":jvterm-parser",
    ":jvterm-protocol",
    ":jvterm-input",
    ":jvterm-host",
    ":jvterm-pty",
    ":jvterm-transport-api",
    ":jvterm-session",
    ":jvterm-testkit",
    ":jvterm-workspace",
    ":jvterm-app",
    ":jvterm-ui-swing",
    ":jvterm-benchmarks",
    ":jvterm-render-cache",
)
