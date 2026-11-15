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

import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";

export function dokkaLinkFailures(log) {
    const lines = log.split(/\r?\n/);
    const failures = lines.filter(line => line.includes("Couldn't resolve link:"));
    const modules = lines.filter(line => /^> Task .*:dokkaGenerateModuleHtml\b/.test(line));
    if (!/^BUILD SUCCESSFUL\b/m.test(log)) failures.push("Dokka generation did not complete successfully.");
    if (modules.length === 0 || modules.some(line => /\b(?:UP-TO-DATE|FROM-CACHE|SKIPPED)\b/.test(line))) {
        failures.push("Fresh Dokka module diagnostics are required; regenerate with --rerun-tasks --console=plain.");
    }
    return [...new Set(failures)];
}

function main() {
    if (process.argv.length !== 3) throw new Error("Usage: dokka-links.mjs <fresh Gradle Dokka log>");
    const failures = dokkaLinkFailures(readFileSync(process.argv[2], "utf8"));
    if (failures.length) throw new Error(failures.join("\n"));
    console.log("Dokka link check passed.");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        main();
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}
