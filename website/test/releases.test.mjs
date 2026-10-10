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
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import test from "node:test";
import { backfillApi } from "../scripts/backfill-api.mjs";
import { latestAppRelease } from "../src/release-sources.mjs";
import { renderAppDocumentation } from "../src/app-docs.mjs";
import { inventory } from "../src/archive.mjs";

test("stable app selection follows published GitHub metadata and fails closed", async (t) => {
  const release = { tag_name: "v2.0.0", prerelease: false, draft: false };
  t.mock.method(
    globalThis,
    "fetch",
    async () => new Response(JSON.stringify(release)),
  );
  assert.equal(await latestAppRelease(), "v2.0.0");
  for (const tag of ["v2.1.0-alpha01", "v2.1.0-rc.2", "v2.1.0-preview", "v2"]) {
    release.tag_name = tag;
    await assert.rejects(latestAppRelease(), /not a stable version/);
  }
  release.tag_name = "v2.0.0";
  release.prerelease = true;
  await assert.rejects(latestAppRelease(), /not a stable version/);
  release.prerelease = false;
  release.draft = true;
  await assert.rejects(latestAppRelease(), /not a stable version/);
  globalThis.fetch.mock.mockImplementation(
    async () => new Response("", { status: 503 }),
  );
  await assert.rejects(latestAppRelease(), /HTTP 503/);
});

test("missing stable app docs cannot silently produce partial or development help", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-app-docs-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const written = [];
  const render = (id) =>
    renderAppDocumentation({
      root,
      id,
      ref: "release",
      write: (...args) => written.push(args),
    });
  assert.throws(() => render("dev"), /stable release/);
  assert.throws(() => render("v1.0.0-rc.1"), /stable release/);
  assert.throws(() => render("v1.0.0"), /Missing app documentation/);
  assert.deepEqual(written, []);
});

test("backfill resumes after failure, retains existing docs, and records exact tag sources", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-backfill-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const git = (...args) =>
    execFileSync("git", args, {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  git("init");
  git("config", "user.name", "Website test");
  git("config", "user.email", "website-test@example.invalid");
  const commits = new Map();
  for (const version of ["1.0.0", "1.1.0-rc.1", "1.1.0"]) {
    fs.writeFileSync(path.join(root, "VERSION"), version);
    git("add", "VERSION");
    git("commit", "-m", version);
    git("tag", `v${version}`);
    commits.set(`v${version}`, git("rev-parse", "HEAD"));
  }
  const archive = path.join(root, "archive");
  const interruptedCopy = path.join(
    archive,
    "library/.pending-api/v1.1.0-rc.1",
  );
  fs.mkdirSync(interruptedCopy, { recursive: true });
  fs.writeFileSync(path.join(interruptedCopy, "index.html"), "Partial copy");
  assert.deepEqual(inventory(archive), []);
  const previous = path.join(root, "previous");
  fs.mkdirSync(path.join(previous, "docs/v0.9.0"), { recursive: true });
  fs.writeFileSync(
    path.join(previous, "docs/v0.9.0/index.html"),
    "Retained legacy",
  );
  fs.writeFileSync(path.join(previous, "CNAME"), "example.invalid");
  const builds = [];
  let fail = true;
  const build = (source) => {
    builds.push(source.id);
    assert.equal(source.ref, commits.get(source.id));
    assert.equal(
      fs.readFileSync(path.join(source.root, "VERSION"), "utf8"),
      source.id.slice(1),
    );
    const output = path.join(source.root, "build/dokka/html");
    fs.mkdirSync(output, { recursive: true });
    // A failed Gradle build can leave output, which must never be promoted.
    fs.writeFileSync(path.join(output, "index.html"), source.id);
    if (fail && source.id === "v1.0.0") throw new Error("Build failed");
  };
  assert.throws(
    () => backfillApi({ root, previous, archive, build }),
    /Build failed/,
  );
  assert.equal(fs.existsSync(path.join(archive, "library/api/v1.0.0")), false);
  fail = false;
  builds.length = 0;
  backfillApi({ root, previous, archive, build });
  assert.deepEqual(builds, ["v1.0.0"]);
  for (const [tag, commit] of commits) {
    assert.deepEqual(
      JSON.parse(
        fs.readFileSync(path.join(archive, "library/api", tag, "source.json")),
      ),
      { tag, commit },
    );
  }
  assert.equal(
    fs.readFileSync(
      path.join(archive, "library/api/v0.9.0/index.html"),
      "utf8",
    ),
    "Retained legacy",
  );
  assert.equal(
    fs.readFileSync(path.join(archive, "CNAME"), "utf8"),
    "example.invalid",
  );
  builds.length = 0;
  backfillApi({ root, previous, archive, build });
  assert.deepEqual(builds, []);
  assert.equal(inventory(archive).length, 4);
});
