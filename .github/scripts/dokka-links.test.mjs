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
import {dokkaLinkFailures} from "./dokka-links.mjs";

const generated = "> Task :ketraterm-core:dokkaGenerateModuleHtml\n> Task :dokkaGeneratePublicationHtml\nBUILD SUCCESSFUL in 12s\n";

test("unresolved KDoc links fail with their original targets and source locations", () => {
    const warnings = [
        "w: [:ketraterm-core:dokkaGenerateModuleHtml] Couldn't resolve link: [dst] in file:///repo/TerminalHostResponseReader.kt:23:9",
        "w: [:ketraterm-pty:dokkaGenerateModuleHtml] Couldn't resolve link: [event.text] in file:///C:/repo/PtyEventListener.kt:223:8",
    ];
    assert.deepEqual(dokkaLinkFailures(warnings.concat(warnings[0], generated).join("\r\n")), warnings);
});

test("unrelated warnings do not fail a successful documentation build", () => {
    assert.deepEqual(dokkaLinkFailures(`w: Deprecated Dokka option\n${generated}`), []);
});

test("missing failed or cached generation cannot hide unresolved link diagnostics", () => {
    assert.ok(dokkaLinkFailures("").some(message => message.includes("did not complete")));
    assert.ok(dokkaLinkFailures(generated.replace("BUILD SUCCESSFUL", "BUILD FAILED")).some(message => message.includes("did not complete")));
    assert.ok(dokkaLinkFailures("BUILD SUCCESSFUL in 1s").some(message => message.includes("Fresh Dokka")));
    for (const status of ["UP-TO-DATE", "FROM-CACHE", "SKIPPED"]) {
        const log = generated.replace("dokkaGenerateModuleHtml\n", `dokkaGenerateModuleHtml ${status}\n`);
        assert.ok(dokkaLinkFailures(log).some(message => message.includes("--rerun-tasks")), status);
    }
});
