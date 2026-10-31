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
import {mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync,} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import {execFileSync} from "node:child_process";
import {
    changelogs,
    compareVersions,
    finalizeChangelog,
    releaseNotes,
    shouldRelease,
    versionParts,
} from "./release.mjs";

const pending =
  "# Changelog\n\n## [Unreleased]\n\n### Fixes\n\n- Fixed [rendering](docs/render.md).\n\n## [0.3.0] - 2026-09-23\n\n- Old change.\n";

test("release ordering handles all native prerelease stages numerically", () => {
  const ordered = [
    "0.4.0-alpha01",
    "0.4.0-alpha2",
    "0.4.0-alpha10",
    "0.4.0-alpha19",
    "0.4.0-beta1",
    "0.4.0-beta19",
    "0.4.0-rc1",
    "0.4.0-rc19",
    "0.4.0",
    "0.4.1-alpha1",
    "0.10.0",
    "1.0.0",
  ];
  for (let i = 1; i < ordered.length; i++)
    assert.equal(compareVersions(ordered[i - 1], ordered[i]), -1);
  assert.equal(compareVersions("0.4.0-alpha01", "0.4.0-alpha1"), 0);
});

test("rejects malformed, unsupported, and out-of-range versions before publication", () => {
  for (const version of [
    "",
    "v0.4.0",
    "01.0.0",
    "1.0",
    "1.0.0-SNAPSHOT",
    "1.0.0-alpha",
    "1.0.0-alpha.1",
    "1.0.0-beta0",
    "1.0.0-rc20",
    "1.0.0+build",
    "1.0.0\ninjected=true",
  ]) {
    assert.throws(() => versionParts(version), /Invalid release version/);
  }
});

test("compares against every published release, including prereleases, while ignoring drafts", () => {
  const release = (tag_name, extra = {}) => ({
    tag_name,
    draft: false,
    ...extra,
  });
  const published = [
    release("v0.3.0"),
    release("v0.4.0-beta2", { prerelease: true }),
    release("v9.0.0", { draft: true }),
  ];
  assert.equal(shouldRelease("0.4.0", published), true);
  assert.equal(shouldRelease("0.4.0-beta2", published), false);
  assert.throws(() => shouldRelease("0.4.0-alpha19", published), /older/);
  assert.throws(() => shouldRelease("0.4.0-beta02", published), /aliases/);
  assert.equal(shouldRelease("0.1.0", []), true);
});

test("finalizes only Unreleased and preserves historical entries and release dates on retry", () => {
  const result = finalizeChangelog(
    pending.replaceAll("\n", "\r\n"),
    "0.4.0",
    "2026-10-31",
  );
  assert.equal(
    result,
    pending.replace("## [Unreleased]", "## [0.4.0] - 2026-10-31"),
  );
  assert.equal(finalizeChangelog(result, "0.4.0", "2026-11-01"), result);
  assert.equal(
    finalizeChangelog(
      result.replace("## [0.4.0]", "## [Unreleased]\n\n## [0.4.0]"),
      "0.4.0",
      "2026-11-01",
    ),
    result,
  );
});

test("rejects ambiguous changelogs instead of losing changes", () => {
  for (const text of [
    pending.replace("[Unreleased]", "[Other]"),
    pending.replace("### Fixes", "## [Unreleased]\n\n### Fixes"),
    pending.replace("[0.3.0]", "[0.4.0]"),
    pending.replace("[Unreleased]", "[0.4.0]"),
  ])
    assert.throws(() => finalizeChangelog(text, "0.4.0", "2026-10-31"));
});

test("release notes combine only this version and resolve links against each changelog", () => {
  const text = finalizeChangelog(pending, "0.4.0", "2026-10-31");
  const notes = releaseNotes(
    changelogs.map(([file, title]) => [file, title, text]),
    "0.4.0",
    "owner/repo",
  );
  for (const [, title] of changelogs) assert.ok(notes.includes(`## ${title}`));
  assert.ok(
    notes.includes("https://github.com/owner/repo/blob/v0.4.0/docs/render.md"),
  );
  assert.ok(
    notes.includes(
      "https://github.com/owner/repo/blob/v0.4.0/ketraterm-app/docs/render.md",
    ),
  );
  assert.ok(!notes.includes("Old change"));
});

test("prepare command supports first release and resumes the immutable tag after interruption", (t) => {
  const root = mkdtempSync(join(tmpdir(), "ketraterm-release-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const script = resolve(".github/scripts/release.mjs");
  const git = (...args) =>
    execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
  git("init", "-b", "master");
  git("config", "user.email", "release-test@example.invalid");
  git("config", "user.name", "Release test");
  writeFileSync(join(root, "VERSION"), "0.4.0\n");
  for (const [file] of changelogs) {
    if (file.includes("/"))
      mkdirSync(join(root, file.split("/")[0]), { recursive: true });
    writeFileSync(join(root, file), pending);
  }
  git("add", ".");
  git("commit", "-m", "Initial");
  writeFileSync(join(root, "releases.json"), "[]");
  const output = join(root, "outputs");
  const run = () =>
    execFileSync(process.execPath, [script, "prepare", "releases.json"], {
      cwd: root,
      env: {
        ...process.env,
        GITHUB_OUTPUT: output,
        GITHUB_REPOSITORY: "owner/repo",
      },
      encoding: "utf8",
      stdio: "pipe",
    });
  writeFileSync(join(root, changelogs[2][0]), "# Missing release section\n");
  assert.throws(run);
  assert.equal(readFileSync(join(root, "CHANGELOG.md"), "utf8"), pending);
  writeFileSync(join(root, changelogs[2][0]), pending);
  run();
  assert.match(readFileSync(output, "utf8"), /needed=true/);
  for (const [file] of changelogs)
    assert.match(
      readFileSync(join(root, file), "utf8"),
      /## \[0.4.0\] - \d{4}-\d{2}-\d{2}/,
    );
  git("add", ...changelogs.map(([file]) => file));
  git("commit", "-m", "Release v0.4.0");
  git("tag", "v0.4.0");
  const releaseCommit = git("rev-parse", "HEAD");
  writeFileSync(join(root, "VERSION"), "0.4.0\n");
  git("commit", "--allow-empty", "-m", "Later development");
  run();
  assert.equal(git("rev-parse", "HEAD"), releaseCommit);
  assert.match(readFileSync(output, "utf8"), /existing_tag=true/);
  writeFileSync(
    join(root, "releases.json"),
    JSON.stringify([{ tag_name: "v0.4.0", draft: false }]),
  );
  writeFileSync(output, "");
  run();
  assert.match(readFileSync(output, "utf8"), /needed=false/);
  assert.equal(git("rev-parse", "HEAD"), releaseCommit);
});
