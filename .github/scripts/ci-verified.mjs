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

import {appendFileSync, mkdtempSync, readFileSync, rmSync} from "node:fs";
import {execFileSync} from "node:child_process";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

/** Reuse only successful repository CI for the exact source tree, including build/workflow files. */
export function matchesValidation(run, evidence, repository, tree) {
    return run.status === "completed" && run.conclusion === "success" &&
        run.path === ".github/workflows/tests.yml" &&
        run.repository?.full_name === repository &&
        ["pull_request", "merge_group", "push", "schedule", "workflow_dispatch"].includes(run.event) &&
        evidence?.full === true && evidence.repository === repository && evidence.run === String(run.id) &&
        evidence.attempt === String(run.run_attempt) && evidence.tree === tree;
}

export function hasVerifiedSource(repository, tree, exec = execFileSync) {
    try {
        // Bounded lookup: old, expired, unavailable, or ambiguous evidence means run verification again.
        const {workflow_runs: runs} = JSON.parse(exec("gh", ["api",
            `repos/${repository}/actions/workflows/tests.yml/runs?status=success&per_page=30`,
        ], {encoding: "utf8"}));
        for (const run of runs) {
            const directory = mkdtempSync(join(tmpdir(), "ketraterm-ci-"));
            try {
                exec("gh", ["run", "download", String(run.id), "--repo", repository,
                    "--name", `ci-validation-${run.run_attempt}`, "--dir", directory], {stdio: "pipe"});
                const evidence = JSON.parse(readFileSync(join(directory, "ci-validation.json"), "utf8"));
                if (matchesValidation(run, evidence, repository, tree)) {
                    // Check GitHub's commit tree too: PR artifacts alone are not trusted evidence.
                    const commit = JSON.parse(exec("gh", ["api",
                        `repos/${repository}/git/commits/${run.head_sha}`], {encoding: "utf8"}));
                    if (commit.tree?.sha !== tree) continue;
                    console.log(`Reusing source validation from run ${run.id}`);
                    return true;
                }
            } catch {
                // A missing artifact never authorizes skipping verification.
            } finally {
                rmSync(directory, {recursive: true, force: true});
            }
        }
    } catch {
        console.log("CI evidence unavailable; full verification is required.");
    }
    return false;
}

function main() {
    const repository = process.env.GITHUB_REPOSITORY;
    const revision = process.env.CI_SOURCE_REVISION;
    if (!/^[\w.-]+\/[\w.-]+$/.test(repository ?? "") || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(revision ?? "")) {
        throw new Error("Repository and pinned source revision are required");
    }
    const tree = execFileSync("git", ["rev-parse", `${revision}^{tree}`], {encoding: "utf8"}).trim();
    const verified = hasVerifiedSource(repository, tree);
    appendFileSync(process.env.GITHUB_OUTPUT, `verified=${verified}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
