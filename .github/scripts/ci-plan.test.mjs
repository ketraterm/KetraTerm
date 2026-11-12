/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import assert from "node:assert/strict";
import {readdirSync, readFileSync} from "node:fs";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {test} from "node:test";
import {changedPaths, checks, fullPlan, parseEnvironments, planChanges, selectPlan} from "./ci-plan.mjs";


const root = fileURLToPath(new URL("../../", import.meta.url));
const environments = parseEnvironments(readFileSync(resolve(root, "gradle/test-environments.properties"), "utf8"));
const selected = plan => checks.filter(check => plan[check]);

for (const [path, expected] of [
    ["docs/development/testing.md", ["website"]],
    ["website/src/pages/index.astro", ["website"]],
    ["ketraterm-ui-swing/README.md", ["consumers", "website"]],
    ["ketraterm-core/src/test/kotlin/CursorTest.kt", ["headless"]],
    ["ketraterm-core/src/test/resources/fixture.md", ["headless"]],
    ["ketraterm-app/src/main/resources/help.md", ["headless", "platform"]],
    ["ketraterm-app/src/test/kotlin/WindowTest.kt", ["headless", "platform"]],
    ["ketraterm-app/src/main/kotlin/Window.kt", ["headless", "platform"]],
    ["ketraterm-workspace/src/main/kotlin/TerminalWorkspace.kt", ["headless", "platform", "consumers", "benchmarks"]],
    ["ketraterm-completion-persistence/src/main/kotlin/Coordinator.kt", ["headless", "platform", "consumers", "benchmarks"]],
    ["ketraterm-ui-swing/src/main/kotlin/Painter.kt", ["headless", "platform", "consumers", "benchmarks"]],
    ["ketraterm-parser/src/main/kotlin/Parser.kt", ["headless", "platform", "consumers", "benchmarks", "differential"]],
    ["ketraterm-testkit/src/main/kotlin/Connector.kt", checks.filter(check => check !== "website")],
    ["ketraterm-testkit/src/consumerTest/ui-swing/Consumer.kt", ["headless", "consumers"]],
    ["ketraterm-core/api/core.api", ["headless", "consumers"]],
    ["tools/xterm-oracle/package-lock.json", ["headless", "differential"]],
]) {
    test(`selection for ${path}`, () => assert.deepEqual(selected(planChanges([path], environments)), expected));
}

for (const path of ["build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "VERSION",
    "gradle/libs.versions.toml", "gradle/test-environments.properties", ".github/scripts/ci-plan.mjs",
    "ketraterm-intellij-plugin/build.gradle.kts", "unclassified.file", "ketraterm-new/src/main/New.kt", "../outside"]) {
    test(`full fallback for ${path}`, () => assert.equal(planChanges([path], environments).full, true));
}

test("empty discovery requests full coverage", () => assert.deepEqual(selected(planChanges([], environments)), checks));
test("mixed changes take the union", () => assert.deepEqual(selected(planChanges([
    "docs/README.md", "ketraterm-core/src/test/Test.kt",
], environments)), ["headless", "website"]));

test("diff discovery retains spaces, deletions, and both rename paths without shell parsing", () => {
    const base = "a".repeat(40);
    const paths = changedPaths(base, args => {
        assert.deepEqual(args, ["diff", "--name-only", "--no-renames", "-z", `${base}...HEAD`]);
        return "old source.kt\0new source.kt\0deleted.kt\0";
    });
    assert.deepEqual(paths, ["old source.kt", "new source.kt", "deleted.kt"]);
    assert.deepEqual(selected(planChanges(["docs/old.md", "ketraterm-core/src/main/New.kt"], environments)),
        ["headless", "platform", "consumers", "benchmarks", "website", "differential"]);
});

test("platform-independent production changes retain Linux integration and consumer coverage", () => {
    for (const module of ["protocol", "parser", "core", "host", "input", "render-api", "render-cache", "completion"]) {
        const plan = planChanges([`ketraterm-${module}/src/main/Changed.kt`], environments);
        assert.equal(plan.platform, true, module);
        assert.deepEqual(plan.platforms.map(platform => platform.os), ["ubuntu-26.04"], module);
        assert.equal(plan.headless, true, module);
        assert.equal(plan.consumers, true, module);
        assert.equal(plan.benchmarks, true, module);
    }
});

test("OS-facing and shared integration changes retain all platform checks", () => {
    for (const module of ["app", "pty", "workspace", "ui-swing", "ui-swing-host", "session",
        "shell-integration", "completion-host", "completion-persistence", "transport-api", "testkit", "examples"]) {
        const plan = planChanges(["ketraterm-core/src/main/Changed.kt", `ketraterm-${module}/src/main/Changed.kt`], environments);
        assert.equal(plan.platform, true, module);
        assert.deepEqual(plan.platforms.map(platform => platform.os), ["ubuntu-26.04", "macos-15", "windows-2025"], module);
    }
});

test("mixed changes preserve three platforms regardless of path order", () => {
    const paths = ["ketraterm-app/src/test/WindowTest.kt", "ketraterm-core/src/main/Core.kt"];
    for (const order of [paths, [...paths].reverse()]) {
        const plan = planChanges(order, environments);
        assert.equal(plan.platform, true);
        assert.equal(plan.platforms.length, 3);
    }
    assert.equal(fullPlan("Release").platforms.length, 3);
});

test("Test Suite runs on PRs, merge groups, weekly and manually, never on pushes", () => {
    const workflow = readFileSync(resolve(root, ".github/workflows/tests.yml"), "utf8");
    const triggers = workflow.split(/\r?\non:\r?\n/)[1].split(/\r?\npermissions:/)[0];
    assert.doesNotMatch(triggers, /^  push:/m);
    for (const event of ["pull_request", "merge_group", "workflow_dispatch", "workflow_call"]) {
        assert.match(triggers, new RegExp(`^  ${event}:`, "m"));
    }
    assert.match(triggers, /cron: '43 3 \* \* 0'/);
    const campaigns = readFileSync(resolve(root, ".github/workflows/terminal-differential.yml"), "utf8");
    assert.match(campaigns, /cron: '17 2 \* \* \*'/);
});
test("missing or invalid base commits cannot silently skip checks", () => {
    for (const base of [undefined, "", "0".repeat(40), "--bad-option", "a".repeat(41)]) assert.throws(() => changedPaths(base));
});

test("only pull requests and merge groups may select affected checks", () => {
    const base = "a".repeat(40);
    const git = () => "docs/README.md\0";
    for (const event of ["pull_request", "merge_group"]) {
        assert.deepEqual(selected(selectPlan({event, base}, environments, git)), ["website"]);
        assert.equal(selectPlan({event, base, full: "true"}, environments, git).full, true);
    }
    for (const event of ["push", "schedule", "workflow_dispatch", "workflow_call", undefined]) {
        assert.equal(selectPlan({event, base}, environments, git).full, true);
    }
    assert.equal(selectPlan({event: "pull_request"}, environments, git).full, true);
    assert.equal(selectPlan({event: "pull_request", base}, environments, () => { throw new Error("Unavailable base"); }).full, true);
});
test("invalid environment registries fail closed", () => {
    for (const text of ["", "native=ketraterm-app", "native=missing\ndesktop=ketraterm-app", "native=ketraterm-app\nnative=ketraterm-pty\ndesktop=ketraterm-app"]) {
        assert.throws(() => parseEnvironments(text));
    }
});

test("every environment-tagged source has a declared module owner", () => {
    function visit(directory, module) {
        for (const entry of readdirSync(directory, {withFileTypes: true})) {
            const path = resolve(directory, entry.name);
            if (entry.isDirectory()) visit(path, module);
            else if (/\.(kt|java)$/.test(entry.name)) {
                for (const match of readFileSync(path, "utf8").matchAll(/@Tag\s*\(\s*"(native|desktop)"\s*\)/g)) {
                    assert.ok(environments[match[1]].has(module), `${module} must declare ${match[1]} tests in gradle/test-environments.properties`);
                }
            }
        }
    }
    for (const entry of readdirSync(root, {withFileTypes: true})) {
        if (entry.isDirectory() && entry.name.startsWith("ketraterm-")) {
            const path = resolve(root, entry.name, "src/test");
            try { readdirSync(path); } catch (error) { if (error.code === "ENOENT") continue; throw error; }
            visit(path, entry.name);
        }
    }
});
