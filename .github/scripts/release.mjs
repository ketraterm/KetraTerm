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

import {appendFileSync, readFileSync, writeFileSync} from "node:fs";
import {execFileSync} from "node:child_process";
import {pathToFileURL} from "node:url";

export const changelogs = [
  ["CHANGELOG.md", "Libraries"],
  ["ketraterm-app/CHANGELOG.md", "Standalone application"],
  ["ketraterm-intellij-plugin/CHANGELOG.md", "IntelliJ plugin"],
];

// Match the prerelease stages and bounds supported by the native installers.
export function versionParts(version) {
  const match =
    /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)(0?[1-9]|1[0-9]))?$/.exec(
      version,
    );
  if (!match)
    throw new Error(
      `Invalid release version: ${version}. Use X.Y.Z or X.Y.Z-alpha/beta/rcN (N=1..19).`,
    );
  const stages = { alpha: 0, beta: 20, rc: 40 };
  return [
    ...match.slice(1, 4).map(BigInt),
    BigInt(match[4] ? stages[match[4]] + Number(match[5]) : 99),
  ];
}

export function compareVersions(left, right) {
  const a = versionParts(left);
  const b = versionParts(right);
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
  }
  return 0;
}

export function shouldRelease(version, releases) {
  versionParts(version);
  const published = releases.filter(
    (release) => !release.draft && /^v\d/.test(release.tag_name),
  );
  for (const release of published) {
    const previous = release.tag_name.slice(1);
    const order = compareVersions(version, previous);
    if (order < 0)
      throw new Error(
        `VERSION ${version} is older than published ${previous}.`,
      );
    if (order === 0 && version !== previous)
      throw new Error(`VERSION ${version} aliases published ${previous}.`);
  }
  return !published.some((release) => release.tag_name === `v${version}`);
}

export function finalizeChangelog(source, version, date) {
  const text = source.replace(/\r\n/g, "\n");
  const headings = [...text.matchAll(/^## \[([^\]]+)\].*$/gm)];
  const existing = headings.filter((heading) => heading[1] === version);
  const pending = headings.filter((heading) => heading[1] === "Unreleased");
  if (existing.length > 1 || pending.length > 1)
    throw new Error("Duplicate changelog sections.");
  if (existing.length) {
    if (
      pending.length &&
      text
        .slice(pending[0].index + pending[0][0].length, headings[1]?.index)
        .trim()
    ) {
      throw new Error(
        `Changelog already contains ${version} and has Unreleased changes.`,
      );
    }
    if (existing[0] !== headings[pending.length])
      throw new Error(`${version} must be the latest changelog release.`);
    // A manually finalized version retains its original date, including on retries.
    if (
      !new RegExp(
        `^## \\[${version.replaceAll(".", "\\.")}\\] - \\d{4}-\\d{2}-\\d{2}$`,
      ).test(existing[0][0])
    ) {
      throw new Error(`Missing release date for ${version}.`);
    }
    return pending.length
      ? text.slice(0, pending[0].index) + text.slice(existing[0].index)
      : text;
  }
  if (pending.length !== 1 || pending[0] !== headings[0])
    throw new Error("Expected a leading Unreleased changelog section.");
  return (
    text.slice(0, pending[0].index) +
    pending[0][0].replace("[Unreleased]", `[${version}] - ${date}`) +
    text.slice(pending[0].index + pending[0][0].length)
  );
}

export function releaseNotes(entries, version, repository) {
  return (
    entries
      .map(([file, title, text]) => {
        const headings = [...text.matchAll(/^## \[([^\]]+)\].*$/gm)];
        const index = headings.findIndex((heading) => heading[1] === version);
        if (index < 0) throw new Error(`Missing ${version} in ${file}.`);
        let body = text
          .slice(
            headings[index].index + headings[index][0].length,
            headings[index + 1]?.index,
          )
          .trim();
        const base = `https://github.com/${repository}/blob/v${version}/${file}`;
        body = body.replace(/\]\(([^\s)]+)\)/g, (match, target) => {
          if (/^[a-z][a-z\d+.-]*:/i.test(target) || target.startsWith("//"))
            return match;
          return `](${new URL(target, base).href})`;
        });
        return `## ${title}\n\n${body || "No changes."}`;
      })
      .join("\n\n") + "\n"
  );
}

function git(...args) {
  return execFileSync("git", args, { encoding: "utf8" }).trim();
}

function output(name, value) {
  appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`);
}

function main() {
  const version = readFileSync("VERSION", "utf8").trim();
  versionParts(version);
  const tag = `v${version}`;
  if (process.argv[2] === "prepare") {
    const releases = JSON.parse(readFileSync(process.argv[3], "utf8")).flat();
    const needed = shouldRelease(version, releases);
    output("needed", needed);
    if (!needed) {
      console.log(`${tag} is already published; nothing to release.`);
      return;
    }
    const existingTag = git("tag", "--list", tag) !== "";
    if (existingTag) {
      git("merge-base", "--is-ancestor", tag, "HEAD");
      git("checkout", "--detach", tag);
      if (readFileSync("VERSION", "utf8").trim() !== version)
        throw new Error(`Existing ${tag} has a different VERSION.`);
    }
    const date = new Date().toISOString().slice(0, 10);
    const entries = changelogs.map(([file, title]) => {
      const source = readFileSync(file, "utf8");
      if (existingTag && /^## \[Unreleased\]/m.test(source))
        throw new Error(`Existing ${tag} has unfinalized ${file}.`);
      return [file, title, finalizeChangelog(source, version, date)];
    });
    // Validate every file before changing any of them.
    for (const [file, , text] of entries) writeFileSync(file, text);
    output("tag", tag);
    output("existing_tag", existingTag);
    output("prerelease", version.includes("-"));
  } else if (process.argv[2] !== "notes") {
    throw new Error("Usage: release.mjs prepare releases.json | notes");
  }
  const entries = changelogs.map(([file, title]) => [
    file,
    title,
    readFileSync(file, "utf8"),
  ]);
  writeFileSync(
    "release-notes.md",
    releaseNotes(entries, version, process.env.GITHUB_REPOSITORY),
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  main();
