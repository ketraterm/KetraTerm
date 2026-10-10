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
import {defaultSiteUrl, escape, relative} from "./layout.mjs";

const navigationBlock =
  /<!-- ketraterm-api-navigation:start -->[\s\S]*?<!-- ketraterm-api-navigation:end -->/g;
const stylesheetBlock =
  /<!-- ketraterm-api-stylesheet:start -->[\s\S]*?<!-- ketraterm-api-stylesheet:end -->/g;

// Refresh site navigation around retained API content without serializing Dokka HTML.
export function addApiNavigation({
  output,
  versions,
  latest,
  appUrl = defaultSiteUrl,
}) {
  const trees = versions.filter(({ api }) => api).map(({ id }) => [id, id]);
  if (fs.existsSync(path.join(output, "library/api/latest/index.html")))
    trees.push(["latest", latest || "Latest stable"]);

  for (const [id, version] of trees) {
    const visit = (directory) => {
      for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const target = path.join(directory, entry.name);
        if (entry.isDirectory()) visit(target);
        else if (entry.isFile() && entry.name.endsWith(".html")) {
          const original = fs.readFileSync(target, "utf8");
          // navigation.html is a fragment loaded by Dokka, not a standalone page.
          if (!/<body\b/i.test(original) || !/<\/head\s*>/i.test(original))
            continue;
          const html = original
            .replace(navigationBlock, "")
            .replace(stylesheetBlock, "");
          const root = /<div\b[^>]*\bclass=(['"])[^'"]*\broot\b[^'"]*\1[^>]*>/i;
          if (!root.test(html) || !/id=['"]navigation-wrapper['"]/i.test(html))
            continue;
          const file = path.relative(output, target).split(path.sep).join("/");
          const home = escape(relative(file, "library/index.html"));
          const navigation = `<!-- ketraterm-api-navigation:start -->
<div class="ketraterm-api-navigation">
  <a class="ketraterm-api-brand" href="${home}" aria-label="KetraTerm Library home"><img src="${escape(relative(file, "library/assets/logo.svg"))}" alt="" width="28" height="28">KetraTerm</a>
  <nav class="ketraterm-api-products" aria-label="KetraTerm products"><a href="${escape(appUrl.replace(/\/$/, "") + "/")}">App</a><a href="${home}" aria-current="true">Library</a></nav>
  <div class="ketraterm-api-reference"><span>API · ${escape(version === "dev" ? "Development" : version)}</span><a href="${escape(relative(file, "library/versions.html"))}">All versions</a></div>
</div>
<!-- ketraterm-api-navigation:end -->`;
          const stylesheet = `<!-- ketraterm-api-stylesheet:start -->
<link rel="icon" href="${escape(relative(file, "library/assets/logo.svg"))}" type="image/svg+xml">
<link rel="stylesheet" href="${escape(relative(file, "library/assets/api-navigation.css"))}">
<!-- ketraterm-api-stylesheet:end -->`;
          const decorated = html
            .replace(
              /<header\b[^>]*\sid=(['"])navigation-wrapper\1[^>]*>[\s\S]*?<\/header>/i,
              (header) =>
                header.replace(/<a\b[^>]*>/gi, (anchor) => {
                  const classes = anchor.match(/\sclass\s*=\s*(['"])(.*?)\1/i);
                  if (!classes?.[2].split(/\s+/).includes("library-name--link"))
                    return anchor;
                  // Keep the inserted product links first in normal keyboard order.
                  return anchor.replace(
                    /\s+tabindex\s*=\s*(?:"\+?0*[1-9]\d*"|'\+?0*[1-9]\d*'|\+?0*[1-9]\d*(?=[\s>]))/i,
                    "",
                  );
                }),
            )
            .replace(root, (match) => match + navigation)
            .replace(/<\/head\s*>/i, (match) => stylesheet + match);
          if (decorated !== original) fs.writeFileSync(target, decorated);
        }
      }
    };
    visit(path.join(output, "library/api", id));
  }
}
