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
import {parseHTML} from "linkedom";
import {documentationFiles, renderDocumentation} from "../src/docs.mjs";
import {renderAppDocumentation} from "../src/app-docs.mjs";

test("publishes module docs once, preserves heading anchors, and rewrites links inside tables", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-guides-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const sources = {
    "README.md":
      "# Test library\n\n| Guide | Source |\n|---|---|\n| [Core](ketraterm-core/README.md#usage) | [Code](ketraterm-core/src/Core.kt) |\n\n## Usage\n\n## Usage\n\n[Repeat](#usage-1)",
    "AGENTS.md": "# Agent instructions",
    "docs/README.md": "# Documentation",
    "docs/library/configuration.md": "# Library configuration",
    "ketraterm-swing/README.md": "# Swing entry point",
    "ketraterm-headless/README.md": "# Headless entry point",
    "ketraterm-core/README.md":
      "# Core\n\n## Usage\n\n[Home](../README.md)\n\n![Diagram](docs/image.png)",
    "ketraterm-core/Module.md":
      "# Core structure\n\n## Bounds & limits\n\n## More &amp; less",
    "ketraterm-core/AGENTS.md": "# Agent instructions",
    "ketraterm-core/docs/contract.md": "# Contract",
    "ketraterm-core/src/Core.kt": "// Source target",
    "ketraterm-core/docs/image.png": "Image fixture",
    "ketraterm-app/docs/user-guide.md":
      "# App help\n\n[Settings](profile-config-toml.md)",
    "ketraterm-app/docs/profile-config-toml.md":
      "# App settings\n\nUnique app setting.",
    "ketraterm-app/CHANGELOG.md": "# App changelog",
    "ketraterm-intellij-plugin/README.md": "# IDE plugin",
  };
  for (const [file, body] of Object.entries(sources)) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), body);
  }
  assert.equal(
    documentationFiles(root).some((file) => file.endsWith("AGENTS.md")),
    false,
  );
  const output = new Map();
  renderDocumentation({
    root,
    id: "v1.0.0",
    ref: "abc123",
    write: (file, body) => output.set(file, body),
  });
  const { document } = parseHTML(
    output.get("library/guides/v1.0.0/README.html"),
  );
  assert.equal(
    document.querySelector("td a").getAttribute("href"),
    "ketraterm-core/README.html#usage",
  );
  assert.ok(document.getElementById("usage-1"));
  assert.ok(
    document.querySelector(
      'a[href="https://github.com/ketraterm/KetraTerm/blob/abc123/ketraterm-core/src/Core.kt"]',
    ),
  );
  assert.match(
    output.get("library/guides/v1.0.0/ketraterm-core/README.html"),
    /raw\.githubusercontent\.com\/ketraterm\/KetraTerm\/abc123\/ketraterm-core\/docs\/image.png/,
  );
  assert.ok(output.has("library/guides/v1.0.0/ketraterm-core/Module.html"));
  const moduleDocument = parseHTML(
    output.get("library/guides/v1.0.0/ketraterm-core/Module.html"),
  ).document;
  assert.ok(moduleDocument.getElementById("bounds--limits"));
  assert.ok(moduleDocument.getElementById("more--less"));
  const moduleBreadcrumbs = moduleDocument.querySelector(
    'nav[aria-label="Breadcrumb"]',
  );
  assert.deepEqual(
    [...moduleBreadcrumbs.querySelectorAll("a")].map((link) => [
      link.textContent,
      link.getAttribute("href"),
    ]),
    [
      ["Library", "../../../index.html"],
      ["Modules", "../modules.html"],
      ["ketraterm-core", "README.html"],
    ],
  );
  assert.ok(moduleBreadcrumbs.textContent.includes("Core structure"));
  const moduleReadme = parseHTML(
    output.get("library/guides/v1.0.0/ketraterm-core/README.html"),
  ).document;
  assert.equal(
    moduleReadme.querySelectorAll('nav[aria-label="Breadcrumb"] a').length,
    2,
  );
  assert.ok(
    output.has("library/guides/v1.0.0/ketraterm-core/docs/contract.html"),
  );
  const contract = parseHTML(
    output.get("library/guides/v1.0.0/ketraterm-core/docs/contract.html"),
  ).document;
  assert.ok(
    contract.querySelector(
      'nav[aria-label="Breadcrumb"] a[href="../README.html"]',
    ),
  );
  const libraryIndex = JSON.parse(
    output.get("library/guides/v1.0.0/search.json"),
  );
  assert.equal(libraryIndex.length, 8);
  assert.deepEqual(
    libraryIndex
      .filter((entry) => entry.file.startsWith("ketraterm-core/"))
      .map(({ context }) => context),
    [
      "Module guide · ketraterm-core",
      "Module notes · ketraterm-core",
      "Module contract · ketraterm-core",
    ],
  );
  assert.equal(
    libraryIndex.find(
      (entry) => entry.file === "docs/library/configuration.html",
    ).context,
    "Embedding guides",
  );
  const guideIndex = parseHTML(
    output.get("library/guides/v1.0.0/docs/README.html"),
  ).document;
  assert.deepEqual(
    [...guideIndex.querySelectorAll(".guide-starts a")].map((link) => [
      link.querySelector("strong").textContent,
      link.getAttribute("href"),
    ]),
    [
      ["Embed a Swing terminal", "../ketraterm-swing/README.html"],
      ["Create a headless session", "../ketraterm-headless/README.html"],
      ["Configure host services", "library/configuration.html"],
    ],
  );
  assert.ok(
    [...output.keys()].every(
      (file) =>
        !file.includes("ketraterm-app") &&
        !file.includes("ketraterm-intellij-plugin"),
    ),
  );
  renderAppDocumentation({
    root,
    id: "v1.0.0",
    ref: "abc123",
    write: (file, body) => output.set(file, body),
  });
  const appIndex = JSON.parse(output.get("search.json"));
  assert.deepEqual(
    appIndex.map((entry) => entry.file),
    ["guide.html", "settings.html", "changelog.html"],
  );
  assert.ok(appIndex.every((entry) => entry.context === "App help"));
  const appGuide = parseHTML(output.get("guide.html")).document;
  assert.ok(appGuide.querySelector('main a[href="settings.html"]'));
  assert.equal(
    appGuide.querySelector(
      'nav[aria-label="App navigation"] a[href*="library"]',
    ),
    null,
  );
  assert.ok(
    appGuide.querySelector('nav[aria-label="Breadcrumb"] a[href="index.html"]'),
  );
  assert.equal(
    JSON.parse(output.get("library/guides/v1.0.0/search.json")).some((entry) =>
      entry.text.includes("Unique app setting"),
    ),
    false,
  );
  for (const source of [
    "ketraterm-swing/README.md",
    "ketraterm-headless/README.md",
    "docs/library/configuration.md",
  ])
    fs.unlinkSync(path.join(root, source));
  renderDocumentation({
    root,
    id: "v0.9.0",
    ref: "older123",
    write: (file, body) => output.set(file, body),
  });
  const olderGuideIndex = parseHTML(
    output.get("library/guides/v0.9.0/docs/README.html"),
  ).document;
  assert.equal(olderGuideIndex.querySelector(".guide-starts"), null);
  assert.ok(
    [...olderGuideIndex.querySelectorAll("a")].every(
      (link) => !link.getAttribute("href").includes("v1.0.0"),
    ),
  );
});
