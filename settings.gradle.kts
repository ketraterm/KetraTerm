plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KetraTerm"
include(
    ":ketraterm-core",
    ":ketraterm-render-api",
    ":ketraterm-parser",
    ":ketraterm-protocol",
    ":ketraterm-input",
    ":ketraterm-host",
    ":ketraterm-pty",
    ":ketraterm-transport-api",
    ":ketraterm-session",
    ":ketraterm-testkit",
    ":ketraterm-workspace",
    ":ketraterm-app",
    ":ketraterm-ui-swing",
    ":ketraterm-benchmarks",
    ":ketraterm-render-cache",
)
