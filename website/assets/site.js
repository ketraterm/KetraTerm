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
const navigation = document.querySelectorAll(
  ".nav-disclosure, .site-navigation",
);
if (navigation.length) {
  const mobile = matchMedia("(max-width: 800px)");
  const updateNavigation = () => {
    for (const item of navigation) item.open = !mobile.matches;
  };
  updateNavigation();
  mobile.addEventListener("change", updateNavigation);
  for (const item of navigation)
    if (item.classList.contains("site-navigation")) {
      item.addEventListener("keydown", (event) => {
        if (mobile.matches && event.key === "Escape") {
          item.open = false;
          item.querySelector("summary").focus();
        }
      });
      document.addEventListener("pointerdown", (event) => {
        if (mobile.matches && item.open && !item.contains(event.target))
          item.open = false;
      });
    }
}
const outline = document.querySelector(".page-outline");
if (outline) {
  const compact = matchMedia("(max-width: 1150px)");
  const updateOutline = () => (outline.open = !compact.matches);
  updateOutline();
  compact.addEventListener("change", updateOutline);
}
try {
  const saved = localStorage.getItem("ketraterm-theme");
  if (saved === "light" || saved === "dark")
    document.documentElement.dataset.theme = saved;
} catch {
  /* Storage is optional in private browsing. */
}
const updateThemeButton = () => {
  if (!themeButton) return;
  const light = document.documentElement.dataset.theme === "light";
  themeButton.textContent = light ? "Dark" : "Light";
  themeButton.setAttribute(
    "aria-label",
    `Switch to ${light ? "dark" : "light"} theme`,
  );
};
updateThemeButton();
themeButton?.addEventListener("click", () => {
  const dark = document.documentElement.dataset.theme !== "light";
  const theme = dark ? "light" : "dark";
  document.documentElement.dataset.theme = theme;
  updateThemeButton();
  try {
    localStorage.setItem("ketraterm-theme", theme);
  } catch {
    /* Keep the in-page choice. */
  }
});

const previewButtons = [...document.querySelectorAll("[data-preview-switch]")];
const terminalShot = document.querySelector("#terminal-shot");
const terminalCaption = document.querySelector("#terminal-caption");
if (terminalShot && terminalCaption)
  for (const button of previewButtons)
    button.addEventListener("click", () => {
      terminalShot.src = button.dataset.src;
      terminalShot.alt = button.dataset.caption;
      terminalCaption.textContent = button.dataset.caption;
      for (const item of previewButtons)
        item.setAttribute("aria-pressed", String(item === button));
    });

const entryButtons = [...document.querySelectorAll("[data-library-entry]")];
const libraryDependency = document.querySelector("#library-dependency");
const libraryEntryLink = document.querySelector("#library-entry-link");
if (libraryDependency && libraryEntryLink)
  for (const button of entryButtons)
    button.addEventListener("click", () => {
      libraryDependency.textContent = `ketraterm-${button.dataset.libraryEntry}`;
      libraryEntryLink.href = button.dataset.guide;
      for (const item of entryButtons)
        item.setAttribute("aria-pressed", String(item === button));
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
    button.setAttribute("aria-live", "polite");
    button.addEventListener("click", async () => {
      let feedback;
      try {
        await navigator.clipboard.writeText(code.textContent);
        feedback = "Copied";
      } catch {
        feedback = "Select text to copy";
      }
      button.textContent = feedback;
      button.setAttribute("aria-label", feedback);
      setTimeout(() => {
        button.textContent = "Copy";
        button.setAttribute("aria-label", "Copy code");
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
              `${item.title} ${item.context || ""} ${item.file} ${item.text}`
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
          detail.textContent = item.context || "Guide";
          link.append(detail);
          if (item.text) {
            const text = item.text.trim();
            let start = Math.max(0, text.toLowerCase().indexOf(terms[0]) - 40);
            if (start) start = text.indexOf(" ", start) + 1;
            const excerpt = document.createElement("span");
            excerpt.className = "search-excerpt";
            excerpt.textContent =
              (start ? "…" : "") +
              text.slice(start, start + 140).trim() +
              (text.length > start + 140 ? "…" : "");
            link.append(excerpt);
          }
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

const downloadRoot = document.querySelector("[data-download]");
if (downloadRoot) {
  const compact = downloadRoot.dataset.download === "compact";
  const os = document.querySelector("#download-os");
  const arch = document.querySelector("#download-arch");
  const archField = document.querySelector("#arch-field");
  const note = document.querySelector("#detection-note");
  const status = document.querySelector("#release-status");
  const options = document.querySelector("#download-options");
  const detected = detectOS(navigator);
  os.value = detected || "";
  let assets = [];
  let loaded = false;
  let failed = false;
  function render() {
    const platform = {
      windows: "Windows",
      macos: "macOS",
      linux: "Linux",
    }[os.value];
    archField.hidden = os.value !== "macos";
    note.textContent = !os.value
      ? "Select the computer you’ll use KetraTerm on."
      : os.value === "macos"
        ? "Choose the chip in your Mac: Apple silicon or Intel."
        : os.value === "java"
          ? "Requires a local Java 25 installation. Choose a desktop OS for bundled-runtime packages."
          : os.value === detected
            ? `Detected ${platform}. Choose another OS for a different computer.`
            : `Packages for ${platform} x64.`;
    options.replaceChildren();
    if (failed) {
      const primary = document.createElement("div");
      primary.className = "download-primary";
      const fallback = document.createElement("a");
      fallback.href = releasesUrl;
      fallback.className = "button download-button download-fallback";
      fallback.textContent = "Download from GitHub Releases ↗";
      primary.append(fallback);
      options.append(primary);
      return;
    }
    if (!loaded) {
      const pending = document.createElement("button");
      pending.type = "button";
      pending.className = "button download-button";
      pending.disabled = true;
      pending.textContent = "Finding download…";
      options.append(pending);
      return;
    }
    const [recommended, ...alternatives] = selectAssets(
      assets,
      os.value,
      arch.value,
    );
    if (!recommended) {
      options.textContent = !os.value
        ? "Choose an operating system to see downloads."
        : os.value === "macos" && !arch.value
          ? "Select your Mac architecture to see downloads."
          : "No matching package is attached to the latest stable release. Check all releases below.";
      return;
    }
    const primary = document.createElement("div");
    primary.className = "download-primary";
    const download = document.createElement("a");
    download.className = "button download-button";
    download.href = recommended.browser_download_url;
    download.textContent =
      os.value === "java"
        ? "Download Java archive"
        : recommended.label.includes("portable")
          ? `Download portable for ${platform}`
          : os.value === "linux"
            ? `Download ${recommended.name.endsWith(".deb") ? ".deb" : ".rpm"} for Linux`
            : `Download for ${platform}`;
    const meta = document.createElement("p");
    meta.className = "download-meta";
    meta.textContent = `${recommended.label} · ${(recommended.size / 1024 / 1024).toFixed(1)} MB${os.value === "java" ? "" : " · Runtime included"}`;
    primary.append(download, meta);
    options.append(primary);
    if (compact) return;
    if (alternatives.length) {
      const details = document.createElement("details");
      details.className = "download-alternatives";
      const summary = document.createElement("summary");
      summary.textContent = "Other packages";
      details.append(summary);
      for (const asset of alternatives) {
        const row = document.createElement("div");
        row.className = "download-asset";
        const link = document.createElement("a");
        link.href = asset.browser_download_url;
        link.textContent = `${asset.label} ↓`;
        const size = document.createElement("small");
        size.textContent = `${(asset.size / 1024 / 1024).toFixed(1)} MB`;
        row.append(link, size);
        details.append(row);
      }
      options.append(details);
    }
    const checksum = assets.find((asset) => asset.name === "SHA256SUMS");
    if (checksum) {
      const link = document.createElement("a");
      link.href = checksum.browser_download_url;
      link.textContent = "SHA-256 checksums ↗";
      link.className = "checksum-link";
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
