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
import { decodeHTML } from "entities";
import { parseHTML } from "linkedom";
import { escape } from "./layout.mjs";

const anchors =
  /<!--[\s\S]*?-->|<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>|<a\b[^>]*\shref\s*=\s*(["'])([^"']*)\2[^>]*>([\s\S]*?)<\/a\s*>/gi;
const localUrl = (href) =>
  href && !/^(?:[a-z][\w+.-]*:|\/\/|#|\/)/i.test(href) && !/[?#]/.test(href);
const inside = (target, tree) => target.startsWith(tree + path.sep);
const text = (html) => decodeHTML(html.replace(/<[^>]*>/g, "")).trim();
const functionFragment = /^-?\d+%2FFunctions%2F-?\d+$/i;
const identifier = /^[A-Za-z_][A-Za-z0-9_]*$/;
const dokkaName = (name) =>
  name.replace(/[A-Z]/g, (letter) => "-" + letter.toLowerCase());

// Repair generator defects only when Dokka's own declaration indexes prove the
// destination. Preserve unknown missing output for publication validation.
export function repairApiLinks({ output, versions }) {
  const ids = versions.filter(({ api }) => api).map(({ id }) => id);
  if (fs.existsSync(path.join(output, "library/api/latest/index.html")))
    ids.push("latest");
  let repaired = 0;
  let unlinked = 0;
  for (const id of ids) {
    const tree = path.resolve(output, "library/api", id);
    const indexes = new Map();
    const functionIndexes = new Map();
    const functionIndex = (file) => {
      if (functionIndexes.has(file)) return functionIndexes.get(file);
      const { document } = parseHTML(fs.readFileSync(file, "utf8"));
      const result = { anchors: new Set(), groups: new Map() };
      functionIndexes.set(file, result);
      for (const element of document.querySelectorAll("[id], a[data-name]")) {
        if (element.id) result.anchors.add(element.id);
        if (element.hasAttribute("data-name"))
          result.anchors.add(element.getAttribute("data-name"));
      }
      for (const row of document.querySelectorAll(".table-row")) {
        const group = row.previousElementSibling;
        const canonical = group?.getAttribute("data-name");
        if (
          group?.tagName !== "A" ||
          !canonical ||
          !functionFragment.test(canonical) ||
          group.id !== canonical ||
          !group.hasAttribute("anchor-label")
        )
          continue;
        for (const name of row.querySelectorAll(".symbol .token.function")) {
          if (name.textContent.trim() !== group.getAttribute("anchor-label"))
            continue;
          const href = name.closest("a")?.getAttribute("href");
          if (!href) continue;
          const [pathname, fragment] = href.split("#");
          if (
            !fragment ||
            !functionFragment.test(fragment) ||
            (pathname && !localUrl(pathname)) ||
            path.resolve(
              path.dirname(file),
              decodeURIComponent(pathname || path.basename(file)),
            ) !== file
          )
            continue;
          const groups = result.groups.get(fragment) || new Set();
          groups.add(canonical);
          result.groups.set(fragment, groups);
        }
      }
      return result;
    };
    const declarationIndex = (directory) => {
      if (indexes.has(directory)) return indexes.get(directory);
      const file = path.join(directory, "index.html");
      const result = {
        members: new Map(),
        targets: new Set(),
        dataClass: false,
        present: false,
      };
      indexes.set(directory, result);
      if (!fs.existsSync(file)) return result;
      result.present = true;
      const { document } = parseHTML(fs.readFileSync(file, "utf8"));
      result.dataClass = /\bdata\s+class\b/.test(
        document.querySelector(".cover .symbol")?.textContent || "",
      );
      for (const link of document.querySelectorAll(
        ".table-row .symbol a[href], .table-row .inline-flex a[href]",
      )) {
        const href = link.getAttribute("href");
        if (!localUrl(href)) continue;
        const target = path.resolve(directory, decodeURIComponent(href));
        if (!inside(target, tree)) continue;
        const label = link.textContent.trim();
        result.targets.add(target);
        const targets = result.members.get(label) || new Set();
        targets.add(target);
        result.members.set(label, targets);
      }
      return result;
    };
    const unpublished = (target, label) => {
      const segments = path.relative(tree, target).split(path.sep);
      if (segments.length !== 4) return false;
      const [module, packageName, className, page] = segments;
      if (
        !className.startsWith("-") ||
        !packageName.split(".").every((part) => identifier.test(part))
      )
        return false;
      const name = label.startsWith(packageName + ".")
        ? label.slice(packageName.length + 1)
        : label;
      const names = name.split(".");
      if (!names.every((part) => identifier.test(part))) return false;
      let owner;
      let member;
      if (page === "index.html") {
        if (names.length !== 1 || dokkaName(names[0]) !== className)
          return false;
        [owner] = names;
      } else {
        if (names.length === 2) [owner, member] = names;
        else if (names.length === 1) [member] = names;
        else return false;
        if (
          (owner && dokkaName(owner) !== className) ||
          dokkaName(member) + ".html" !== page
        )
          return false;
      }
      const packageDirectory = path.join(tree, module, packageName);
      const packageIndex = declarationIndex(packageDirectory);
      if (!packageIndex.present) return false;
      const classFile = path.join(packageDirectory, className, "index.html");
      const index = declarationIndex(path.dirname(classFile));
      if (!index.present)
        return (
          Boolean(owner) &&
          !packageIndex.targets.has(classFile) &&
          !packageIndex.members.has(owner)
        );
      if (!member || !packageIndex.targets.has(classFile)) return false;
      return !index.targets.has(target) && !index.members.has(member);
    };
    const visit = (directory) => {
      for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const file = path.join(directory, entry.name);
        if (entry.isDirectory()) visit(file);
        else if (entry.isFile() && entry.name.endsWith(".html")) {
          const original = fs.readFileSync(file, "utf8");
          if (
            !original.includes("--root--.html") &&
            !original.includes("copy.html") &&
            !/%2FFunctions%2F/i.test(original) &&
            !/<p\b[^>]*\bparagraph\b/i.test(original)
          )
            continue;
          let sourceAnchors;
          let ordinal = 0;
          const corrected = original.replace(
            anchors,
            (anchor, rawTag, quote, encodedHref, children) => {
              if (encodedHref === undefined) return anchor;
              const sourceOrdinal = ordinal++;
              const href = decodeHTML(encodedHref);
              const [pathname, fragment] = href.split("#");
              if (
                fragment &&
                functionFragment.test(fragment) &&
                (!pathname || localUrl(pathname))
              ) {
                const target = path.resolve(
                  path.dirname(file),
                  decodeURIComponent(pathname || path.basename(file)),
                );
                if (!inside(target, tree) || !fs.existsSync(target))
                  return anchor;
                const index = functionIndex(target);
                if (
                  index.anchors.has(fragment) ||
                  index.anchors.has(decodeURIComponent(fragment))
                )
                  return anchor;
                const groups = index.groups.get(fragment);
                if (groups?.size !== 1) return anchor;
                const [group] = groups;
                repaired++;
                return anchor.replace(
                  /(\shref\s*=\s*)(["'])[^"']*\2/i,
                  (attribute, prefix, delimiter) =>
                    prefix +
                    delimiter +
                    escape(pathname + "#" + group) +
                    delimiter,
                );
              }
              if (!localUrl(href)) return anchor;
              const target = path.resolve(
                path.dirname(file),
                decodeURIComponent(href),
              );
              if (!inside(target, tree) || fs.existsSync(target)) return anchor;
              if (!/(?:^|\/)(?:--root--|copy)\.html$/.test(href)) {
                if (!unpublished(target, text(children))) return anchor;
                sourceAnchors ??=
                  parseHTML(original).document.querySelectorAll("a[href]");
                const source = sourceAnchors[sourceOrdinal];
                if (
                  source?.getAttribute("href") !== href ||
                  source.textContent.trim() !== text(children) ||
                  !source.closest("p.paragraph") ||
                  source.closest(
                    ".symbol, .table, table, nav, header, .navigation",
                  )
                )
                  return anchor;
                unlinked++;
                return children;
              }
              const index = declarationIndex(path.dirname(target));
              if (!index.present) return anchor;
              if (path.basename(target) === "copy.html") {
                for (const targets of index.members.values())
                  if (targets.has(target)) return anchor;
                if (!index.dataClass) return anchor;
                unlinked++;
                return children;
              }
              const candidates = index.members.get(text(children));
              if (candidates)
                for (const member of candidates)
                  if (!fs.existsSync(member)) return anchor;
              if (candidates?.size === 1) {
                const [member] = candidates;
                const relative = path
                  .relative(path.dirname(file), member)
                  .split(path.sep)
                  .join("/");
                repaired++;
                return anchor.replace(
                  /(\shref\s*=\s*)(["'])[^"']*\2/i,
                  (attribute, prefix, delimiter) =>
                    prefix + delimiter + escape(relative) + delimiter,
                );
              }
              unlinked++;
              return children;
            },
          );
          if (corrected !== original) fs.writeFileSync(file, corrected);
        }
      }
    };
    visit(tree);
  }
  return { repaired, unlinked };
}
