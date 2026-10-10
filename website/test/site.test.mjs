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

import assert from "node:assert/strict";
import test from "node:test";
import { parseHTML } from "linkedom";
import { pages } from "../src/pages.mjs";

let siteImport = 0;

async function loadSite(t, html, platform = "Win32", release, browser = {}) {
  const { document, Event } = parseHTML(html);
  // Linkedom models select.value as read-only; the browser allows assignments.
  for (const select of document.querySelectorAll("select"))
    Object.defineProperty(select, "value", { value: "", writable: true });
  for (const [name, value] of Object.entries({
    document,
    navigator: { platform, ...browser.navigator },
    location: {
      href: "https://example.test/library/guides/v1.0.0/docs/README.html",
    },
    matchMedia:
      browser.matchMedia || (() => ({ matches: false, addEventListener() {} })),
  })) {
    const original = Object.getOwnPropertyDescriptor(globalThis, name);
    Object.defineProperty(globalThis, name, { value, configurable: true });
    t.after(() => {
      if (original) Object.defineProperty(globalThis, name, original);
      else delete globalThis[name];
    });
  }
  t.mock.method(globalThis, "fetch", async () => {
    if (release) return { ok: true, json: async () => release };
    throw new Error("Offline");
  });
  await import(`../assets/site.js?test=${++siteImport}`);
  return {
    document,
    Event,
    choose(id, value) {
      const select = document.getElementById(id);
      select.value = value;
      select.dispatchEvent(new Event("change"));
    },
  };
}

const downloadPage = () =>
  pages([], "dev", "https://example.invalid").find(
    (page) => page.file === "download.html",
  ).html;

const asset = (name) => ({
  name,
  size: 10485760,
  browser_download_url: `https://github.com/ketraterm/KetraTerm/releases/download/v0.3.0/${name}`,
});

test("download fallback survives OS and architecture changes after lookup failure", async (t) => {
  const { document, choose } = await loadSite(t, downloadPage());
  const themeButton = document.querySelector(".theme-toggle");
  assert.equal(themeButton.getAttribute("aria-label"), "Switch to light theme");
  themeButton.click();
  assert.equal(document.documentElement.dataset.theme, "light");
  assert.equal(themeButton.getAttribute("aria-label"), "Switch to dark theme");
  themeButton.click();
  assert.equal(document.documentElement.dataset.theme, "dark");
  const assertFallback = () => {
    assert.equal(
      document.querySelector("#download-options a")?.href,
      "https://github.com/ketraterm/KetraTerm/releases",
    );
    assert.match(
      document.querySelector("#release-status").textContent,
      /unavailable/,
    );
    assert.equal(
      document.querySelector(".download-primary .download-button")?.textContent,
      "Download from GitHub Releases ↗",
    );
  };
  assertFallback();
  for (const [id, value] of [
    ["download-os", "macos"],
    ["download-arch", "arm64"],
    ["download-os", "linux"],
  ]) {
    choose(id, value);
    assertFallback();
  }
});

test("downloads recommend the installer, keep alternatives secondary, and follow explicit platform choices", async (t) => {
  const release = {
    tag_name: "v0.3.0",
    assets: [
      "KetraTerm-Windows-Portable-x64.zip",
      "KetraTerm-Linux-x64.rpm",
      "KetraTerm-macOS-Intel.dmg",
      "KetraTerm-Java-Preinstalled.zip",
      "SHA256SUMS",
      "KetraTerm-macOS-Apple-Silicon-Portable.zip",
      "KetraTerm-Windows-Setup-x64.exe",
      "KetraTerm-Linux-x64.tar.gz",
      "KetraTerm-macOS-Apple-Silicon.dmg",
      "KetraTerm-Linux-x64.deb",
    ].map(asset),
  };
  const { document, choose } = await loadSite(
    t,
    downloadPage(),
    "Win32",
    release,
  );
  const primary = () => document.querySelector(".download-button");
  const alternatives = () =>
    [
      ...document.querySelectorAll(".download-alternatives .download-asset a"),
    ].map((link) => link.href);
  assert.equal(primary().textContent, "Download for Windows");
  assert.equal(
    primary().href,
    asset("KetraTerm-Windows-Setup-x64.exe").browser_download_url,
  );
  assert.deepEqual(alternatives(), [
    asset("KetraTerm-Windows-Portable-x64.zip").browser_download_url,
  ]);
  assert.equal(
    document.querySelector(".download-alternatives summary").textContent,
    "Other packages",
  );
  assert.equal(
    document.querySelector(".download-alternatives").hasAttribute("open"),
    false,
  );
  assert.equal(
    document.querySelector(".download-meta").textContent,
    "Windows installer · x64 · 10.0 MB · Runtime included",
  );
  assert.equal(
    document.querySelector(".download-alternatives .checksum-link"),
    null,
  );
  assert.equal(
    document.querySelector("#download-options > .checksum-link").href,
    asset("SHA256SUMS").browser_download_url,
  );
  assert.equal(
    document.querySelector("#release-status").textContent,
    "Latest stable release: v0.3.0",
  );
  assert.equal(
    document.querySelector("#detection-note").textContent,
    "Detected Windows. Choose another OS for a different computer.",
  );

  choose("download-os", "macos");
  assert.equal(
    document.querySelector("#detection-note").textContent,
    "Choose the chip in your Mac: Apple silicon or Intel.",
  );
  assert.equal(document.querySelector("#arch-field").hidden, false);
  assert.equal(primary(), null);
  assert.match(
    document.querySelector("#download-options").textContent,
    /Select your Mac architecture/,
  );
  choose("download-arch", "arm64");
  assert.equal(primary().textContent, "Download for macOS");
  assert.equal(
    primary().href,
    asset("KetraTerm-macOS-Apple-Silicon.dmg").browser_download_url,
  );
  assert.deepEqual(alternatives(), [
    asset("KetraTerm-macOS-Apple-Silicon-Portable.zip").browser_download_url,
  ]);
  choose("download-arch", "x64");
  assert.equal(
    primary().href,
    asset("KetraTerm-macOS-Intel.dmg").browser_download_url,
  );
  assert.equal(document.querySelector(".download-alternatives"), null);

  choose("download-os", "linux");
  assert.equal(
    document.querySelector("#detection-note").textContent,
    "Packages for Linux x64.",
  );
  assert.equal(document.querySelector("#arch-field").hidden, true);
  assert.equal(primary().textContent, "Download .deb for Linux");
  assert.equal(
    primary().href,
    asset("KetraTerm-Linux-x64.deb").browser_download_url,
  );
  assert.deepEqual(alternatives(), [
    asset("KetraTerm-Linux-x64.rpm").browser_download_url,
    asset("KetraTerm-Linux-x64.tar.gz").browser_download_url,
  ]);
  choose("download-os", "java");
  assert.equal(primary().textContent, "Download Java archive");
  assert.equal(
    primary().href,
    asset("KetraTerm-Java-Preinstalled.zip").browser_download_url,
  );
  assert.match(
    document.querySelector(".download-meta").textContent,
    /Java 25 required/,
  );
  assert.doesNotMatch(
    document.querySelector(".download-meta").textContent,
    /Runtime included/,
  );
  assert.equal(document.querySelector(".download-alternatives"), null);
});

test("the app homepage shows only the recommended package and retains platform controls", async (t) => {
  const home = pages([], "dev", "https://example.invalid").find(
    (page) => page.file === "index.html",
  ).html;
  const { document, choose } = await loadSite(t, home, "Win32", {
    tag_name: "v0.3.0",
    assets: [
      "KetraTerm-Windows-Setup-x64.exe",
      "KetraTerm-Windows-Portable-x64.zip",
      "KetraTerm-macOS-Apple-Silicon.dmg",
      "KetraTerm-macOS-Apple-Silicon-Portable.zip",
      "SHA256SUMS",
    ].map(asset),
  });
  assert.equal(
    document.querySelector("[data-download]").dataset.download,
    "compact",
  );
  assert.equal(
    document.querySelector(".download-button").textContent,
    "Download for Windows",
  );
  assert.equal(
    document.querySelector(".download-meta").textContent,
    "Windows installer · x64 · 10.0 MB · Runtime included",
  );
  assert.equal(document.querySelector(".download-alternatives"), null);
  assert.equal(document.querySelector(".checksum-link"), null);
  choose("download-os", "macos");
  assert.equal(document.querySelector("#arch-field").hidden, false);
  assert.equal(document.querySelector(".download-button"), null);
  choose("download-arch", "arm64");
  assert.equal(
    document.querySelector(".download-button").href,
    asset("KetraTerm-macOS-Apple-Silicon.dmg").browser_download_url,
  );
  assert.equal(document.querySelector(".download-alternatives"), null);
  assert.equal(document.querySelector(".checksum-link"), null);
});

test("a portable-only release is labeled honestly and missing packages produce no guessed links", async (t) => {
  const { document, choose } = await loadSite(t, downloadPage(), "Win32", {
    tag_name: "v0.3.0",
    assets: [asset("KetraTerm-Windows-Portable-x64.zip")],
  });
  assert.equal(
    document.querySelector(".download-button").textContent,
    "Download portable for Windows",
  );
  assert.equal(document.querySelector(".download-alternatives"), null);
  choose("download-os", "linux");
  assert.equal(document.querySelector("#download-options a"), null);
  assert.match(
    document.querySelector("#download-options").textContent,
    /No matching package/,
  );
});

test("an unknown browser platform asks for the target computer instead of recommending a Java archive", async (t) => {
  const { document, choose } = await loadSite(t, downloadPage(), "", {
    tag_name: "v0.3.0",
    assets: [
      asset("KetraTerm-Windows-Setup-x64.exe"),
      asset("KetraTerm-Java-Preinstalled.zip"),
    ],
  });
  assert.equal(document.querySelector("#download-os").value, "");
  assert.equal(document.querySelector(".download-button"), null);
  assert.equal(
    document.querySelector("#detection-note").textContent,
    "Select the computer you’ll use KetraTerm on.",
  );
  assert.equal(
    document.querySelector("#download-options").textContent,
    "Choose an operating system to see downloads.",
  );
  choose("download-os", "windows");
  assert.equal(
    document.querySelector(".download-button").href,
    asset("KetraTerm-Windows-Setup-x64.exe").browser_download_url,
  );
  assert.equal(
    document.querySelector(".download-button").textContent,
    "Download for Windows",
  );
  assert.equal(
    document.querySelector("#detection-note").textContent,
    "Packages for Windows x64.",
  );
});

test("the Linux primary action identifies its package format when only RPM is available", async (t) => {
  const { document } = await loadSite(t, downloadPage(), "Linux", {
    tag_name: "v0.3.0",
    assets: [
      asset("KetraTerm-Linux-x64.rpm"),
      asset("KetraTerm-Linux-x64.tar.gz"),
    ],
  });
  assert.equal(
    document.querySelector(".download-button").textContent,
    "Download .rpm for Linux",
  );
  assert.equal(
    document.querySelector(".download-button").href,
    asset("KetraTerm-Linux-x64.rpm").browser_download_url,
  );
  assert.equal(
    document.querySelector(".download-alternatives a").href,
    asset("KetraTerm-Linux-x64.tar.gz").browser_download_url,
  );
});

test("mobile navigation dismisses on outside taps and Escape without hiding desktop navigation", async (t) => {
  let change;
  const media = {
    matches: true,
    addEventListener(event, callback) {
      assert.equal(event, "change");
      change = callback;
    },
  };
  const { document, Event } = await loadSite(
    t,
    `<details class="site-navigation" open><summary>Menu</summary><nav><a href="guide.html">Guide</a></nav></details>
    <details class="nav-disclosure" open><summary>Browse guides</summary></details>
    <button type="button" id="outside-menu">Theme</button>`,
    "Win32",
    undefined,
    {
      matchMedia(query) {
        assert.equal(query, "(max-width: 800px)");
        return media;
      },
    },
  );
  const header = document.querySelector(".site-navigation");
  const sidebar = document.querySelector(".nav-disclosure");
  const focus = t.mock.method(
    header.querySelector("summary"),
    "focus",
    () => {},
  );
  assert.equal(header.open, false);
  assert.equal(sidebar.open, false);
  header.open = true;
  sidebar.open = true;
  for (const target of [
    header.querySelector("a"),
    header.querySelector("summary"),
  ]) {
    target.dispatchEvent(new Event("pointerdown", { bubbles: true }));
    assert.equal(header.open, true);
  }
  const outside = document.querySelector("#outside-menu");
  outside.dispatchEvent(new Event("pointerdown", { bubbles: true }));
  assert.equal(header.open, false);
  assert.equal(sidebar.open, true);
  assert.equal(focus.mock.callCount(), 0);
  header.open = true;
  const escape = new Event("keydown", { bubbles: true });
  escape.key = "Escape";
  header.querySelector("a").dispatchEvent(escape);
  assert.equal(header.open, false);
  assert.equal(focus.mock.callCount(), 1);
  media.matches = false;
  change();
  assert.equal(header.open, true);
  assert.equal(sidebar.open, true);
  outside.dispatchEvent(new Event("pointerdown", { bubbles: true }));
  assert.equal(header.open, true);
  header.querySelector("a").dispatchEvent(escape);
  assert.equal(header.open, true);
  assert.equal(focus.mock.callCount(), 1);
  media.matches = true;
  change();
  assert.equal(header.open, false);
  assert.equal(sidebar.open, false);
});

test("copy feedback has the same visible and accessible message for success and failure", async (t) => {
  let denied = false;
  let copied;
  t.mock.method(globalThis, "setTimeout", () => 0);
  const { document } = await loadSite(
    t,
    '<pre><code>implementation("io.github.ketraterm:ketraterm-swing")</code></pre>',
    "Win32",
    undefined,
    {
      navigator: {
        clipboard: {
          async writeText(text) {
            if (denied) throw new Error("Clipboard denied");
            copied = text;
          },
        },
      },
    },
  );
  const button = document.querySelector(".copy-button");
  assert.equal(button.getAttribute("aria-live"), "polite");
  button.click();
  await Promise.resolve();
  assert.equal(copied, 'implementation("io.github.ketraterm:ketraterm-swing")');
  assert.equal(button.textContent, "Copied");
  assert.equal(button.getAttribute("aria-label"), "Copied");
  denied = true;
  button.click();
  await Promise.resolve();
  assert.equal(button.textContent, "Select text to copy");
  assert.equal(button.getAttribute("aria-label"), "Select text to copy");
});

test("the page outline is collapsible on small screens and restores its desktop state", async (t) => {
  let resize;
  const media = {
    matches: true,
    addEventListener(type, listener) {
      assert.equal(type, "change");
      resize = listener;
    },
  };
  const { document } = await loadSite(
    t,
    '<details class="page-outline" open><summary>On this page</summary><nav><a href="#usage">Usage</a></nav></details>',
    "Win32",
    undefined,
    {
      matchMedia(query) {
        assert.equal(query, "(max-width: 1150px)");
        return media;
      },
    },
  );
  const outline = document.querySelector(".page-outline");
  assert.equal(outline.open, false);
  outline.open = true;
  assert.equal(outline.querySelector("a").getAttribute("href"), "#usage");
  media.matches = false;
  resize();
  assert.equal(outline.open, true);
  media.matches = true;
  resize();
  assert.equal(outline.open, false);
});

test("search results retain their documentation version and show readable context and excerpts", async (t) => {
  let searchNow;
  t.mock.method(globalThis, "setTimeout", (callback) => {
    searchNow = callback;
    return 0;
  });
  const { document, Event } = await loadSite(
    t,
    '<label>Find a guide<input type="search" data-doc-search data-index="../search.json"></label><div class="search-results"></div>',
    "Win32",
    [
      {
        title: "Clipboard <options>",
        file: "ketraterm-ui-swing/README.html",
        context: "Module guide · ketraterm-ui-swing",
        text: "Configure primary selection and clipboard gestures for your host.",
      },
      {
        title: "Historical clipboard",
        file: "docs/clipboard.html",
        text: "Primary selection support.",
      },
    ],
  );
  const search = document.querySelector("input");
  search.value = "primary selection";
  search.dispatchEvent(new Event("input"));
  await searchNow();
  const links = document.querySelectorAll(".search-results a");
  assert.equal(links.length, 2);
  assert.equal(
    links[0].href,
    "https://example.test/library/guides/v1.0.0/ketraterm-ui-swing/README.html",
  );
  assert.equal(
    links[0].querySelector("small").textContent,
    "Module guide · ketraterm-ui-swing",
  );
  assert.match(
    links[0].querySelector(".search-excerpt").textContent,
    /primary selection/,
  );
  assert.ok(links[0].textContent.includes("Clipboard <options>"));
  assert.equal(links[0].querySelector("options"), null);
  assert.equal(links[1].querySelector("small").textContent, "Guide");
});

test("preview switches change the real screenshot, description, and selected state", async (t) => {
  const { document } = await loadSite(
    t,
    `<img id="terminal-shot" src="screenshots/12.png" alt="System monitoring"><p id="terminal-caption">System monitoring</p>
    <button data-preview-switch data-src="screenshots/12.png" data-caption="System monitoring" aria-pressed="true">Monitor</button>
    <button data-preview-switch data-src="screenshots/13.png" data-caption="Lazygit and a build in split panes" aria-pressed="false">Workspace</button>`,
  );
  const [monitor, workspace] = document.querySelectorAll(
    "[data-preview-switch]",
  );
  workspace.click();
  assert.equal(
    document.querySelector("#terminal-shot").src,
    "screenshots/13.png",
  );
  assert.equal(
    document.querySelector("#terminal-shot").alt,
    "Lazygit and a build in split panes",
  );
  assert.equal(
    document.querySelector("#terminal-caption").textContent,
    "Lazygit and a build in split panes",
  );
  assert.equal(workspace.getAttribute("aria-pressed"), "true");
  assert.equal(monitor.getAttribute("aria-pressed"), "false");
  monitor.click();
  assert.equal(
    document.querySelector("#terminal-shot").src,
    "screenshots/12.png",
  );
  assert.equal(
    document.querySelector("#terminal-caption").textContent,
    "System monitoring",
  );
  assert.equal(monitor.getAttribute("aria-pressed"), "true");
  assert.equal(workspace.getAttribute("aria-pressed"), "false");
});

test("library entry switches keep the copied artifact and integration guide aligned", async (t) => {
  const { document } = await loadSite(
    t,
    `<code>implementation("io.github.ketraterm:<span id="library-dependency">ketraterm-swing</span>")</code>
    <a id="library-entry-link" href="guides/dev/ketraterm-swing/README.html">Get started</a>
    <button data-library-entry="swing" data-guide="guides/dev/ketraterm-swing/README.html" aria-pressed="true">Swing</button>
    <button data-library-entry="headless" data-guide="guides/dev/ketraterm-headless/README.html" aria-pressed="false">Headless</button>`,
  );
  const [swing, headless] = document.querySelectorAll("[data-library-entry]");
  headless.click();
  assert.equal(
    document.querySelector("code").textContent,
    'implementation("io.github.ketraterm:ketraterm-headless")',
  );
  assert.equal(
    document.querySelector("#library-entry-link").href,
    "guides/dev/ketraterm-headless/README.html",
  );
  assert.equal(headless.getAttribute("aria-pressed"), "true");
  assert.equal(swing.getAttribute("aria-pressed"), "false");
  swing.click();
  assert.equal(
    document.querySelector("#library-dependency").textContent,
    "ketraterm-swing",
  );
  assert.equal(
    document.querySelector("#library-entry-link").href,
    "guides/dev/ketraterm-swing/README.html",
  );
  assert.equal(swing.getAttribute("aria-pressed"), "true");
  assert.equal(headless.getAttribute("aria-pressed"), "false");
});
