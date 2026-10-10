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
import test from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { buildSite } from "../scripts/build.mjs";
import {
  copyArchives,
  inventory,
  releaseId,
  sortVersions,
} from "../src/archive.mjs";

test("the version picker orders a stable release before its prereleases", () => {
  const ids = ["v1.9.0", "v1.10.0-rc.1", "v1.10.0", "v1.10.0-rc.2", "dev"];
  assert.deepEqual(
    sortVersions(ids.map((id) => ({ id }))).map((v) => v.id),
    ["dev", "v1.10.0", "v1.10.0-rc.2", "v1.10.0-rc.1", "v1.9.0"],
  );
});

test("release archives remain immutable across development, prereleases, and older releases", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-site-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const write = (file, text) => {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), text);
  };
  write("README.md", "# Library");
  write("docs/README.md", "# Guides");
  write("CHANGELOG.md", "# Changelog");
  write("website/assets/styles.css", "");
  write(
    "ketraterm-app/src/main/resources/io/github/ketraterm/app/icons/logo.svg",
    '<svg xmlns="http://www.w3.org/2000/svg"/>',
  );
  write("website/screenshots/12.png", "fixture");
  const previous = path.join(root, "archive");
  const stableRoot = path.join(root, "stable-app");
  const appSources = [
    "docs/user-guide.md",
    "docs/profile-config-toml.md",
    "CHANGELOG.md",
  ];
  for (const source of appSources) {
    write(
      `stable-app/ketraterm-app/${source}`,
      `# Released app\n\nStable 1.10 settings`,
    );
    write(`ketraterm-app/${source}`, `# Unreleased app\n\nUnreleased setting`);
  }
  let appRelease = { root: stableRoot, id: "v1.10.0", ref: "stable-commit" };
  const build = (release, body) => {
    write("VERSION", release || "2.0.0");
    write("build/dokka/html/index.html", body);
    const result = buildSite({
      root,
      previous: fs.existsSync(previous) ? previous : undefined,
      release,
      ref: "abc123",
      appRelease,
      requireApi: true,
    });
    fs.rmSync(previous, { recursive: true, force: true });
    fs.cpSync(result.output, previous, { recursive: true });
    return result;
  };
  const read = (file) => fs.readFileSync(path.join(previous, file), "utf8");
  build("1.10.0", "stable");
  assert.match(read("settings.html"), /Stable 1.10 settings/);
  assert.doesNotMatch(read("settings.html"), /Unreleased setting/);
  const stableApp = read("settings.html");
  assert.equal(read("library/api/latest/index.html"), "stable");
  assert.equal(
    fs.existsSync(path.join(previous, "library/guides/dev")),
    false,
    "a tag must not manufacture development docs",
  );
  const originalGuide = read("library/guides/v1.10.0/README.html");
  write("README.md", "# Changed");
  build("1.10.0", "rewritten");
  assert.equal(read("library/api/v1.10.0/index.html"), "stable");
  assert.equal(read("library/guides/v1.10.0/README.html"), originalGuide);
  build(undefined, "development");
  const devGuide = read("library/guides/dev/README.html");
  build("2.0.0-rc.1", "prerelease");
  assert.equal(read("library/api/latest/index.html"), "stable");
  assert.equal(read("library/api/dev/index.html"), "development");
  assert.equal(read("library/guides/dev/README.html"), devGuide);
  build("1.9.0", "older");
  assert.equal(
    read("settings.html"),
    stableApp,
    "library publication cannot change app docs",
  );
  assert.equal(JSON.parse(read("app-version.json")).ref, "stable-commit");
  assert.equal(read("library/api/latest/index.html"), "stable");
  appRelease = { root: stableRoot, id: "v2.0.0", ref: "new-stable-commit" };
  write(
    "stable-app/ketraterm-app/docs/profile-config-toml.md",
    "# Settings\n\nStable 2.0 settings",
  );
  const result = build("2.0.0", "new stable");
  assert.match(read("settings.html"), /Stable 2.0 settings/);
  assert.equal(JSON.parse(read("app-version.json")).id, "v2.0.0");
  assert.equal(read("library/api/latest/index.html"), "new stable");
  assert.equal(result.versions.length, 5);
  assert.equal(read("library/api/v1.9.0/index.html"), "older");
  assert.match(
    read("docs/v1.9.0/index.html"),
    /library\/api\/v1.9.0\/index.html/,
  );
  assert.match(read("library.html"), /location.hash/);
});

test("legacy API archives migrate without losing deep pages or resources", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-legacy-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const previous = path.join(root, "previous"),
    output = path.join(root, "output");
  fs.mkdirSync(path.join(previous, "docs/v0.3.0/module"), { recursive: true });
  fs.writeFileSync(path.join(previous, "docs/v0.3.0/index.html"), "Legacy API");
  fs.writeFileSync(
    path.join(previous, "docs/v0.3.0/module/type.html"),
    "Type docs",
  );
  fs.writeFileSync(path.join(previous, "docs/v0.3.0/style.css"), "Styles");
  copyArchives(previous, output);
  assert.deepEqual(inventory(output), [
    { id: "v0.3.0", label: "0.3.0", stable: true, api: true, guides: false },
  ]);
  assert.equal(
    fs.readFileSync(
      path.join(output, "library/api/v0.3.0/module/type.html"),
      "utf8",
    ),
    "Type docs",
  );
  assert.equal(
    fs.readFileSync(path.join(output, "library/api/v0.3.0/style.css"), "utf8"),
    "Styles",
  );
});

test("invalid releases and inaccessible archives fail closed", (t) => {
  for (const version of ["../1.0.0", "1", "v1.0.0", "1.0.0/x", ""])
    assert.throws(() => releaseId(version));
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-archive-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  assert.throws(
    () => copyArchives(path.join(root, "missing"), path.join(root, "output")),
    /does not exist/,
  );
  fs.writeFileSync(path.join(root, "VERSION"), "1.0.0");
  assert.throws(
    () => buildSite({ root, release: "2.0.0", ref: "abc123" }),
    /does not match/,
  );
  assert.throws(
    () =>
      buildSite({
        root,
        ref: "abc123",
        previous: path.join(root, "build/website"),
      }),
    /outside build output/,
  );
});
