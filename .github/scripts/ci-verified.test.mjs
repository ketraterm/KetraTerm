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
import {test} from "node:test";
import {existsSync, writeFileSync} from "node:fs";
import {join} from "node:path";
import {hasVerifiedSource, matchesValidation} from "./ci-verified.mjs";

const repository = "owner/repo";
const tree = "a".repeat(40);
const run = {
    id: 123, run_attempt: 2, head_sha: "b".repeat(40),
    status: "completed", conclusion: "success", event: "pull_request",
    path: ".github/workflows/tests.yml", repository: {full_name: repository},
};
const evidence = {repository, run: "123", attempt: "2", tree, full: true};

test("only successful matching workflow attempts can supply validation", () => {
    assert.equal(matchesValidation(run, evidence, repository, tree), true);
    for (const change of [
        {status: "in_progress"}, {conclusion: "failure"}, {conclusion: "cancelled"},
        {conclusion: "skipped"}, {event: "pull_request_target"},
        {path: ".github/workflows/other.yml"}, {repository: {full_name: "fork/repo"}},
        {run_attempt: 3}, {id: 124},
    ]) assert.equal(matchesValidation({...run, ...change}, evidence, repository, tree), false, JSON.stringify(change));
    for (const value of [null, {}, {...evidence, full: false}, {...evidence, full: undefined}, {...evidence, tree: "different"},
        {...evidence, repository: "fork/repo"}, {...evidence, attempt: "1"}]) {
        assert.equal(matchesValidation(run, value, repository, tree), false);
    }
});

test("lookup requires both recorded checkout and GitHub commit trees and cleans downloads", () => {
    for (const commitTree of [tree, "different"]) {
        let directory;
        const exec = (command, args) => {
            assert.equal(command, "gh");
            if (args[0] === "run") {
                assert.equal(args[args.indexOf("--name") + 1], "ci-validation-2");
                directory = args[args.indexOf("--dir") + 1];
                writeFileSync(join(directory, "ci-validation.json"), JSON.stringify(evidence));
                return "";
            }
            if (args[1].includes("/git/commits/")) {
                assert.ok(args[1].endsWith(run.head_sha));
                return JSON.stringify({tree: {sha: commitTree}});
            }
            assert.ok(args[1].endsWith("status=success&per_page=30"));
            return JSON.stringify({workflow_runs: [run]});
        };
        assert.equal(hasVerifiedSource(repository, tree, exec), commitTree === tree);
        assert.equal(existsSync(directory), false);
    }
});

test("missing, malformed, and unavailable evidence require full verification", () => {
    for (const failure of ["api", "download", "json", "commit"]) {
        let directory;
        const exec = (command, args) => {
            if (failure === "api") throw new Error("API unavailable");
            if (args[0] === "run") {
                directory = args[args.indexOf("--dir") + 1];
                if (failure === "download") throw new Error("Expired artifact");
                writeFileSync(join(directory, "ci-validation.json"), failure === "json" ? "{" : JSON.stringify(evidence));
                return "";
            }
            if (args[1].includes("/git/commits/")) throw new Error("Commit unavailable");
            return JSON.stringify({workflow_runs: [run]});
        };
        assert.equal(hasVerifiedSource(repository, tree, exec), false, failure);
        if (directory) assert.equal(existsSync(directory), false);
    }
    assert.equal(hasVerifiedSource(repository, tree, () => JSON.stringify({workflow_runs: []})), false);
});

test("an expired recent record does not hide usable older validation", () => {
    const exec = (command, args) => {
        if (args[0] === "run") {
            if (args[2] === "124") throw new Error("Expired artifact");
            writeFileSync(join(args[args.indexOf("--dir") + 1], "ci-validation.json"), JSON.stringify(evidence));
            return "";
        }
        if (args[1].includes("/git/commits/")) return JSON.stringify({tree: {sha: tree}});
        return JSON.stringify({workflow_runs: [{...run, id: 124}, run]});
    };
    assert.equal(hasVerifiedSource(repository, tree, exec), true);
});
