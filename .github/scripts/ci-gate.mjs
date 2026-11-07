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

import {resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {checks} from "./ci-plan.mjs";

/** A skipped selected job, a failed planner, or any cancelled/failed dependency cannot pass. */
export function verifyResults(needs) {
    if (needs?.plan?.result !== "success") throw new Error("CI planning did not succeed");
    const plan = JSON.parse(needs.plan.outputs.plan);
    for (const check of checks) {
        if (typeof plan[check] !== "boolean") throw new Error(`Invalid selection for ${check}`);
        const result = needs[check]?.result;
        if (result !== "success" && !(result === "skipped" && !plan[check])) {
            throw new Error(`${check}: selected=${plan[check]}, result=${result ?? "missing"}`);
        }
    }
    for (const [name, job] of Object.entries(needs)) {
        if (["failure", "cancelled"].includes(job.result)) throw new Error(`${name}: ${job.result}`);
    }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    verifyResults(JSON.parse(process.env.CI_NEEDS ?? "null"));
    console.log("All selected CI checks passed.");
}
