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

import {escape, layout, repository} from "./layout.mjs";

const screenshots = [
  [13, "Workspace", "Lazygit and a build running in split panes"],
  [12, "Monitor", "System monitoring with btop4win"],
  [1, "Text & color", "True color, text styles, and Unicode in the Rich demo"],
  [2, "Commands", "Command status markers beside PowerShell output"],
];

export function pages(versions, guideId, appUrl) {
  const doc = (file) => `guides/${guideId}/${file.replace(/\.md$/, ".html")}`;
  const downloadPanel = (compact) => {
    const controls = `<div class="download-controls">
      <label>Download for<select id="download-os" aria-label="Operating system">
        <option value="" disabled>Choose an OS</option>
        <option value="windows">Windows</option><option value="macos">macOS</option>
        <option value="linux">Linux</option><option value="java">Any OS · bring Java</option>
      </select></label>
      <label id="arch-field" hidden>Architecture<select id="download-arch" aria-label="Architecture">
        <option value="">Choose your Mac</option><option value="arm64">Apple silicon</option><option value="x64">Intel</option>
      </select></label>
    </div>`;
    const options = '<div id="download-options"></div>';
    return `<div class="download-card" data-download="${compact ? "compact" : "full"}">
    ${compact ? options + controls : controls + options}
    <p class="small" id="detection-note">Choose an operating system to see packages.</p>
    <p id="release-status" role="status">Checking release availability…</p>
    <noscript><p><a href="${repository}/releases/latest">Download from GitHub Releases</a>.</p></noscript>
    <div class="download-links">${compact ? '<a href="download.html">Other platforms & packages</a>' : `<a href="${repository}/releases">All releases</a>`}<a href="changelog.html">What’s new</a></div>
  </div>`;
  };
  const app = `<section class="workspace wrap" aria-label="KetraTerm desktop app">
    <div class="app-workspace">
      <div class="app-intro">
        <p class="product-label">For Windows, macOS, and Linux</p>
        <h1>Your shell.<br>More space.</h1>
        <p class="lead">Keep your shells side by side, find past commands, and make the terminal your own.</p>
        ${downloadPanel(true)}
        <a class="guide-link" href="guide.html">Explore the app guide <span aria-hidden="true">↗</span></a>
      </div>
      <figure class="live-preview">
        <div class="preview-screen"><img id="terminal-shot" src="screenshots/${screenshots[0][0]}.png" alt="${screenshots[0][2]}" width="1918" height="1146" fetchpriority="high"></div>
        <div class="preview-switch" role="group" aria-label="Choose an app screenshot">
          ${screenshots
            .slice(0, 3)
            .map(
              ([n, label, caption], i) =>
                `<button type="button" data-preview-switch data-src="screenshots/${n}.png" data-caption="${escape(caption)}" aria-pressed="${i === 0}">${label}</button>`,
            )
            .join("")}
        </div>
        <figcaption id="terminal-caption" aria-live="polite">${screenshots[0][2]}</figcaption>
      </figure>
    </div>
  </section>
  <section class="wrap section app-features">
    <div class="section-heading"><h2>More room to work.</h2><p>Keep the terminal workflow you know.<br>Give it a workspace of its own.</p></div>
    <div class="feature-list">
      <a href="guide.html#shells-and-sessions"><span class="feature-symbol" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="4" width="18" height="16" rx="3"/><path d="M3 9h18M12 9v11"/></svg></span><h3>Tabs. Splits. Your shells.</h3><p>Run sessions side by side. Name your tabs and give them their own accent colors.</p><span class="feature-action">Shells & sessions ↗</span></a>
      <a href="guide.html#commands-and-output"><span class="feature-symbol" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="5" cy="6" r="2"/><circle cx="5" cy="18" r="2"/><path d="M5 8v8M11 6h10M11 12h7M11 18h10"/></svg></span><h3>Find the command. Keep the output.</h3><p>Use command markers and exit status, jump through commands, and copy their output in supported shells.</p><span class="feature-action">Commands & output ↗</span></a>
      <a href="settings.html"><span class="feature-symbol" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 3v4m0 4v10M12 3v10m0 4v4M20 3v4m0 4v10"/><rect x="2" y="7" width="4" height="4" rx="1"/><rect x="10" y="13" width="4" height="4" rx="1"/><rect x="18" y="7" width="4" height="4" rx="1"/></svg></span><h3>Set the tone.</h3><p>Choose your palette, fonts, line height, and cursor. Appearance settings update your open panes.</p><span class="feature-action">Appearance & settings ↗</span></a>
    </div>
  </section>
  <section class="wrap section help-section">
    <div><h2>Ready when you are.</h2><p>Start a session, check shell support, or turn on suggestions.</p></div>
    <div class="link-list">
      <a href="guide.html">Using KetraTerm <span aria-hidden="true">↗</span></a>
      <a href="guide.html#shells-and-sessions">Shell support <span aria-hidden="true">↗</span></a>
      <a href="guide.html#completion">Completion setup <span aria-hidden="true">↗</span></a>
      <a href="changelog.html">App changelog <span aria-hidden="true">↗</span></a>
    </div>
  </section>`;
  const library = `<section class="wrap library-hero">
    <div class="library-heading"><p class="product-label">Kotlin/JVM terminal library</p><h1>A terminal engine.<br>Built for your app.</h1><p class="lead">A modular terminal engine with Swing and headless entry points. You own the transport, host services, and presentation.</p><div class="actions"><a class="button" href="${doc("README.md")}">Get started</a><a class="button secondary" href="versions.html">API reference</a></div></div>
    <div class="code-panel">
      <div class="panel-title"><span>build.gradle.kts</span><span>Kotlin DSL</span></div>
      <div class="entry-switch" role="group" aria-label="Choose a library entry point">
        <button type="button" data-library-entry="swing" data-guide="${doc("ketraterm-swing/README.md")}" aria-pressed="true">Swing view</button><button type="button" data-library-entry="headless" data-guide="${doc("ketraterm-headless/README.md")}" aria-pressed="false">Headless session</button>
      </div>
      <pre><code><span class="code-key">dependencies</span> {
    implementation(platform(
        <span class="code-string">"io.github.ketraterm:ketraterm-bom:{version}"</span>
    ))
    implementation(
        <span class="code-string">"io.github.ketraterm:<span id="library-dependency">ketraterm-swing</span>"</span>
    )
}</code></pre>
      <div class="code-footer"><a id="library-entry-link" href="${doc("ketraterm-swing/README.md")}">Entry point guide ↗</a><a href="${doc("README.md")}#using-the-libraries">Repositories & versions ↗</a></div>
    </div>
  </section>
  <div class="wrap"><div class="runtime-rail"><span>Java 25</span><span>Kotlin 2.4+</span><span>Kotlin & Java APIs</span><span>Apache 2.0</span></div></div>
  <section class="wrap section">
    <div class="section-heading"><h2>Compose the terminal you need.</h2><a href="${doc("modules.md")}">Browse modules ↗</a></div>
    <div class="integration-map">
      <a href="${doc("ketraterm-transport-api/README.md")}"><span class="integration-label">Your transport</span><h3>Ordered bytes in and out.</h3><p>Supply a connector. Add local PTY processes when you need them.</p><code>TerminalConnector</code></a>
      <span class="pipeline-arrow" aria-hidden="true">→</span>
      <a href="${doc("ketraterm-session/README.md")}"><span class="integration-label">Terminal session</span><h3>State, input, and lifecycle.</h3><p>Parse output, maintain grid and history, and serialize terminal input.</p><code>TerminalSession</code></a>
      <span class="pipeline-arrow" aria-hidden="true">→</span>
      <a href="${doc("ketraterm-ui-swing/README.md")}"><span class="integration-label">Your presentation</span><h3>A view that fits your host.</h3><p>Embed Swing or read frames for your own renderer.</p><code>SwingTerminal / frame readers</code></a>
    </div>
    <div class="integration-extras"><span>Add what your host needs.</span><a href="${doc("ketraterm-pty/README.md")}">Local PTY</a><a href="${doc("docs/features/shells.md")}">Shell integration</a><a href="${doc("docs/features/completion.md")}">Completion</a><a href="${doc("docs/library/configuration.md")}">Host services</a></div>
  </section>
  <section class="wrap section help-section">
    <div><h2>Know the contracts.</h2><p>Supported capabilities, customization, and versioned reference material for your integration.</p></div>
    <div class="link-list">
      <a href="${doc("docs/terminal-feature-map.md")}">Terminal features <span aria-hidden="true">↗</span></a>
      <a href="${doc("docs/features/embedding.md")}">Embedding & customization <span aria-hidden="true">↗</span></a>
      <a href="${doc("docs/terminal-feature-gap-map.md")}">Known limitations <span aria-hidden="true">↗</span></a>
      <a href="${doc("docs/library/compatibility.md")}">Compatibility & migrations <span aria-hidden="true">↗</span></a>
      <a href="${doc("CHANGELOG.md")}">Library changelog <span aria-hidden="true">↗</span></a>
    </div>
  </section>`;
  const download = `<section class="wrap download-heading"><div><p class="product-label">KetraTerm desktop</p><h1>Download KetraTerm.</h1><p class="lead">Install it. Open your shell. Make yourself at home.</p></div><img class="download-logo" src="assets/logo.svg" alt="" width="120" height="120"></section>
  <section class="wrap download-section">
    <div class="installer-window">
      <div class="installer-body">${downloadPanel(false)}<aside class="install-steps"><h2>From download to prompt.</h2><ol><li><strong>Install or unpack.</strong><p>Platform packages include the Java runtime. Portable packages run without an installer.</p></li><li><strong>Open KetraTerm.</strong><p>Choose an installed shell from the profile menu.</p></li><li><strong>Make it yours.</strong><p>Pick a theme and font, then open tabs or split panes.</p></li></ol><a href="guide.html">Open the app guide ↗</a></aside></div>
      <div class="workspace-status"><span>Windows x64 · macOS Apple silicon / Intel · Linux x64</span><a href="${repository}/issues">Need help? ↗</a></div>
    </div>
    <div class="download-help"><p>Bringing your own runtime? Choose <strong>Any OS · bring Java</strong>. That archive requires Java 25.</p><a href="guide.html#shells-and-sessions">Check shell support ↗</a></div>
  </section>`;
  const versionRows = versions
    .map(
      (
        v,
      ) => `<tr><th scope="row">${escape(v.label)} ${v.id === "dev" ? "" : `<span class="tag ${v.stable ? "" : "prerelease"}">${v.stable ? "Stable" : "Prerelease"}</span>`}</th>
    <td>${v.guides ? `<a href="guides/${v.id}/docs/README.html">Guides</a> <a href="guides/${v.id}/modules.html">Modules</a> <a href="guides/${v.id}/CHANGELOG.html">Changelog</a>` : `<span class="muted">API only</span>`}</td>
    <td>${v.api ? `<a href="api/${v.id}/index.html">Open API docs <span aria-hidden="true">↗</span></a>` : `<span class="muted">Not generated</span>`}</td></tr>`,
    )
    .join("");
  const versionsPage = `<section class="wrap intro compact"><p class="product-label">Library reference</p><h1>API & versions.</h1><p class="lead">Choose the version that matches your dependency. Development docs follow the current source; release docs stay with their version.</p></section>
  <section class="wrap archive-section"><div class="table-scroll"><table class="version-table"><caption class="visually-hidden">Library documentation by version</caption><thead><tr><th scope="col">Version</th><th scope="col">Guides & modules</th><th scope="col">API reference</th></tr></thead><tbody>${versionRows}</tbody></table></div><p class="muted">Earlier releases contain API docs only. New releases also retain guides, module docs, and changelogs.</p></section>`;
  const gallery = `<section class="wrap intro compact"><p class="product-label">KetraTerm desktop</p><h1>At the prompt.</h1><p class="lead">Real sessions from the project. Appearance varies by platform, version, font, and settings.</p></section><section class="gallery wrap">${screenshots.map(([n, , caption]) => `<figure class="terminal-preview"><div class="panel-title"><span>${caption}</span><a href="screenshots/${n}.png">Full size ↗</a></div><a href="screenshots/${n}.png"><img loading="lazy" src="screenshots/${n}.png" alt="${caption}"></a></figure>`).join("")}</section>`;
  return [
    ["index.html", "Desktop app", app],
    ["library/index.html", "Libraries", library],
    ["download.html", "Download", download],
    ["library/versions.html", "API & versions", versionsPage],
    ["gallery.html", "Screenshots", gallery],
  ].map(([file, title, body]) => ({
    file,
    html: layout({ file, title, body, guideId, appUrl }),
  }));
}
