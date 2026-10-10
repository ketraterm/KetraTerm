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
import { execFileSync } from "node:child_process";
import { releaseId, sortVersions } from "./archive.mjs";
import { latestReleaseApi, stableVersion } from "../assets/releases.js";

export async function latestAppRelease() {
  const response = await fetch(latestReleaseApi, {
    headers: {
      Accept: "application/vnd.github+json",
      ...(process.env.GITHUB_TOKEN
        ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` }
        : {}),
    },
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok)
    throw new Error(
      `Cannot select stable app release: GitHub HTTP ${response.status}`,
    );
  const release = await response.json();
  if (release.draft || release.prerelease || !stableVersion(release.tag_name))
    throw new Error("GitHub's latest app release is not a stable version");
  return release.tag_name;
}

export function releaseTags(root) {
  const tags = execFileSync("git", ["tag", "--list", "v*"], {
    cwd: root,
    encoding: "utf8",
  })
    .trim()
    .split(/\r?\n/)
    .filter((tag) => /^v\d+\.\d+\.\d+(?:-[\w.-]+)?$/.test(tag));
  return sortVersions(tags.map((id) => ({ id }))).map(({ id }) => id);
}

// Exports leave the working checkout untouched. A commit-addressed directory
// also lets interrupted Dokka builds reuse their Gradle outputs safely.
export function exportRelease(root, id) {
  if (releaseId(id.slice(1)) !== id)
    throw new Error(`Invalid release tag: ${id}`);
  const ref = execFileSync("git", ["rev-parse", `${id}^{commit}`], {
    cwd: root,
    encoding: "utf8",
  }).trim();
  const directory = path.join(root, "build/website-sources", ref);
  const marker = path.join(directory, ".website-source");
  if (!fs.existsSync(marker)) {
    fs.mkdirSync(directory, { recursive: true });
    const archive = `${directory}.tar`;
    execFileSync(
      "git",
      ["archive", "--format=tar", `--output=${archive}`, ref],
      { cwd: root },
    );
    execFileSync("tar", ["-xf", archive, "-C", directory]);
    fs.rmSync(archive);
    fs.writeFileSync(marker, ref);
  }
  const version = fs
    .readFileSync(path.join(directory, "VERSION"), "utf8")
    .trim();
  if (releaseId(version) !== id)
    throw new Error(`${id} does not match its VERSION file`);
  return { root: directory, id, ref };
}
