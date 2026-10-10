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

import {detectOS, releaseAssets, releasesUrl, selectAssets,} from "./downloads.js";
import {latestReleaseApi} from "./releases.js";

const themeButton = document.querySelector(".theme-toggle");
const navigation = document.querySelector(".nav-disclosure");
if (navigation) {
  const mobile = matchMedia("(max-width: 800px)");
  const updateNavigation = () => {
    navigation.open = !mobile.matches;
  };
  updateNavigation();
  mobile.addEventListener("change", updateNavigation);
}
try {
  const saved = localStorage.getItem("ketraterm-theme");
  if (saved === "light" || saved === "dark")
    document.documentElement.dataset.theme = saved;
} catch {
  /* Storage is optional in private browsing. */
}
themeButton?.addEventListener("click", () => {
  const dark =
    document.documentElement.dataset.theme === "dark" ||
    (!document.documentElement.dataset.theme &&
      matchMedia("(prefers-color-scheme: dark)").matches);
  const theme = dark ? "light" : "dark";
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem("ketraterm-theme", theme);
  } catch {
    /* Keep the in-page choice. */
  }
});

if (navigator.clipboard)
  for (const block of document.querySelectorAll("pre")) {
    const code = block.querySelector("code");
    if (!code || code.classList.contains("language-mermaid")) continue;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "copy-button";
    button.textContent = "Copy";
    button.setAttribute("aria-label", "Copy code");
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(code.textContent);
        button.textContent = "Copied";
      } catch {
        button.textContent = "Select text to copy";
      }
      setTimeout(() => {
        button.textContent = "Copy";
      }, 2500);
    });
    block.prepend(button);
  }

const search = document.querySelector("[data-doc-search]");
if (search) {
  const results = document.querySelector(".search-results");
  const indexUrl = new URL(search.dataset.index, location.href);
  let indexPromise;
  let timer;
  search.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const query = search.value.trim().toLowerCase();
      results.replaceChildren();
      if (query.length < 2) return;
      results.textContent = "Searching…";
      try {
        indexPromise ||= fetch(indexUrl).then((response) => {
          if (!response.ok) throw new Error("Search index unavailable");
          return response.json();
        });
        const index = await indexPromise;
        if (search.value.trim().toLowerCase() !== query) return;
        const terms = query.split(/\s+/);
        const matches = index
          .filter((item) =>
            terms.every((term) =>
              `${item.title} ${item.file} ${item.text}`
                .toLowerCase()
                .includes(term),
            ),
          )
          .sort(
            (a, b) =>
              Number(b.title.toLowerCase().includes(query)) -
              Number(a.title.toLowerCase().includes(query)),
          )
          .slice(0, 8);
        results.replaceChildren();
        for (const item of matches) {
          const link = document.createElement("a");
          link.href = new URL(item.file, indexUrl).href;
          link.textContent = item.title;
          const detail = document.createElement("small");
          detail.textContent = item.file.replace(/\.html$/, "");
          link.append(detail);
          results.append(link);
        }
        if (!matches.length) results.textContent = "No matching guides.";
      } catch {
        indexPromise = undefined;
        if (search.value.trim().toLowerCase() === query)
          results.textContent =
            "Search is unavailable. Browse the guides below.";
      }
    }, 150);
  });
}

const diagrams = [...document.querySelectorAll("code.language-mermaid")];
if (diagrams.length) {
  try {
    const { default: mermaid } = await import("mermaid");
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: "neutral",
    });
    for (const [index, code] of diagrams.entries()) {
      const { svg } = await mermaid.render(
        `diagram-${index}`,
        code.textContent,
      );
      const figure = document.createElement("div");
      figure.className = "diagram";
      figure.innerHTML = svg;
      code.parentElement.replaceWith(figure);
    }
  } catch {
    /* Keep the diagram source readable when rendering is unavailable. */
  }
}

if (document.querySelector("[data-download]")) {
  const os = document.querySelector("#download-os");
  const arch = document.querySelector("#download-arch");
  const archField = document.querySelector("#arch-field");
  const note = document.querySelector("#detection-note");
  const status = document.querySelector("#release-status");
  const options = document.querySelector("#download-options");
  const detected = detectOS(navigator);
  os.value = detected || "java";
  let assets = [];
  let loaded = false;
  let failed = false;
  function render() {
    archField.hidden = os.value !== "macos";
    note.textContent =
      os.value === "macos"
        ? "Choose Apple silicon or Intel; browsers cannot reliably detect Mac architecture."
        : os.value === "java"
          ? "Requires a local Java 25 installation. Choose a desktop OS for bundled-runtime packages."
          : `${os.value === "windows" ? "Windows" : "Linux"} packages are built for x64. You can change the operating system above.`;
    options.replaceChildren();
    if (failed) {
      const fallback = document.createElement("a");
      fallback.href = releasesUrl;
      fallback.textContent = "Download from GitHub Releases ↗";
      options.append(fallback);
      return;
    }
    if (!loaded) return;
    const selected = selectAssets(assets, os.value, arch.value);
    for (const asset of selected) {
      const row = document.createElement("div");
      row.className = "download-asset";
      const link = document.createElement("a");
      link.href = asset.browser_download_url;
      link.textContent = `${asset.label} ↓`;
      const size = document.createElement("small");
      size.textContent = `${(asset.size / 1024 / 1024).toFixed(1)} MB`;
      row.append(link, size);
      options.append(row);
    }
    if (!selected.length)
      options.textContent =
        os.value === "macos" && !arch.value
          ? "Select your Mac architecture to see downloads."
          : "No matching package is attached to the latest stable release. Check all releases below.";
    const checksum = assets.find((asset) => asset.name === "SHA256SUMS");
    if (checksum && selected.length) {
      const link = document.createElement("a");
      link.href = checksum.browser_download_url;
      link.textContent = "SHA-256 checksums ↗";
      link.className = "small";
      options.append(link);
    }
  }
  os.addEventListener("change", render);
  arch.addEventListener("change", render);
  render();
  try {
    const response = await fetch(latestReleaseApi, {
      signal: AbortSignal.timeout(10000),
      headers: { Accept: "application/vnd.github+json" },
    });
    if (!response.ok) throw new Error("Release lookup failed");
    const release = await response.json();
    assets = releaseAssets(release);
    loaded = true;
    status.textContent = `Latest stable release: ${release.tag_name}`;
    render();
  } catch {
    failed = true;
    status.textContent = "Release details are unavailable right now.";
    render();
  }
}
