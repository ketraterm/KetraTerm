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
import {stableVersion} from "../assets/releases.js";

export const releaseId = (version) => {
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version))
    throw new Error(`Invalid release version: ${version}`);
  return `v${version}`;
};
export function sortVersions(versions) {
  return versions.sort((a, b) => {
    if (a.id === b.id) return 0;
    if (a.id === "dev") return -1;
    if (b.id === "dev") return 1;
    const base = b.id
      .split("-")[0]
      .localeCompare(a.id.split("-")[0], undefined, { numeric: true });
    if (base) return base;
    // Stable releases follow their prereleases chronologically.
    if (stableVersion(a.id)) return -1;
    if (stableVersion(b.id)) return 1;
    return b.id.localeCompare(a.id, undefined, { numeric: true });
  });
}
export function inventory(directory) {
  const ids = new Set();
  for (const family of ["library/api", "library/guides"]) {
    const folder = path.join(directory, family);
    if (fs.existsSync(folder))
      for (const entry of fs.readdirSync(folder, { withFileTypes: true })) {
        if (
          entry.isDirectory() &&
          (entry.name === "dev" ||
            /^v\d+\.\d+\.\d+(?:-[\w.-]+)?$/.test(entry.name))
        )
          ids.add(entry.name);
      }
  }
  return sortVersions(
    [...ids].map((id) => ({
      id,
      label: id === "dev" ? "Development" : id.slice(1),
      stable: stableVersion(id),
      api: fs.existsSync(path.join(directory, "library/api", id, "index.html")),
      guides: fs.existsSync(
        path.join(directory, "library/guides", id, "docs", "README.html"),
      ),
    })),
  );
}
export function copyArchives(previous, output) {
  if (!previous) return;
  if (!fs.existsSync(previous))
    throw new Error(`Archive directory does not exist: ${previous}`);
  for (const item of inventory(previous)) {
    for (const family of ["library/api", "library/guides"]) {
      const source = path.join(previous, family, item.id);
      if (fs.existsSync(source))
        fs.cpSync(source, path.join(output, family, item.id), {
          recursive: true,
        });
    }
  }
  const legacyRoot = path.join(previous, "docs");
  if (fs.existsSync(legacyRoot)) {
    for (const entry of fs.readdirSync(legacyRoot, { withFileTypes: true })) {
      if (
        !entry.isDirectory() ||
        !/^(?:dev|latest|v\d+\.\d+\.\d+(?:-[\w.-]+)?)$/.test(entry.name)
      )
        continue;
      const target = path.join(output, "library/api", entry.name);
      // Once migrated, /docs contains redirects rather than API content.
      if (
        !fs.existsSync(path.join(previous, "library/api")) &&
        !fs.existsSync(target)
      )
        fs.cpSync(path.join(legacyRoot, entry.name), target, {
          recursive: true,
        });
    }
  }
  const latest = path.join(previous, "library/api/latest");
  if (fs.existsSync(latest))
    fs.cpSync(latest, path.join(output, "library/api/latest"), {
      recursive: true,
    });
}
