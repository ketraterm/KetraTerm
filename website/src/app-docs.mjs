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
import {layout, relative} from "./layout.mjs";
import {renderMarkdown} from "./markdown.mjs";
import {stableVersion} from "../assets/releases.js";

export const appDocuments = [
  ["guide.html", "App guide", "ketraterm-app/docs/user-guide.md"],
  [
    "settings.html",
    "Settings reference",
    "ketraterm-app/docs/profile-config-toml.md",
  ],
  ["changelog.html", "App changelog", "ketraterm-app/CHANGELOG.md"],
];

export function renderAppDocumentation({ root, id, ref, write, corrections }) {
  if (!stableVersion(id))
    throw new Error("App documentation requires a stable release");
  const documents = appDocuments.map(([file, title, canonical]) => {
    const corrected = `website/released-app/${id}/${file.replace(/\.html$/, ".md")}`;
    const useCorrection =
      corrections && fs.existsSync(path.join(corrections.root, corrected));
    const source = useCorrection ? corrected : canonical;
    const origin = useCorrection ? corrections : { root, ref };
    if (!fs.existsSync(path.join(origin.root, source)))
      throw new Error(`Missing app documentation for ${id}: ${canonical}`);
    return { file, title, canonical, source, ...origin };
  });
  const routes = new Map(
    documents.flatMap(({ file, canonical, source }) => [
      [canonical, file],
      [source, file],
    ]),
  );
  const index = [];
  for (const {
    file,
    title,
    source,
    root: sourceRoot,
    ref: sourceRef,
  } of documents) {
    const content = renderMarkdown({
      root: sourceRoot,
      source,
      file,
      ref: sourceRef,
      routes,
    });
    const sidebar = `<label class="search-label">Find app help<input type="search" data-doc-search placeholder="Search app guides" autocomplete="off" data-index="search.json"></label><div class="search-results" aria-live="polite"></div><details open><summary>Using KetraTerm</summary>${documents.map(({ file: target, title: label }) => `<a ${file === target ? 'aria-current="page"' : ""} href="${relative(file, target)}">${label}</a>`).join("")}</details>`;
    write(
      file,
      layout({
        file,
        ...content,
        title,
        version: `${id} · Stable release`,
        sidebar,
      }),
    );
    index.push({ title, file, text: content.text });
  }
  write("search.json", JSON.stringify(index));
  write("app-version.json", JSON.stringify({ id, ref }, null, 2));
}
