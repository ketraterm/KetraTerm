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

import path from "node:path";

export const repository = "https://github.com/ketraterm/KetraTerm";
export const defaultSiteUrl = "https://ketraterm.github.io/KetraTerm";
export const escape = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
export const relative = (from, to) =>
  path.posix.relative(path.posix.dirname(from), to) || "./";

export function layout({
  file,
  title,
  description,
  body,
  sidebar = "",
  toc = "",
  version = "",
  guideId = "dev",
  appUrl = defaultSiteUrl,
}) {
  const library = file.startsWith("library/");
  const root = library ? "library/" : "";
  const url = (target) => relative(file, root + target);
  const brand = library ? "KetraTerm Library" : "KetraTerm";
  const navigation = library
    ? [
        [`guides/${guideId}/README.html`, "Get started"],
        [`guides/${guideId}/docs/README.html`, "Guides"],
        [`guides/${guideId}/modules.html`, "Modules"],
        ["versions.html", "API & versions"],
        [`guides/${guideId}/CHANGELOG.html`, "Changelog"],
      ]
    : [
        ["download.html", "Download"],
        ["gallery.html", "Screenshots"],
        ["guide.html", "Guide"],
        ["settings.html", "Settings"],
        ["changelog.html", "Changelog"],
      ];
  const crossLink = library ? `${appUrl.replace(/\/$/, "")}/` : url("library/");
  const descriptionText =
    description ||
    (library
      ? "A modular Kotlin/JVM terminal emulator library for Swing and headless applications."
      : "KetraTerm is a desktop terminal for Windows, macOS, and Linux.");
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="description" content="${escape(descriptionText)}">
  <meta name="color-scheme" content="light dark">
  <title>${escape(title)} · ${brand}</title>
  <link rel="icon" href="${url("assets/logo.svg")}" type="image/svg+xml">
  <link rel="stylesheet" href="${url("assets/styles.css")}">
  <script type="importmap">${JSON.stringify({ imports: { mermaid: url("assets/mermaid/mermaid.esm.min.mjs") } }).replace(/</g, "\\u003c")}</script>
  <script type="module" src="${url("assets/site.js")}"></script>
</head>
<body data-product="${library ? "library" : "app"}" data-version="${escape(version)}">
  <a class="skip" href="#main">Skip to content</a>
  <header class="site-header">
    <a class="brand" href="${url("index.html")}" aria-label="${brand} home"><img class="brand-mark" src="${url("assets/logo.svg")}" alt="" width="34" height="34">${brand}</a>
    <nav aria-label="${library ? "Library" : "App"} navigation">${navigation.map(([target, label]) => `<a ${root + target === file ? 'aria-current="page"' : ""} href="${url(target)}">${label}</a>`).join("")}</nav>
    <button class="theme-toggle" type="button" aria-label="Switch color theme">◐</button>
  </header>
  ${sidebar ? `<div class="docs-shell"><aside class="sidebar" aria-label="Documentation navigation"><details class="nav-disclosure" open><summary class="mobile-nav-toggle">Browse ${library ? "library" : "app"} guides</summary><div>${sidebar}</div></details></aside><main id="main" class="article"><div class="article-top"><span class="eyebrow">${library ? "Library" : "Desktop app"}${version ? ` · ${escape(version)}` : ""}</span>${library ? `<a href="${url("versions.html")}">All versions ↗</a>` : ""}</div>${body}</main><aside class="toc" aria-label="On this page">${toc}</aside></div>` : `<main id="main">${body}</main>`}
  <footer>
    <a class="brand" href="${url("index.html")}"><img class="brand-mark" src="${url("assets/logo.svg")}" alt="" width="34" height="34">${brand}</a>
    <p>Open source · Apache 2.0</p>
    <div><a href="${repository}">GitHub ↗</a><a href="${repository}/issues">Issues ↗</a><a class="product-switch" href="${escape(crossLink)}">${library ? "Desktop app" : "For developers"} ↗</a></div>
  </footer>
</body>
</html>`;
}

export function cards(items, className = "") {
  return `<div class="cards ${className}">${items.map(([number, title, text, href]) => `<a class="card" href="${href}"><span class="card-number">${number}</span><h3>${title}<span aria-hidden="true">↗</span></h3><p>${text}</p></a>`).join("")}</div>`;
}
