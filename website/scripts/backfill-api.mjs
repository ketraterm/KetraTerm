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

import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {spawnSync} from "node:child_process";
import {copyArchives} from "../src/archive.mjs";
import {exportRelease, releaseTags} from "../src/release-sources.mjs";

export function backfillApi({ root, previous, archive, build = buildDokka }) {
  // Preserve the original archive until every missing release builds successfully.
  copyArchives(previous, archive);
  if (previous && fs.existsSync(path.join(previous, "CNAME")))
    fs.copyFileSync(path.join(previous, "CNAME"), path.join(archive, "CNAME"));
  const tags = releaseTags(root);
  for (const id of tags) {
    const target = path.join(archive, "library/api", id);
    if (fs.existsSync(path.join(target, "index.html"))) {
      console.log(`Retained ${id}`);
      continue;
    }
    const source = exportRelease(root, id);
    console.log(`Building Dokka for ${id} (${source.ref})`);
    build(source);
    const output = path.join(source.root, "build/dokka/html");
    if (!fs.existsSync(path.join(output, "index.html")))
      throw new Error(`Dokka did not produce an index for ${id}`);
    // Stage outside the version tree: a prerelease ending in ".pending" is
    // otherwise indistinguishable from a real version after an interruption.
    const staging = path.join(archive, "library/.pending-api", id);
    fs.rmSync(staging, { recursive: true, force: true });
    fs.cpSync(output, staging, { recursive: true });
    fs.writeFileSync(
      path.join(staging, "source.json"),
      JSON.stringify({ tag: id, commit: source.ref }, null, 2),
    );
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.renameSync(staging, target);
    console.log(`Archived ${id}`);
  }
  return tags;
}

function buildDokka(source) {
  const windows = process.platform === "win32";
  const args = [":dokkaGenerate", "--no-daemon", "--max-workers=2"];
  const result = spawnSync(
    windows ? "cmd.exe" : "bash",
    windows
      ? ["/d", "/s", "/c", `gradlew.bat ${args.join(" ")}`]
      : ["./gradlew", ...args],
    {
      cwd: source.root,
      env: { ...process.env, RELEASE: "true" },
      stdio: "inherit",
    },
  );
  if (result.error) throw result.error;
  if (result.status !== 0)
    throw new Error(`Dokka failed for ${source.id} (exit ${result.status})`);
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const root = path.resolve(fileURLToPath(new URL("../../", import.meta.url)));
  const tags = backfillApi({
    root,
    previous:
      process.env.WEBSITE_ARCHIVE && path.resolve(process.env.WEBSITE_ARCHIVE),
    archive: path.join(root, "build/website-history"),
  });
  console.log(`API history complete: ${tags.length} release tags.`);
}
