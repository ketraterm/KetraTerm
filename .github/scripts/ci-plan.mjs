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

import {appendFileSync, existsSync, readdirSync, readFileSync, writeFileSync} from "node:fs";
import {execFileSync} from "node:child_process";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";

export const checks = ["headless", "platform", "consumers", "benchmarks", "website", "differential"];
export const platforms = [
    {os: "ubuntu-26.04", shells: "BASH,ZSH,FISH,POWERSHELL"},
    {os: "macos-15", shells: "BASH,ZSH,FISH,POWERSHELL"},
    {os: "windows-2025", shells: "WINDOWS_POWERSHELL,POWERSHELL,GIT_BASH"},
];
const root = fileURLToPath(new URL("../../", import.meta.url));
const modules = new Set(readdirSync(root).filter(name => name.startsWith("ketraterm-") && existsSync(resolve(root, name, "build.gradle.kts"))));
// Known platform-independent implementations still run Linux shell/window tests.
// Everything else keeps all three operating systems; build changes request full CI.
const platformIndependent = new Set([
    "ketraterm-protocol", "ketraterm-parser", "ketraterm-core", "ketraterm-host",
    "ketraterm-input", "ketraterm-render-api", "ketraterm-render-cache", "ketraterm-completion",
]);

/** One registry is shared with Gradle; environment tags still select individual tests. */
export function parseEnvironments(text) {
    const result = {};
    for (const line of text.split(/\r?\n/).map(line => line.trim()).filter(line => line && !line.startsWith("#"))) {
        const match = /^(native|desktop)=(ketraterm-[a-z-]+(?:,ketraterm-[a-z-]+)*)$/.exec(line);
        if (!match || result[match[1]]) throw new Error(`Invalid environment registry entry: ${line}`);
        result[match[1]] = new Set(match[2].split(","));
    }
    if (!result.native || !result.desktop) throw new Error("Both native and desktop module owners are required");
    for (const names of Object.values(result)) {
        for (const name of names) if (!modules.has(name)) throw new Error(`Unknown environment owner: ${name}`);
    }
    return result;
}

export function fullPlan(reason) {
    return {full: true, ...Object.fromEntries(checks.map(check => [check, true])), platforms, reasons: [reason]};
}

/** Conservative job selection, not a second dependency graph. Gradle owns test-classpath invalidation. */
export function planChanges(paths, environments) {
    if (!paths.length) return fullPlan("No changed paths available");
    const plan = {full: false, ...Object.fromEntries(checks.map(check => [check, false])), platforms: [platforms[0]], reasons: []};
    const select = (...names) => names.forEach(name => { plan[name] = true; });
    for (const path of paths) {
        if (!path || path.startsWith("/") || path.includes("\\") || path.split("/").includes("..")) return fullPlan("Unrecognized path");
        if (path.startsWith(".github/") || path.startsWith("gradle/") || /(?:^|\/)(?:[^/]+\.gradle\.kts|gradle\.properties|gradlew(?:\.bat)?|VERSION)$/.test(path)) {
            return fullPlan("Build, toolchain, release, or CI configuration changed");
        }
        if (path.startsWith("website/") || path.startsWith("docs/") || /^[^/]+\.md$/.test(path) ||
            /^ketraterm-[^/]+\/(?:docs\/|(?:README|Module|AGENTS|CHANGELOG)\.md$)/.test(path) || path === "LICENSE") {
            select("website");
            if (path === "ketraterm-ui-swing/README.md") select("consumers");
            continue;
        }
        if (path.startsWith("tools/xterm-oracle/")) {
            select("headless", "differential");
            continue;
        }
        const [module, ...parts] = path.split("/");
        if (!modules.has(module)) return fullPlan("Unclassified change");
        const relative = parts.join("/");
        if (relative.startsWith("src/consumerTest/")) {
            select("headless", "consumers");
        } else if (relative.startsWith("api/")) {
            select("headless", "consumers");
        } else if (relative.startsWith("src/test/")) {
            select("headless");
            if (environments.native.has(module) || environments.desktop.has(module)) {
                select("platform");
                plan.platforms = platforms;
            }
        } else if (relative.startsWith("src/jmh/") || module === "ketraterm-benchmarks") {
            select("headless", "benchmarks");
        } else if (relative.startsWith("src/main/")) {
            select("headless", "platform");
            if (!platformIndependent.has(module)) plan.platforms = platforms;
            if (module !== "ketraterm-app") select("consumers", "benchmarks");
            if (["ketraterm-protocol", "ketraterm-parser", "ketraterm-core", "ketraterm-host", "ketraterm-render-api", "ketraterm-render-cache", "ketraterm-testkit"].includes(module)) select("differential");
            if (path === "ketraterm-app/src/main/resources/io/github/ketraterm/app/icons/logo.svg") select("website");
        } else {
            return fullPlan("Unclassified module change");
        }
    }
    plan.reasons.push("Selected affected job groups; Gradle tracks downstream JVM test inputs");
    return plan;
}

/** NUL-separated output and disabled rename detection retain both sides of renames and deletions. */
export function changedPaths(base, git = args => execFileSync("git", args, {cwd: root, encoding: "utf8"})) {
    if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/i.test(base ?? "") || /^0+$/.test(base)) throw new Error("A valid base commit is required");
    return git(["diff", "--name-only", "--no-renames", "-z", `${base}...HEAD`]).split("\0").filter(Boolean);
}

/** Unknown events and unavailable diffs always request the complete validation set. */
export function selectPlan({event, full, base}, environments, git) {
    if (full === "true" || !["pull_request", "merge_group"].includes(event)) {
        return fullPlan("Full validation requested by event or caller");
    }
    try { return planChanges(changedPaths(base, git), environments); }
    catch (error) { return fullPlan(`Changed-path discovery failed: ${error.message}`); }
}

function main() {
    const environments = parseEnvironments(readFileSync(resolve(root, "gradle/test-environments.properties"), "utf8"));
    const plan = selectPlan({event: process.env.GITHUB_EVENT_NAME, full: process.env.CI_FULL, base: process.env.CI_BASE_SHA}, environments);
    const outputs = {
        ...Object.fromEntries(checks.map(check => [check, String(plan[check])])),
        platform_matrix: JSON.stringify({include: plan.platforms}),
        consumer_matrix: JSON.stringify({include: plan.full ? platforms : [platforms[0]]}),
        revision: execFileSync("git", ["rev-parse", "HEAD"], {cwd: root, encoding: "utf8"}).trim(),
    };
    if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, Object.entries(outputs).map(([key, value]) => `${key}=${value}\n`).join(""));
    // Evidence is usable only after this entire workflow succeeds, not merely this job.
    writeFileSync("ci-validation.json", JSON.stringify({
        repository: process.env.GITHUB_REPOSITORY,
        run: process.env.GITHUB_RUN_ID,
        attempt: process.env.GITHUB_RUN_ATTEMPT,
        full: plan.full,
        tree: execFileSync("git", ["rev-parse", "HEAD^{tree}"], {cwd: root, encoding: "utf8"}).trim(),
    }));
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY,
        `CI selection (${plan.full ? "full" : "affected groups"})\n\n| Check | Required |\n| --- | --- |\n${checks.map(check => `| ${check} | ${plan[check]} |`).join("\n")}\n\n${plan.reasons.join("\n")}\n`);
    console.log(JSON.stringify(outputs, null, 2));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
