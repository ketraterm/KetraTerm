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
import { fileURLToPath } from "node:url";
import { parseHTML } from "linkedom";
import { decodeHTMLAttribute } from "entities";

export function checkLinks(root) {
  root = path.resolve(root) + path.sep;
  const pages = [];
  for (const name of fs.readdirSync(root))
    if (name.endsWith(".html")) pages.push(path.join(root, name));
  // Keep DOM-based validation for site pages and Markdown guides.
  function visitGuides(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) visitGuides(file);
      else if (entry.isFile() && entry.name.endsWith(".html")) pages.push(file);
    }
  }
  for (const name of fs.readdirSync(path.join(root, "library")))
    if (name.endsWith(".html")) pages.push(path.join(root, "library", name));
  visitGuides(path.join(root, "library/guides"));
  visitGuides(path.join(root, "guides"));
  const documents = new Map(
    pages.map((file) => [
      file,
      parseHTML(fs.readFileSync(file, "utf8")).document,
    ]),
  );
  const errors = [];
  for (const [file, document] of documents) {
    const links = [...document.querySelectorAll("[href],[src]")].map(
      (element) => element.getAttribute("href") ?? element.getAttribute("src"),
    );
    for (const map of document.querySelectorAll('script[type="importmap"]'))
      links.push(...Object.values(JSON.parse(map.textContent).imports));
    for (const link of links) {
      if (!link || /^(?:[a-z][\w+.-]*:|\/\/)/i.test(link)) continue;
      const [pathname, fragment] = link.split("#");
      let target = pathname
        ? path.resolve(
            path.dirname(file),
            decodeURIComponent(pathname.split("?")[0]),
          )
        : file;
      if (fs.existsSync(target) && fs.statSync(target).isDirectory())
        target = path.join(target, "index.html");
      if (!target.startsWith(root) || !fs.existsSync(target))
        errors.push(`${path.relative(root, file)}: missing ${link}`);
      else if (
        file.startsWith(path.join(root, "library") + path.sep) &&
        !target.startsWith(path.join(root, "library") + path.sep)
      )
        errors.push(
          `${path.relative(root, file)}: library depends on parent site: ${link}`,
        );
      else if (
        fragment &&
        documents.has(target) &&
        !documents.get(target).getElementById(decodeURIComponent(fragment))
      )
        errors.push(`${path.relative(root, file)}: missing anchor ${link}`);
    }
  }
  const libraryDirectory = path.join(root, "library");
  const libraryRoot = libraryDirectory + path.sep;
  const apiTargets = new Map();
  const apiAnchors = new Map();
  const apiErrors = new Set();
  let apiPages = 0;

  function apiTarget(file) {
    if (!apiTargets.has(file)) {
      const stat = fs.statSync(file, { throwIfNoEntry: false });
      apiTargets.set(file, {
        exists: Boolean(stat),
        directory: stat?.isDirectory(),
      });
    }
    return apiTargets.get(file);
  }

  function apiAnchorIds(file, html) {
    if (!apiAnchors.has(file)) {
      const content = html ?? fs.readFileSync(file, "utf8");
      const ids = new Set();
      const names = new Set();
      // Dokka writes quoted HTML attributes. Retain only IDs, never an API DOM.
      for (const match of content.matchAll(
        /\sid\s*=\s*(?:"([^"]*)"|'([^']*)')/gi,
      ))
        ids.add(decodeHTMLAttribute(match[1] ?? match[2]));
      // Dokka's platform handler resolves raw hashes against a[data-name].
      for (const match of content.matchAll(
        /<!--[\s\S]*?-->|<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>|<a\b[^>]*\sdata-name\s*=\s*(?:"([^"]*)"|'([^']*)')/gi,
      ))
        if (match[2] !== undefined || match[3] !== undefined)
          names.add(decodeHTMLAttribute(match[2] ?? match[3]));
      apiAnchors.set(file, { ids, names });
    }
    return apiAnchors.get(file);
  }

  function checkApiPages(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        checkApiPages(file);
        continue;
      }
      if (!entry.isFile() || !entry.name.endsWith(".html")) continue;
      apiPages++;
      const html = fs.readFileSync(file, "utf8");
      // Walk each generated page's attributes without retaining its links or DOM.
      for (const match of html.matchAll(
        /\s(?:href|src)\s*=\s*(?:"([^"]*)"|'([^']*)')/gi,
      )) {
        const link = decodeHTMLAttribute(match[1] ?? match[2]);
        if (!link || /^(?:[a-z][\w+.-]*:|\/\/)/i.test(link)) continue;
        const source = path.relative(root, file);
        try {
          const [pathname, fragment] = link.split("#");
          const locationPath = pathname.split("?")[0];
          let target = locationPath
            ? path.resolve(path.dirname(file), decodeURIComponent(locationPath))
            : file;
          if (target !== libraryDirectory && !target.startsWith(libraryRoot)) {
            apiErrors.add(`${source}: library depends on parent site: ${link}`);
            continue;
          }
          if (apiTarget(target).directory)
            target = path.join(target, "index.html");
          if (!apiTarget(target).exists)
            apiErrors.add(`${source}: missing ${link}`);
          else if (fragment && target.endsWith(".html")) {
            const anchors = apiAnchorIds(
              target,
              target === file ? html : undefined,
            );
            if (
              !anchors.ids.has(decodeURIComponent(fragment)) &&
              !anchors.names.has(fragment)
            )
              apiErrors.add(`${source}: missing anchor ${link}`);
          }
        } catch (error) {
          if (!(error instanceof URIError)) throw error;
          apiErrors.add(`${source}: invalid URL ${link}`);
        }
      }
    }
  }

  checkApiPages(path.join(root, "library/api"));
  for (const error of apiErrors) errors.push(error);
  return { pages: documents.size, apiPages, errors };
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const root = fileURLToPath(new URL("../../build/website/", import.meta.url));
  const { pages, apiPages, errors } = checkLinks(root);
  const report = path.resolve(root, "../website-link-errors.log");
  if (errors.length) {
    fs.writeFileSync(report, errors.join("\n") + "\n");
    console.error(errors.slice(0, 20).join("\n"));
    console.error(`${errors.length} link errors. Full report: ${report}`);
    process.exitCode = 1;
  } else {
    fs.rmSync(report, { force: true });
    console.log(
      `Checked local links and anchors in ${pages} website and guide pages and ${apiPages} API pages.`,
    );
  }
}
