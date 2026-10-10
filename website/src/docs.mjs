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

import fs from "node:fs";
import path from "node:path";
import { defaultSiteUrl, escape, layout, relative } from "./layout.mjs";
import { renderMarkdown } from "./markdown.mjs";
import { appDocuments } from "./app-docs.mjs";

export function documentationFiles(root) {
  const files = [
    "README.md",
    "ARCHITECTURE.md",
    "CONTRIBUTING.md",
    "CHANGELOG.md",
  ];
  const visit = (directory) => {
    if (!fs.existsSync(path.join(root, directory))) return;
    for (const entry of fs.readdirSync(path.join(root, directory), {
      withFileTypes: true,
    })) {
      const file = `${directory}/${entry.name}`;
      if (entry.isDirectory() && !["reviews", "build"].includes(entry.name))
        visit(file);
      else if (
        entry.isFile() &&
        entry.name.endsWith(".md") &&
        entry.name !== "AGENTS.md"
      )
        files.push(file);
    }
  };
  visit("docs");
  for (const module of fs
    .readdirSync(root)
    .filter(
      (name) =>
        name.startsWith("ketraterm-") &&
        !["ketraterm-app", "ketraterm-intellij-plugin"].includes(name),
    )) {
    for (const name of ["README.md", "Module.md", "CHANGELOG.md"])
      if (fs.existsSync(path.join(root, module, name)))
        files.push(`${module}/${name}`);
    visit(`${module}/docs`);
  }
  return files.filter((file) => fs.existsSync(path.join(root, file)));
}

export function renderDocumentation({
  root,
  id,
  ref,
  write,
  appUrl = defaultSiteUrl,
}) {
  const files = documentationFiles(root),
    included = new Set(files);
  const output = (source) =>
    `library/guides/${id}/${source.replace(/\.md$/, ".html")}`;
  const routes = new Map(files.map((source) => [source, output(source)]));
  // Cross-product references are explicit absolute URLs, so the library subtree
  // also works when served as a separate site.
  for (const [file, , source] of appDocuments)
    routes.set(source, `${appUrl.replace(/\/$/, "")}/${file}`);
  const groups = [
    [
      "Start here",
      [
        ["Documentation", "docs/README.md"],
        ["Installation", "README.md"],
        ["Module guides", null],
        ["Changelog", "CHANGELOG.md"],
      ],
    ],
    [
      "Capabilities",
      [
        ["Feature map", "docs/terminal-feature-map.md"],
        ["Terminal", "docs/features/terminal.md"],
        ["Shell integration", "docs/features/shells.md"],
        ["Swing view & hosts", "docs/features/desktop.md"],
        ["Completion", "docs/features/completion.md"],
        ["Embedding", "docs/features/embedding.md"],
        ["Known limits", "docs/terminal-feature-gap-map.md"],
      ],
    ],
    [
      "Embedding guides",
      [
        ["Configuration", "docs/library/configuration.md"],
        ["Compatibility", "docs/library/compatibility.md"],
        ["Render ownership", "docs/library/render-ownership.md"],
      ],
    ],
    [
      "Reference",
      [
        ["Protocols", "docs/reference/protocol.md"],
        ["Notifications", "docs/reference/notifications.md"],
        ["Storage & privacy", "docs/reference/storage.md"],
      ],
    ],
    [
      "Development",
      [
        ["Architecture", "ARCHITECTURE.md"],
        ["Contributing", "CONTRIBUTING.md"],
        ["Conformance testing", "docs/development/conformance-testing.md"],
      ],
    ],
  ].map(([title, links]) => [
    title,
    links.filter(([, source]) => !source || included.has(source)),
  ]);
  const linkTarget = (source) =>
    source ? output(source) : `library/guides/${id}/modules.html`;
  const groupFor = (source) =>
    groups.find(([, links]) =>
      links.some(([, target]) => target === source),
    )?.[0];
  const contextFor = (source) => {
    if (source.startsWith("ketraterm-")) {
      const [module, name] = source.split("/");
      const role =
        name === "README.md"
          ? "Module guide"
          : name === "Module.md"
            ? "Module notes"
            : name === "CHANGELOG.md"
              ? "Module changelog"
              : "Module contract";
      return `${role} · ${module}`;
    }
    return groupFor(source) || "Guides";
  };
  const breadcrumbsFor = (file, source, title) => {
    const breadcrumbs = [
      { label: "Library", href: relative(file, "library/index.html") },
    ];
    if (source.startsWith("ketraterm-")) {
      const module = source.split("/")[0];
      const readme = `${module}/README.md`;
      breadcrumbs.push({
        label: "Modules",
        href: relative(file, linkTarget(null)),
      });
      if (source !== readme && included.has(readme))
        breadcrumbs.push({
          label: module,
          href: relative(file, output(readme)),
        });
    } else {
      const group = groupFor(source);
      breadcrumbs.push(
        group
          ? { label: group }
          : {
              label: "Guides",
              ...(included.has("docs/README.md") && {
                href: relative(file, output("docs/README.md")),
              }),
            },
      );
    }
    breadcrumbs.push({ label: title });
    return breadcrumbs;
  };
  const starters = [
    [
      "Embed a Swing terminal",
      "ketraterm-swing/README.md",
      "Add a reusable terminal component to your Swing application.",
    ],
    [
      "Create a headless session",
      "ketraterm-headless/README.md",
      "Connect your transport to the terminal session pipeline.",
    ],
    [
      "Configure host services",
      "docs/library/configuration.md",
      "Choose the settings and services owned by your application.",
    ],
  ].filter(([, source]) => included.has(source));
  const sidebar = (file) =>
    `<label class="search-label">Find a library guide<input type="search" data-doc-search placeholder="Search library docs" autocomplete="off" data-index="${relative(file, `library/guides/${id}/search.json`)}"></label><div class="search-results" aria-live="polite"></div>${groups.map(([title, links]) => `<details open><summary>${title}</summary>${links.map(([label, source]) => `<a ${linkTarget(source) === file ? 'aria-current="page"' : ""} href="${relative(file, linkTarget(source))}">${label}</a>`).join("")}</details>`).join("")}`;
  const writePage = (file, content) =>
    write(
      file,
      layout({
        file,
        ...content,
        version: id === "dev" ? "Development" : id,
        guideId: id,
        sidebar: sidebar(file),
        appUrl,
      }),
    );
  const index = [];
  for (const source of files) {
    const file = output(source);
    if (source === "docs/README.md") {
      // The repository index includes product docs. The library site's index
      // is built from its own navigation, without copying that mixed index.
      const body = `<h1>Library documentation</h1><p>Embed a terminal, choose modules, and configure your host.</p>${starters.length ? `<div class="guide-starts">${starters.map(([label, target, description]) => `<a href="${relative(file, output(target))}"><strong>${escape(label)}</strong><span>${escape(description)}</span></a>`).join("")}</div>` : ""}${groups
        .filter(([title]) => title !== "Start here")
        .map(
          ([title, links]) =>
            `<h2>${title}</h2><ul>${links.map(([label, target]) => `<li><a href="${relative(file, linkTarget(target))}">${label}</a></li>`).join("")}</ul>`,
        )
        .join("")}`;
      writePage(file, {
        title: "Library documentation",
        body,
        breadcrumbs: breadcrumbsFor(file, source, "Library documentation"),
      });
      index.push({
        title: "Library documentation",
        file: "docs/README.html",
        text: "Embedding guides, capabilities, reference, and development.",
        context: "Guides",
      });
      continue;
    }
    const content = renderMarkdown({ root, source, file, ref, routes });
    writePage(file, {
      ...content,
      breadcrumbs: breadcrumbsFor(file, source, content.title),
    });
    index.push({
      title: content.title,
      file: source.replace(/\.md$/, ".html"),
      text: content.text,
      context: contextFor(source),
    });
  }
  const file = `library/guides/${id}/modules.html`;
  const modules = files.filter((source) =>
    /^ketraterm-[^/]+\/README.md$/.test(source),
  );
  const body = `<h1>Module guides</h1><p>Consumer entry points and usage examples. Module notes describe dependencies and internal structure.</p><div class="module-list">${modules
    .map((source) => {
      const module = source.split("/")[0];
      return `<div><a href="${relative(file, output(source))}">${module}</a>${included.has(`${module}/Module.md`) ? `<a class="muted" href="${relative(file, output(`${module}/Module.md`))}">Module notes ↗</a>` : ""}</div>`;
    })
    .join("")}</div>`;
  writePage(file, {
    title: "Module guides",
    body,
    breadcrumbs: [
      { label: "Library", href: relative(file, "library/index.html") },
      { label: "Modules" },
    ],
  });
  write(`library/guides/${id}/search.json`, JSON.stringify(index));
}
