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
import {parseHTML} from "linkedom";
import {pages} from "../src/pages.mjs";

test("the app owns the root and the library can be hosted independently", () => {
  const output = new Map(
    pages([], "dev", "https://app.example.com").map((page) => [
      page.file,
      parseHTML(page.html).document,
    ]),
  );
  const app = output.get("index.html"),
    library = output.get("library/index.html");
  assert.ok(
    app.querySelector(
      'nav[aria-label="App navigation"] a[href="download.html"]',
    ),
  );
  assert.ok(
    app.querySelector(
      'nav[aria-label="App navigation"] a[href="changelog.html"]',
    ),
  );
  assert.equal(app.querySelector("main pre"), null);
  assert.equal(app.querySelector('header a[href*="library"]'), null);
  assert.ok(
    library.querySelector(
      'nav[aria-label="Library navigation"] a[href="versions.html"]',
    ),
  );
  assert.ok(
    library.querySelector(
      'nav[aria-label="Library navigation"] a[href="guides/dev/CHANGELOG.html"]',
    ),
  );
  assert.equal(library.querySelector('main a[href="download.html"]'), null);
  assert.equal(library.querySelector('header a[href*="settings.html"]'), null);
  assert.ok(
    library.querySelector("main code").textContent.includes("{version}"),
  );
  assert.equal(
    library.querySelector(".product-switch").getAttribute("href"),
    "https://app.example.com/",
  );
  for (const document of output.values()) {
    assert.equal(
      document.querySelector('link[rel="icon"]').getAttribute("href"),
      "assets/logo.svg",
    );
    assert.equal(
      document.querySelector(".brand img").getAttribute("src"),
      "assets/logo.svg",
    );
    assert.equal(
      document.querySelector('link[rel="stylesheet"]').getAttribute("href"),
      "assets/styles.css",
    );
  }
  for (const element of library.querySelectorAll("[href],[src]")) {
    const link = element.getAttribute("href") || element.getAttribute("src");
    assert.ok(
      !link.startsWith("../") && !link.startsWith("/"),
      `Library depends on parent site: ${link}`,
    );
  }
});
