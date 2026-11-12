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
import {readFileSync} from "node:fs";

// These job predicates use only boolean expressions supported by both Actions and JS.
// Exercise the actual workflow conditions, particularly the intentionally skipped verify job.
const workflow = readFileSync(new URL("../workflows/release.yml", import.meta.url), "utf8");
function condition(job) {
    const body = workflow.split(`  ${job}:`)[1].split(/\n  \w+:/)[0];
    const expression = body.match(/\n    if: (.+)/)[1].replace(/^\$\{\{\s*|\s*\}\}$/g, "");
    return new Function("needs", "cancelled", `return ${expression}`);
}

test("tag finalization needs successful source verification, whether reused or fresh", () => {
    const allowed = condition("finalize");
    for (const verified of ["true", "false", ""]) {
        for (const result of ["success", "failure", "cancelled", "skipped"]) {
            const needs = {
                prepare: {result: "success", outputs: {needed: "true", verified}},
                verify: {result},
            };
            assert.equal(allowed(needs, () => false), verified === "true" || result === "success");
            assert.equal(allowed(needs, () => true), false);
            needs.prepare.outputs.needed = "false";
            assert.equal(allowed(needs, () => false), false);
            needs.prepare.outputs.needed = "true";
            needs.prepare.result = "failure";
            assert.equal(allowed(needs, () => false), false);
        }
    }
});

test("verification precedes tag creation and publishers use the finalized revision", () => {
    const prepare = workflow.split("  prepare:")[1].split("\n  verify:")[0];
    assert.doesNotMatch(prepare, /git (?:tag|.*push --atomic)/);
    const verify = workflow.split("  verify:")[1].split("\n  finalize:")[0];
    assert.match(verify, /revision: \$\{\{ needs.prepare.outputs.revision \}\}/);
    const finalize = workflow.split("  finalize:")[1].split("\n  maven:")[0];
    assert.match(finalize, /needs: \[prepare, verify\]/);
    assert.match(finalize, /ref: \$\{\{ needs.prepare.outputs.revision \}\}/);
    assert.match(finalize, /git tag/);
    for (const job of ["maven", "binaries"]) {
        const body = workflow.split(`  ${job}:`)[1].split(/\n  \w+:/)[0];
        assert.match(body, /revision: \$\{\{ needs.finalize.outputs.revision \}\}/);
    }
});

test("Maven publication waits for successful tag finalization", () => {
    for (const result of ["success", "failure", "cancelled", "skipped"]) {
        const needs = {prepare: {result: "success", outputs: {needed: "true"}}, finalize: {result}};
        assert.equal(condition("maven")(needs, () => false), result === "success");
        assert.equal(condition("maven")(needs, () => true), false);
    }
});

test("binary publishing continues after reused verification but never after failed Maven publication", () => {
    const allowed = condition("binaries");
    for (const result of ["success", "failure", "cancelled", "skipped"]) {
        const needs = {
            prepare: {result: "success", outputs: {needed: "true", verified: "true"}},
            maven: {result},
        };
        assert.equal(allowed(needs, () => false), result === "success");
        assert.equal(allowed(needs, () => true), false);
    }
});
