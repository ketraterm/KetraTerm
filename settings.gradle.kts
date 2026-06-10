plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Lattice"
include(
    ":terminal-core",
    ":terminal-render-api",
    ":terminal-parser",
    ":terminal-protocol",
    ":terminal-input",
    ":terminal-integration",
    ":terminal-pty",
    ":terminal-transport-api",
    ":terminal-session",
    ":terminal-testkit",
    ":terminal-workspace",
    ":terminal-standalone-app",
    ":terminal-ui-swing",
    ":terminal-ui-swing-demo",
    ":terminal-benchmarks",
    ":terminal-render-cache",
)
