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
import {fileURLToPath} from "node:url";
import {execFileSync} from "node:child_process";
import {renderDocumentation} from "../src/docs.mjs";
import {appDocuments, renderAppDocumentation} from "../src/app-docs.mjs";
import {copyArchives, inventory, releaseId} from "../src/archive.mjs";
import {pages} from "../src/pages.mjs";
import {escape, relative} from "../src/layout.mjs";
import {exportRelease, latestAppRelease} from "../src/release-sources.mjs";

export function buildSite({
  root,
  previous,
  release,
  ref,
  appRelease,
  requireApi = false,
  baseUrl = "https://ketraterm.github.io/KetraTerm",
}) {
  const output = path.join(root, "build/website");
  const id = release ? releaseId(release) : "dev";
  if (
    release &&
    fs.readFileSync(path.join(root, "VERSION"), "utf8").trim() !== release
  )
    throw new Error("Release tag does not match VERSION");
  if (
    previous &&
    (previous === output || previous.startsWith(output + path.sep))
  )
    throw new Error("Archive must be outside build output");
  if (!appRelease) throw new Error("Stable app release source is required");
  // Output is fixed inside the repository; never delete a caller-selected directory.
  fs.rmSync(output, { recursive: true, force: true });
  fs.mkdirSync(output, { recursive: true });
  copyArchives(previous, output);
  if (id === "dev") {
    for (const family of ["library/api", "library/guides"])
      fs.rmSync(path.join(output, family, "dev"), {
        recursive: true,
        force: true,
      });
  }
  const write = (file, content) => {
    const target = path.join(output, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content);
  };
  const existing = inventory(output).find((x) => x.id === id);
  if (!existing?.guides)
    renderDocumentation({ root, id, ref, write, appUrl: baseUrl });
  renderAppDocumentation({ ...appRelease, write, corrections: { root, ref } });
  const dokka = path.join(root, "build/dokka/html");
  if (!existing?.api && fs.existsSync(path.join(dokka, "index.html")))
    fs.cpSync(dokka, path.join(output, "library/api", id), { recursive: true });
  if (
    requireApi &&
    !fs.existsSync(path.join(output, "library/api", id, "index.html"))
  )
    throw new Error("Dokka output is required");
  const versions = inventory(output);
  const guideId =
    versions.find((v) => v.stable && v.guides)?.id ||
    versions.find((v) => v.id === "dev" && v.guides)?.id ||
    versions.find((v) => v.guides).id;
  const sitePages = pages(versions, guideId, baseUrl);
  for (const page of sitePages) write(page.file, page.html);
  fs.cpSync(path.join(root, "website/assets"), path.join(output, "assets"), {
    recursive: true,
  });
  fs.copyFileSync(
    path.join(
      root,
      "ketraterm-app/src/main/resources/io/github/ketraterm/app/icons/logo.svg",
    ),
    path.join(output, "assets/logo.svg"),
  );
  fs.cpSync(
    path.join(root, "website/screenshots"),
    path.join(output, "screenshots"),
    { recursive: true },
  );
  const mermaid = path.resolve(
    fileURLToPath(new URL("../node_modules/mermaid/", import.meta.url)),
  );
  fs.mkdirSync(path.join(output, "assets/mermaid"), {
    recursive: true,
  });
  fs.copyFileSync(
    path.join(mermaid, "dist/mermaid.esm.min.mjs"),
    path.join(output, "assets/mermaid/mermaid.esm.min.mjs"),
  );
  fs.copyFileSync(
    path.join(mermaid, "LICENSE"),
    path.join(output, "assets/mermaid/LICENSE"),
  );
  fs.cpSync(
    path.join(mermaid, "dist/chunks/mermaid.esm.min"),
    path.join(output, "assets/mermaid/chunks/mermaid.esm.min"),
    {
      recursive: true,
      filter: (file) =>
        fs.statSync(file).isDirectory() || file.endsWith(".mjs"),
    },
  );
  fs.cpSync(path.join(output, "assets"), path.join(output, "library/assets"), {
    recursive: true,
  });
  if (previous && fs.existsSync(path.join(previous, "CNAME")))
    fs.copyFileSync(path.join(previous, "CNAME"), path.join(output, "CNAME"));
  write("library/versions.json", JSON.stringify(versions, null, 2));
  const redirect = (file, to) =>
    write(
      file,
      `<!doctype html><html lang="en"><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=${escape(to)}"><title>Page moved</title><a href="${escape(to)}">Continue</a><script>location.replace(${JSON.stringify(to).replace(/</g, "\\u003c")} + location.search + location.hash)</script></html>`,
    );
  const latest = versions.find((v) => v.stable && v.api);
  if (latest) {
    fs.rmSync(path.join(output, "library/api/latest"), {
      recursive: true,
      force: true,
    });
    fs.cpSync(
      path.join(output, "library/api", latest.id),
      path.join(output, "library/api/latest"),
      { recursive: true },
    );
  } else if (!fs.existsSync(path.join(output, "library/api/latest/index.html")))
    redirect("library/api/latest/index.html", "../../versions.html");
  redirect("library/api/index.html", "../versions.html");
  redirect("library/guides/index.html", `${guideId}/docs/README.html`);
  redirect("app.html", "./");
  redirect("library.html", "library/");
  redirect("versions.html", "library/versions.html");
  redirect("docs/index.html", "../library/versions.html");
  redirect(
    "guides/index.html",
    `../library/guides/${guideId}/docs/README.html`,
  );
  // Keep incoming API and guide deep links, including their fragments.
  const aliases = (directory, legacy) => {
    for (const entry of fs.readdirSync(path.join(output, directory), {
      withFileTypes: true,
    })) {
      const source = `${directory}/${entry.name}`,
        target = `${legacy}/${entry.name}`;
      if (entry.isDirectory()) aliases(source, target);
      else if (
        entry.name.endsWith(".html") &&
        !fs.existsSync(path.join(output, target))
      )
        redirect(target, relative(target, source));
    }
  };
  aliases("library/api", "docs");
  aliases("library/guides", "guides");
  for (const version of versions.filter((item) => item.guides)) {
    const oldAppGuide = `guides/${version.id}/ketraterm-app/README.html`;
    if (fs.existsSync(path.join(output, "guide.html")))
      redirect(oldAppGuide, relative(oldAppGuide, "guide.html"));
    for (const [target, , source] of appDocuments) {
      const file = `guides/${version.id}/${source.replace(/\.md$/, ".html")}`;
      if (fs.existsSync(path.join(output, target)))
        redirect(file, relative(file, target));
    }
  }
  write(".nojekyll", "");
  const base = baseUrl.replace(/\/$/, "");
  const canonical = [
    ...sitePages.map((page) => page.file),
    ...appDocuments.map(([file]) => file),
  ];
  write(
    "sitemap.xml",
    `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${canonical.map((file) => `<url><loc>${escape(base + "/" + file)}</loc></url>`).join("")}</urlset>`,
  );
  write("library/.nojekyll", "");
  write(
    "robots.txt",
    `User-agent: *\nAllow: /\nSitemap: ${base}/sitemap.xml\n`,
  );
  return { output, versions };
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const root = path.resolve(fileURLToPath(new URL("../../", import.meta.url)));
  const result = buildSite({
    root,
    previous:
      process.env.WEBSITE_ARCHIVE && path.resolve(process.env.WEBSITE_ARCHIVE),
    release: process.env.WEBSITE_RELEASE,
    appRelease: exportRelease(
      root,
      process.env.WEBSITE_APP_RELEASE || (await latestAppRelease()),
    ),
    ref:
      process.env.WEBSITE_SOURCE_REF ||
      execFileSync("git", ["rev-parse", "HEAD"], {
        cwd: root,
        encoding: "utf8",
      }).trim(),
    requireApi: process.env.WEBSITE_REQUIRE_API === "true",
    baseUrl: process.env.WEBSITE_URL,
  });
  console.log(
    `Built website: ${result.output}\n${result.versions.length} documentation versions retained.`,
  );
}
