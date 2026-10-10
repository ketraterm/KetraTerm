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
import { parseHTML } from "linkedom";
import { addApiNavigation } from "../src/api-navigation.mjs";

const dokkaPage = `<!DOCTYPE html>\r\n<html lang="en">\r\n<head>\r\n<title>TerminalSession</title>\r\n<link rel="stylesheet" href="styles/style.css">\r\n<script>var pathToRoot = "../../"; const exact = "<div>API source</div>";</script>\r\n</head>\r\n<body>\r\n<div class='root'>\r\n<header id="navigation-wrapper" class="navigation theme-dark"><a href="index.html">KetraTerm</a></header>\r\n<div id="container"><nav id="leftColumn">Types</nav><main id="main"><h1 id="session">TerminalSession</h1><p>Original API text &amp; contracts.</p><a href="../index.html#type">Type</a></main></div>\r\n</div>\r\n<script src="scripts/main.js"></script>\r\n</body>\r\n</html>`;

function fixture(t, files) {
  const output = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-api-nav-"));
  t.after(() => fs.rmSync(output, { recursive: true, force: true }));
  for (const [file, html] of Object.entries(files)) {
    const target = path.join(output, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, html);
  }
  return output;
}

const withoutNavigation = (html) =>
  html
    .replace(
      /<!-- ketraterm-api-navigation:start -->[\s\S]*?<!-- ketraterm-api-navigation:end -->/g,
      "",
    )
    .replace(
      /<!-- ketraterm-api-stylesheet:start -->[\s\S]*?<!-- ketraterm-api-stylesheet:end -->/g,
      "",
    );

test("API navigation connects development, archived, deep, and latest pages without changing Dokka content", (t) => {
  const cases = [
    ["dev/index.html", "Development"],
    [
      "dev/ketraterm-session/io.github.ketraterm.session/-terminal-session/start.html",
      "Development",
    ],
    ["v0.1.0-alpha01/index.html", "v0.1.0-alpha01"],
    [
      "v0.3.0/ketraterm-core/io.github.ketraterm.core/-terminal-core/index.html",
      "v0.3.0",
    ],
    ["latest/index.html", "v0.3.0"],
    [
      "latest/ketraterm-core/io.github.ketraterm.core/-terminal-core/index.html",
      "v0.3.0",
    ],
  ];
  const output = fixture(
    t,
    Object.fromEntries(
      cases.map(([file]) => [`library/api/${file}`, dokkaPage]),
    ),
  );
  addApiNavigation({
    output,
    versions: ["dev", "v0.1.0-alpha01", "v0.3.0"].map((id) => ({
      id,
      api: true,
    })),
    latest: "v0.3.0",
    appUrl: "https://app.example.com/KetraTerm/",
  });
  for (const [file, version] of cases) {
    const html = fs.readFileSync(
      path.join(output, "library/api", file),
      "utf8",
    );
    assert.equal(withoutNavigation(html), dokkaPage, `Dokka changed: ${file}`);
    const { document } = parseHTML(html);
    const navigation = document.querySelector(".ketraterm-api-navigation");
    assert.equal(navigation.parentElement.className, "root");
    assert.equal(navigation.nextElementSibling.id, "navigation-wrapper");
    const base = `https://library.example.com/api/${file}`;
    const brand = navigation.querySelector(".ketraterm-api-brand");
    assert.equal(brand.getAttribute("aria-label"), "KetraTerm Library home");
    assert.equal(
      new URL(brand.getAttribute("href"), base).href,
      "https://library.example.com/index.html",
    );
    assert.equal(
      new URL(brand.querySelector("img").getAttribute("src"), base).href,
      "https://library.example.com/assets/logo.svg",
    );
    const products = navigation.querySelector(
      'nav[aria-label="KetraTerm products"]',
    );
    assert.deepEqual(
      [...products.querySelectorAll("a")].map((link) => link.textContent),
      ["App", "Library"],
    );
    assert.equal(products.querySelectorAll("[aria-current]").length, 1);
    assert.equal(
      products.querySelector("[aria-current]").textContent,
      "Library",
    );
    assert.equal(
      products.querySelector("a").getAttribute("href"),
      "https://app.example.com/KetraTerm/",
    );
    assert.equal(
      new URL(
        products.querySelector("[aria-current]").getAttribute("href"),
        base,
      ).href,
      "https://library.example.com/index.html",
    );
    const reference = navigation.querySelector(".ketraterm-api-reference");
    assert.equal(
      reference.querySelector("span").textContent,
      `API · ${version}`,
    );
    assert.equal(
      new URL(reference.querySelector("a").getAttribute("href"), base).href,
      "https://library.example.com/versions.html",
    );
    const stylesheet = document.head.lastElementChild;
    const favicon = document.head.querySelector('link[rel="icon"]');
    assert.equal(favicon.getAttribute("type"), "image/svg+xml");
    assert.equal(
      new URL(favicon.getAttribute("href"), base).href,
      "https://library.example.com/assets/logo.svg",
    );
    assert.equal(stylesheet.tagName, "LINK");
    assert.equal(
      new URL(stylesheet.getAttribute("href"), base).href,
      "https://library.example.com/assets/api-navigation.css",
    );
    assert.equal(document.querySelectorAll("script").length, 2);
    assert.equal(
      document.querySelector('link[href$="assets/styles.css"]'),
      null,
    );
  }
});

test("reapplying API navigation is idempotent and refreshes the configured app origin", (t) => {
  const file = "library/api/v1.0.0/index.html";
  const output = fixture(t, { [file]: dokkaPage });
  const options = {
    output,
    versions: [{ id: "v1.0.0", api: true }],
    appUrl: "https://first.example.com",
  };
  addApiNavigation(options);
  const target = path.join(output, file);
  const first = fs.readFileSync(target, "utf8");
  addApiNavigation(options);
  assert.equal(fs.readFileSync(target, "utf8"), first);
  addApiNavigation({
    ...options,
    appUrl: "https://second.example.com/project/",
  });
  const refreshed = fs.readFileSync(target, "utf8");
  assert.equal(withoutNavigation(refreshed), dokkaPage);
  const { document } = parseHTML(refreshed);
  assert.equal(
    document.querySelectorAll(".ketraterm-api-navigation").length,
    1,
  );
  assert.equal(
    document.querySelectorAll('link[href$="api-navigation.css"]').length,
    1,
  );
  assert.equal(
    document.querySelector(".ketraterm-api-products a").getAttribute("href"),
    "https://second.example.com/project/",
  );
  assert.ok(!refreshed.includes("first.example.com"));
});

test("native Dokka home links follow product navigation in keyboard order without changing other markup", (t) => {
  const cases = [
    [
      '<a class="library-name--link" href="index.html" tabindex="1">KetraTerm</a>',
      '<a class="library-name--link" href="index.html">KetraTerm</a>',
    ],
    [
      "<a tabindex='2' href='index.html' class='extra library-name--link'>KetraTerm</a>",
      "<a href='index.html' class='extra library-name--link'>KetraTerm</a>",
    ],
    [
      '<a class="library-name--link" href="index.html" tabindex="0">KetraTerm</a>',
      '<a class="library-name--link" href="index.html" tabindex="0">KetraTerm</a>',
    ],
    [
      "<a class='library-name--link' tabindex='-1' href='index.html'>KetraTerm</a>",
      "<a class='library-name--link' tabindex='-1' href='index.html'>KetraTerm</a>",
    ],
  ];
  const untouched =
    '<a class="library-name--linkish" tabindex="3" href="other.html">Other</a>';
  const content =
    '<a class="library-name--link" tabindex="4" href="#session">API content</a>';
  const script =
    '<script>const literal = \'<a class="library-name--link" tabindex="5">\';</script>';
  const originals = cases.map(([anchor]) =>
    dokkaPage
      .replace('<a href="index.html">KetraTerm</a>', anchor + untouched)
      .replace("</main>", content + "</main>")
      .replace("</body>", script + "</body>"),
  );
  const files = originals.map((html, index) => [
    `library/api/dev/case-${index}.html`,
    html,
  ]);
  const output = fixture(t, Object.fromEntries(files));
  const options = { output, versions: [{ id: "dev", api: true }] };
  addApiNavigation(options);
  const decorated = files.map(([file], index) => {
    const html = fs.readFileSync(path.join(output, file), "utf8");
    assert.equal(
      withoutNavigation(html),
      originals[index].replace(cases[index][0], cases[index][1]),
    );
    return html;
  });
  addApiNavigation(options);
  for (const [index, [file]] of files.entries())
    assert.equal(
      fs.readFileSync(path.join(output, file), "utf8"),
      decorated[index],
    );
});

test("Dokka navigation fragments and API redirect pages remain untouched", (t) => {
  const fragments = {
    "library/api/dev/navigation.html":
      '<div class="root"><a href="index.html">Types</a></div>',
    "library/api/dev/index.html": dokkaPage,
    "library/api/dev/redirect.html":
      '<html><head><meta http-equiv="refresh" content="0;url=index.html"></head><body><a href="index.html">Continue</a></body></html>',
    "library/api/dev/bodyless.html":
      '<html><head><title>Fragment</title></head><div class="root"><header id="navigation-wrapper"></header></div></html>',
  };
  const output = fixture(t, fragments);
  addApiNavigation({ output, versions: [{ id: "dev", api: true }] });
  for (const [file, original] of Object.entries(fragments))
    if (!file.endsWith("index.html"))
      assert.equal(fs.readFileSync(path.join(output, file), "utf8"), original);
});
