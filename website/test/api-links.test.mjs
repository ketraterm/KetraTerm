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
import { repairApiLinks } from "../src/api-links.mjs";

function fixture(t, files) {
  const output = fs.mkdtempSync(path.join(os.tmpdir(), "ketraterm-api-links-"));
  t.after(() => fs.rmSync(output, { recursive: true, force: true }));
  for (const [file, html] of Object.entries(files)) {
    const target = path.join(output, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, html);
  }
  return output;
}

const classPage = (members, content = "", kind = "data class") =>
  `<!doctype html>\r\n<html><head><script>const api = "untouched";</script></head><body><div class="cover"><div class="symbol">${kind} Candidate</div></div><div class="table"><div class="table-row"><div class="symbol">${members.map(([label, href]) => `<a href="${href}">${label}</a>`).join("")}</div></div></div>${content}<script src="scripts/main.js"></script></body></html>`;

test("malformed Dokka links resolve distinct labels through actual member entries without rewriting surrounding HTML", (t) => {
  const root = "library/api/dev/module/pkg/-candidate";
  const original = classPage(
    [
      ["displayText", "presentation.html"],
      ["matchedRanges", "highlights.html"],
    ],
    `<p><a data-test="first" href='--root--.html'><code>displayText</code></a> with <a href="--root--.html"><span>matched</span><wbr><span>Ranges</span></a>.</p><p><a href="--root--.html"><code>capacity</code></a> and <a href="copy.html">copy()</a></p>`,
  );
  const output = fixture(t, {
    [`${root}/index.html`]: original,
    [`${root}/presentation.html`]: "<h1>displayText</h1>",
    [`${root}/highlights.html`]: "<h1>matchedRanges</h1>",
  });
  assert.deepEqual(
    repairApiLinks({ output, versions: [{ id: "dev", api: true }] }),
    { repaired: 2, unlinked: 2 },
  );
  const corrected = fs.readFileSync(
    path.join(output, root, "index.html"),
    "utf8",
  );
  assert.equal(
    corrected,
    original
      .replace(
        "href='--root--.html'><code>displayText",
        "href='presentation.html'><code>displayText",
      )
      .replace(
        'href="--root--.html"><span>matched',
        'href="highlights.html"><span>matched',
      )
      .replace(
        '<a href="--root--.html"><code>capacity</code></a>',
        "<code>capacity</code>",
      )
      .replace('<a href="copy.html">copy()</a>', "copy()"),
  );
  assert.deepEqual(
    repairApiLinks({ output, versions: [{ id: "dev", api: true }] }),
    { repaired: 0, unlinked: 0 },
  );
  assert.equal(
    fs.readFileSync(path.join(output, root, "index.html"), "utf8"),
    corrected,
  );
});

test("development, archived, and latest deep links remain within their independently hosted API version", (t) => {
  const ids = ["dev", "v0.1.0-alpha01", "v0.3.0", "latest"];
  const files = {};
  for (const id of ids) {
    files[`library/api/${id}/index.html`] = "<h1>All modules</h1>";
    const root = `library/api/${id}/module/pkg/-candidate`;
    files[`${root}/index.html`] = classPage([
      ["displayText", "display-text.html"],
    ]);
    files[`${root}/display-text.html`] = "<h1>displayText</h1>";
    files[`${root}/-companion/resolve.html`] =
      '<p><a href="../--root--.html"><code>displayText</code></a></p>';
  }
  const output = fixture(t, files);
  const versions = ids
    .filter((id) => id !== "latest")
    .map((id) => ({ id, api: true }));
  assert.deepEqual(repairApiLinks({ output, versions }), {
    repaired: 4,
    unlinked: 0,
  });
  for (const id of ids) {
    const file = `library/api/${id}/module/pkg/-candidate/-companion/resolve.html`;
    const corrected = fs.readFileSync(path.join(output, file), "utf8");
    assert.equal(
      corrected,
      '<p><a href="../display-text.html"><code>displayText</code></a></p>',
    );
    const { document } = parseHTML(corrected);
    assert.equal(
      new URL(
        document.querySelector("a").getAttribute("href"),
        `https://library.example.com/api/${id}/module/pkg/-candidate/-companion/resolve.html`,
      ).href,
      `https://library.example.com/api/${id}/module/pkg/-candidate/display-text.html`,
    );
  }
});

test("valid copy pages, external and fragment links, and unrecognized missing APIs remain untouched", (t) => {
  const root = "library/api/dev/module/pkg/-candidate";
  const content = `<p><a href="copy.html">copy</a> <a href="https://example.com/--root--.html">remote</a> <a href="#copy.html">fragment</a> <a href="--root--.html#anchor">anchored</a> <a href="missing-api.html">unknown</a> <a href="../-absent/--root--.html">missing class</a> <a href="../../../v0.3.0/--root--.html">other version</a></p><script>const example = '<a href="--root--.html">capacity</a>';</script><!-- <a href="--root--.html">capacity</a> -->`;
  const original = classPage([["copy", "copy.html"]], content);
  const regular = classPage([], '<p><a href="copy.html">copy</a></p>', "class");
  const output = fixture(t, {
    [`${root}/index.html`]: original,
    [`${root}/copy.html`]: "<h1>copy</h1>",
    "library/api/dev/module/pkg/-regular/index.html": regular,
  });
  assert.deepEqual(
    repairApiLinks({ output, versions: [{ id: "dev", api: true }] }),
    { repaired: 0, unlinked: 0 },
  );
  assert.equal(
    fs.readFileSync(path.join(output, root, "index.html"), "utf8"),
    original,
  );
  assert.equal(
    fs.readFileSync(
      path.join(output, "library/api/dev/module/pkg/-regular/index.html"),
      "utf8",
    ),
    regular,
  );
});

test("ambiguous documented names and unpublished constructor inputs keep their exact child markup without links", (t) => {
  const root = "library/api/dev/module/pkg/-candidate";
  const original = classPage(
    [
      ["value", "value-a.html"],
      ["value", "value-b.html"],
    ],
    '<p><a href="--root--.html"><code>value</code></a>; <a href="--root--.html"><span>replay</span><wbr><span>Filter</span></a></p>',
  );
  const output = fixture(t, {
    [`${root}/index.html`]: original,
    [`${root}/value-a.html`]: "<h1>value overload A</h1>",
    [`${root}/value-b.html`]: "<h1>value overload B</h1>",
  });
  assert.deepEqual(
    repairApiLinks({ output, versions: [{ id: "dev", api: true }] }),
    { repaired: 0, unlinked: 2 },
  );
  assert.equal(
    fs.readFileSync(path.join(output, root, "index.html"), "utf8"),
    original
      .replace(
        '<a href="--root--.html"><code>value</code></a>',
        "<code>value</code>",
      )
      .replace(
        '<a href="--root--.html"><span>replay</span><wbr><span>Filter</span></a>',
        "<span>replay</span><wbr><span>Filter</span>",
      ),
  );
});

test("explicitly indexed missing members remain broken so archive validation can detect incomplete output", (t) => {
  const root = "library/api/dev/module/pkg/-candidate";
  const original = classPage(
    [
      ["copy", "copy.html"],
      ["displayText", "display-text.html"],
      ["value", "value-a.html"],
      ["value", "value-b.html"],
      ["matchedRanges", "highlights.html"],
    ],
    '<p><a href="copy.html">copy</a>; <a href="--root--.html"><code>displayText</code></a>; <a href="--root--.html">value</a>; <a href="--root--.html">matchedRanges</a>; <a href="--root--.html">capacity</a></p>',
  );
  const output = fixture(t, {
    [`${root}/index.html`]: original,
    [`${root}/value-a.html`]: "<h1>value overload A</h1>",
    [`${root}/highlights.html`]: "<h1>matchedRanges</h1>",
  });
  assert.deepEqual(
    repairApiLinks({ output, versions: [{ id: "dev", api: true }] }),
    { repaired: 1, unlinked: 1 },
  );
  assert.equal(
    fs.readFileSync(path.join(output, root, "index.html"), "utf8"),
    original
      .replace(
        '<a href="--root--.html">matchedRanges</a>',
        '<a href="highlights.html">matchedRanges</a>',
      )
      .replace('<a href="--root--.html">capacity</a>', "capacity"),
  );
});

const functionGroup = (id, name, fragments) =>
  `<a data-name="${id}" anchor-label="${name}" id="${id}"></a><div class="table-row"><div class="title">${fragments.map((fragment) => `<div class="symbol monospace">fun <a href="index.html#${fragment}"><span class="token function">${name}</span></a>()</div>`).join("")}</div></div>`;

test("broken overload hashes use actual grouped declarations in the target class, including cross-class links", (t) => {
  const tree = "library/api/dev/module/pkg";
  const first = "-1%2FFunctions%2F42";
  const second = "-2%2FFunctions%2F42";
  const canonical = "100%2FFunctions%2F42";
  const target = classPage(
    [],
    functionGroup(canonical, "add", [first, second]),
  );
  const crossLink = `../-overlay/index.html#${first}`;
  const source = classPage(
    [],
    `<a href="${crossLink}">add</a><a data-name="999%2FFunctions%2F42" anchor-label="add" id="999%2FFunctions%2F42"></a><div class="table-row"><div class="symbol"><a href="${crossLink}"><span class="token function">add</span></a></div></div><script>const example = '<a href="index.html#${first}">add</a>';</script>`,
  );
  const output = fixture(t, {
    [`${tree}/-overlay/index.html`]: target,
    [`${tree}/-terminal/index.html`]: source,
  });
  const versions = [{ id: "dev", api: true }];
  assert.deepEqual(repairApiLinks({ output, versions }), {
    repaired: 4,
    unlinked: 0,
  });
  assert.equal(
    fs.readFileSync(path.join(output, tree, "-overlay/index.html"), "utf8"),
    target
      .replace(`href="index.html#${first}"`, `href="index.html#${canonical}"`)
      .replace(`href="index.html#${second}"`, `href="index.html#${canonical}"`),
  );
  const correctedSource = fs.readFileSync(
    path.join(output, tree, "-terminal/index.html"),
    "utf8",
  );
  assert.equal(
    correctedSource,
    source.replaceAll(
      `href="${crossLink}"`,
      `href="../-overlay/index.html#${canonical}"`,
    ),
  );
  const { document } = parseHTML(correctedSource);
  assert.equal(
    new URL(
      document.body.querySelector("a").getAttribute("href"),
      "https://library.example.com/api/dev/module/pkg/-terminal/index.html",
    ).href,
    `https://library.example.com/api/dev/module/pkg/-overlay/index.html#${canonical}`,
  );
  assert.deepEqual(repairApiLinks({ output, versions }), {
    repaired: 0,
    unlinked: 0,
  });
});

const packagePage = (types) =>
  `<!doctype html><html><body><div class="table">${types.map(([name, href]) => `<div class="table-row"><span class="inline-flex"><a href="${href}">${name}</a></span></div>`).join("")}</div></body></html>`;

test("prose links to omitted classes and members use authoritative public indexes and retain child markup", (t) => {
  const root = "library/api/v0.1.0/module/pkg.model";
  const omittedClass =
    '<a href="-void-line/index.html"><code>pkg.model.VoidLine</code></a>';
  const omittedConstant =
    '<a href="-terminal-constants/-w-i-d-e_-c-h-a-r_-s-p-a-c-e-r.html">pkg.model.TerminalConstants.WIDE_CHAR_SPACER</a>';
  const omittedMember =
    '<a href="-terminal-session/render-worker.html"><span>render</span><wbr><span>Worker</span></a>';
  const original = classPage(
    [],
    `<p class="paragraph">${omittedClass}; ${omittedConstant}; ${omittedMember}.</p><div class="symbol"><p class="paragraph">${omittedMember}</p></div><nav><p class="paragraph">${omittedClass}</p></nav><div class="table"><p class="paragraph">${omittedConstant}</p></div><script>const sample = '${omittedClass}';</script>`,
  );
  const output = fixture(t, {
    [`${root}/index.html`]: packagePage([
      ["TerminalSession", "-terminal-session/index.html"],
    ]),
    [`${root}/-terminal-session/index.html`]: classPage(
      [["onDirty", "on-dirty.html"]],
      "",
      "class",
    ),
    [`${root}/-terminal-session/on-dirty.html`]: "<p>Callback</p>",
    [`${root}/description.html`]: original,
  });
  const versions = [{ id: "v0.1.0", api: true }];
  assert.deepEqual(repairApiLinks({ output, versions }), {
    repaired: 0,
    unlinked: 3,
  });
  assert.equal(
    fs.readFileSync(path.join(output, root, "description.html"), "utf8"),
    original
      .replace(omittedClass, "<code>pkg.model.VoidLine</code>")
      .replace(omittedConstant, "pkg.model.TerminalConstants.WIDE_CHAR_SPACER")
      .replace(omittedMember, "<span>render</span><wbr><span>Worker</span>"),
  );
  assert.deepEqual(repairApiLinks({ output, versions }), {
    repaired: 0,
    unlinked: 0,
  });
});

test("prose does not hide declared missing targets, mismatched identifiers, or incomplete declaration indexes", (t) => {
  const root = "library/api/dev/module/pkg";
  const original = classPage(
    [],
    `<p class="paragraph"><a href="-missing-type/index.html">pkg.MissingType</a>; <a href="-candidate/render-worker.html">renderWorker</a>; <a href="-candidate/renamed.html">renamed</a>; <a href="-orphan/render-worker.html">renderWorker</a>; <a href="../absent.pkg/-void-line/index.html">absent.pkg.VoidLine</a>; <a href="-void-line/index.html">AnotherType</a>; <a href="missing-api.html">unknown</a>; <a href="-candidate/render-worker.html#unknown">renderWorker</a></p>`,
  );
  const output = fixture(t, {
    [`${root}/index.html`]: packagePage([
      ["MissingType", "-missing-type/index.html"],
      ["Candidate", "-candidate/index.html"],
    ]),
    [`${root}/-candidate/index.html`]: classPage(
      [
        ["renderWorker", "render-worker.html"],
        ["renamed", "other-name.html"],
      ],
      "",
      "class",
    ),
    [`${root}/-orphan/index.html`]: classPage([], "", "class"),
    [`${root}/description.html`]: original,
  });
  assert.deepEqual(
    repairApiLinks({ output, versions: [{ id: "dev", api: true }] }),
    { repaired: 0, unlinked: 0 },
  );
  assert.equal(
    fs.readFileSync(path.join(output, root, "description.html"), "utf8"),
    original,
  );
});

test("omitted prose members resolve from deep source pages without escaping an API version", (t) => {
  const root = "library/api/latest/module/pkg";
  const href = "../-session/render-worker.html";
  const original = `<p class="paragraph">The <a href="${href}">renderWorker</a> thread.</p>`;
  const output = fixture(t, {
    "library/api/latest/index.html": "<h1>API</h1>",
    [`${root}/index.html`]: packagePage([["Session", "-session/index.html"]]),
    [`${root}/-session/index.html`]: classPage([], "", "class"),
    [`${root}/-consumer/usage.html`]: original,
  });
  assert.deepEqual(repairApiLinks({ output, versions: [] }), {
    repaired: 0,
    unlinked: 1,
  });
  assert.equal(
    fs.readFileSync(path.join(output, root, "-consumer/usage.html"), "utf8"),
    '<p class="paragraph">The renderWorker thread.</p>',
  );
});

test("valid, ambiguous, unknown, external and cross-version function fragments remain unchanged", (t) => {
  const tree = "library/api/dev/module/pkg";
  const missing = "-1%2FFunctions%2F42";
  const valid = "100%2FFunctions%2F42";
  const unknown = "900%2FFunctions%2F42";
  const ambiguous = "300%2FFunctions%2F42";
  const content =
    functionGroup(valid, "add", [valid]) +
    functionGroup("200%2FFunctions%2F42", "read", [ambiguous]) +
    functionGroup("400%2FFunctions%2F42", "read", [ambiguous]) +
    `<a href="index.html#${unknown}">unknown</a><a href="index.html#intro">intro</a><a href="https://example.com/index.html#${missing}">remote</a><a href="../-absent/index.html#${missing}">missing page</a><a href="../../../../v0.3.0/module/pkg/-candidate/index.html#${missing}">other version</a>`;
  const original = classPage([], content);
  const output = fixture(t, { [`${tree}/-candidate/index.html`]: original });
  assert.deepEqual(
    repairApiLinks({ output, versions: [{ id: "dev", api: true }] }),
    { repaired: 0, unlinked: 0 },
  );
  assert.equal(
    fs.readFileSync(path.join(output, tree, "-candidate/index.html"), "utf8"),
    original,
  );
});
