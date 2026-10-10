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
import {Marked} from "marked";
import GithubSlugger from "github-slugger";
import {decodeHTML} from "entities";
import {escape, relative, repository} from "./layout.mjs";

// Both sites render the owning Markdown source. Only published routes differ.
export function renderMarkdown({
  root,
  source,
  file,
  ref,
  routes,
  markdown = fs.readFileSync(path.join(root, source), "utf8"),
}) {
  const slugger = new GithubSlugger();
  const headings = [];
  const resolveLink = (href) => {
    if (!href || /^(?:[a-z][\w+.-]*:|#|\/\/)/i.test(href)) return href;
    const [pathname, hash = ""] = href.split("#");
    let resolved = path.posix.normalize(
      path.posix.join(path.posix.dirname(source), decodeURI(pathname)),
    );
    if (routes.has(`${resolved}/README.md`)) resolved += "/README.md";
    const suffix = hash ? `#${hash}` : "";
    if (routes.has(resolved)) {
      const target = routes.get(resolved);
      return (
        (/^https?:/.test(target) ? target : relative(file, target)) + suffix
      );
    }
    const target = path.join(root, resolved);
    if (!fs.existsSync(target))
      throw new Error(`Missing source link in ${source}: ${href}`);
    const kind = fs.statSync(target).isDirectory() ? "tree" : "blob";
    return `${repository}/${kind}/${encodeURIComponent(ref)}/${resolved.split("/").map(encodeURIComponent).join("/")}${suffix}`;
  };
  const renderer = new Marked({
    gfm: true,
    renderer: {
      heading({ tokens, depth }) {
        const html = this.parser.parseInline(tokens),
          text = decodeHTML(html.replace(/<[^>]*>/g, ""));
        const slug = slugger.slug(text);
        if (depth === 2 || depth === 3) headings.push({ depth, text, slug });
        return `<h${depth} id="${escape(slug)}">${html}</h${depth}>\n`;
      },
      link({ href, title, tokens }) {
        const safe = resolveLink(href);
        if (/^(javascript|data|vbscript):/i.test(safe))
          throw new Error(`Unsafe link in ${source}`);
        return `<a href="${escape(safe)}"${title ? ` title="${escape(title)}"` : ""}>${this.parser.parseInline(tokens)}</a>`;
      },
      image({ href, title, text }) {
        const resolved = resolveLink(href).replace(
          `${repository}/blob/`,
          "https://raw.githubusercontent.com/ketraterm/KetraTerm/",
        );
        if (/^(javascript|data|vbscript):/i.test(resolved))
          throw new Error(`Unsafe image in ${source}`);
        return `<img src="${escape(resolved)}" alt="${escape(text)}"${title ? ` title="${escape(title)}"` : ""} loading="lazy">`;
      },
    },
  });
  const content = renderer
    .parse(markdown)
    .replace(/<table>/g, '<div class="table-scroll"><table>')
    .replace(/<\/table>/g, "</table></div>");
  return {
    title: markdown.match(/^# (.+)/m)?.[1].replace(/[`*]/g, "") || source,
    body: `<div class="prose">${content}</div><div class="source-link"><a href="${repository}/blob/${encodeURIComponent(ref)}/${source}">View source on GitHub ↗</a></div>`,
    toc: headings
      .map(
        (h) =>
          `<a class="depth-${h.depth}" href="#${escape(h.slug)}">${escape(h.text)}</a>`,
      )
      .join(""),
    text: decodeHTML(content.replace(/<[^>]*>/g, " ")).replace(/\s+/g, " "),
  };
}
