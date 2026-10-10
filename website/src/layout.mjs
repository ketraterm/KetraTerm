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
  breadcrumbs = [],
  guideId = "dev",
  appUrl = defaultSiteUrl,
}) {
  const library = file.startsWith("library/");
  const root = library ? "library/" : "";
  const url = (target) => relative(file, root + target);
  const brand = library ? "KetraTerm Library" : "KetraTerm";
  const navigation = library
    ? [
        [`guides/${guideId}/docs/README.html`, "Guides"],
        [`guides/${guideId}/modules.html`, "Modules"],
        ["versions.html", "API & versions"],
        [`guides/${guideId}/CHANGELOG.html`, "Changelog"],
        [`guides/${guideId}/README.html`, "Get started"],
      ]
    : [
        ["gallery.html", "Screenshots"],
        ["guide.html", "Guide"],
        ["settings.html", "Settings"],
        ["changelog.html", "Changelog"],
        ["download.html", "Download"],
      ];
  let current = file;
  const guideRoot = `${root}guides/${guideId}/`;
  if (
    library &&
    file.startsWith(guideRoot) &&
    !navigation.some(([target]) => root + target === file)
  )
    current =
      guideRoot +
      (file.startsWith(`${guideRoot}ketraterm-`)
        ? "modules.html"
        : "docs/README.html");
  const breadcrumb = breadcrumbs.length
    ? `<nav class="breadcrumb" aria-label="Breadcrumb"><ol>${breadcrumbs.map(({ label, href }, index) => `<li>${href ? `<a href="${escape(href)}">${escape(label)}</a>` : `<span${index === breadcrumbs.length - 1 ? ' aria-current="page"' : ""}>${escape(label)}</span>`}</li>`).join("")}</ol></nav>`
    : `<span>${library ? "Library" : "Desktop app"}</span>`;
  const crossLink = library
    ? `${appUrl.replace(/\/$/, "")}/`
    : url("library/index.html");
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
  <link rel="preload" href="${url("assets/fonts/manrope-variable.ttf")}" as="font" type="font/ttf" crossorigin>
  <link rel="stylesheet" href="${url("assets/styles.css")}">
  <script type="importmap">${JSON.stringify({ imports: { mermaid: url("assets/mermaid/mermaid.esm.min.mjs") } }).replace(/</g, "\\u003c")}</script>
  <script type="module" src="${url("assets/site.js")}"></script>
</head>
<body data-product="${library ? "library" : "app"}" data-version="${escape(version)}">
  <a class="skip" href="#main">Skip to content</a>
  <header class="site-header">
    <div class="site-identity">
      <a class="brand" href="${url("index.html")}" aria-label="${brand} home"><img class="brand-mark" src="${url("assets/logo.svg")}" alt="" width="34" height="34">KetraTerm</a>
      <nav class="product-navigation" aria-label="KetraTerm products"><a href="${escape(library ? crossLink : url("index.html"))}"${library ? "" : ' aria-current="true"'}>App</a><a href="${escape(library ? url("index.html") : crossLink)}"${library ? ' aria-current="true"' : ""}>Library</a></nav>
    </div>
    <details class="site-navigation" open><summary>Menu</summary><nav aria-label="${library ? "Library" : "App"} navigation">${navigation.map(([target, label], index) => `<a ${root + target === current ? `aria-current="${file === current ? "page" : "true"}"` : ""} ${index === navigation.length - 1 ? 'class="nav-primary"' : ""} href="${url(target)}">${label}</a>`).join("")}</nav></details>
    <button class="theme-toggle" type="button" aria-label="Switch to light theme">Light</button>
  </header>
  ${sidebar ? `<div class="docs-shell"><aside class="sidebar" aria-label="Documentation navigation"><details class="nav-disclosure" open><summary class="mobile-nav-toggle">Browse ${library ? "library" : "app"} guides</summary><div>${sidebar}</div></details></aside><main id="main" tabindex="-1" class="article${toc ? " article-with-outline" : ""}"><div class="article-top">${breadcrumb}${version ? `<div class="doc-version"><span>${escape(version)}</span>${library ? `<a href="${url("versions.html")}">All versions</a>` : ""}</div>` : ""}</div>${toc ? `<details class="page-outline" open><summary>On this page</summary><nav aria-label="On this page">${toc}</nav></details>` : ""}<div class="article-content">${body}</div></main></div>` : `<main id="main" tabindex="-1">${body}</main>`}
  <footer>
    <a class="brand" href="${url("index.html")}"><img class="brand-mark" src="${url("assets/logo.svg")}" alt="" width="34" height="34">${brand}</a>
    <p>Open source · Apache 2.0</p>
    <div><a href="${repository}">GitHub ↗</a><a href="${repository}/issues">Issues ↗</a><a class="product-switch" href="${escape(crossLink)}">${library ? "Desktop app" : "For developers"} ↗</a></div>
  </footer>
</body>
</html>`;
}
