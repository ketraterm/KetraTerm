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
import test from "node:test";
import {checkLinks} from "../scripts/check-links.mjs";

function fixture(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-link-check-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const directory of ["library/guides", "library/api", "guides"])
    fs.mkdirSync(path.join(root, directory), { recursive: true });
  for (const [file, html] of Object.entries(files)) {
    const target = path.join(root, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, html);
  }
  return root;
}

test("API fragment validation accepts native IDs and Dokka's raw named anchors", (t) => {
  const root = fixture(t, {
    "library/api/dev/index.html":
      '<a href="member.html#123%2FProperties%2F456">Dokka</a><a href="member.html#native%2Fid">Native</a>',
    "library/api/dev/member.html":
      '<a data-name="123%2FProperties%2F456" id="123%2FProperties%2F456"></a><h2 id="native/id">Member</h2>',
  });
  const result = checkLinks(root);
  assert.equal(result.apiPages, 2);
  assert.deepEqual(result.errors, []);
});

test("missing fragments and encoded IDs without a matching Dokka anchor still fail", (t) => {
  const root = fixture(t, {
    "library/api/dev/index.html":
      '<a href="member.html#123%2FProperties">Encoded ID only</a><a href="member.html#missing">Missing</a><a href="member.html#ignored">Inert markup</a>',
    "library/api/dev/member.html":
      '<h2 id="123%2FProperties">Member</h2><div data-name="missing"></div><!-- <a data-name="ignored"></a> --><script>const example = \'<a data-name="ignored"></a>\';</script>',
  });
  const result = checkLinks(root);
  assert.equal(result.errors.length, 3);
  assert.ok(result.errors.every((error) => error.includes("missing anchor")));
  assert.ok(
    result.errors.some((error) =>
      error.endsWith("member.html#123%2FProperties"),
    ),
  );
  assert.ok(
    result.errors.some((error) => error.endsWith("member.html#missing")),
  );
  assert.ok(
    result.errors.some((error) => error.endsWith("member.html#ignored")),
  );
});

test("API pages validate missing files, library boundaries, and relative assets", (t) => {
  const root = fixture(t, {
    "index.html": "<h1>App</h1>",
    "library/index.html": "<h1>Library</h1>",
    "library/assets/logo.svg": "<svg></svg>",
    "library/api/dev/index.html":
      '<a href="../../">Library</a><img src="../../assets/logo.svg"><a href="missing.html">Missing page</a><a href="../../../index.html">App through parent</a>',
  });
  const result = checkLinks(root);
  assert.equal(result.errors.length, 2);
  assert.ok(
    result.errors.some((error) => error.endsWith("missing missing.html")),
  );
  assert.ok(
    result.errors.some((error) =>
      error.includes("library depends on parent site"),
    ),
  );
});

test("malformed API URLs produce actionable failures", (t) => {
  const root = fixture(t, {
    "library/api/dev/index.html":
      '<a href="broken-%E0%A4%A.html">Malformed</a>',
  });
  assert.deepEqual(checkLinks(root).errors, [
    `${path.join("library", "api", "dev", "index.html")}: invalid URL broken-%E0%A4%A.html`,
  ]);
});
