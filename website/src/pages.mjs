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

import {cards, escape, layout, repository} from "./layout.mjs";

export function pages(versions, guideId, appUrl) {
  const doc = (file) => `guides/${guideId}/${file.replace(/\.md$/, ".html")}`;
  const features = cards([
    [
      "01",
      "Text that holds together",
      "Unicode graphemes, wide characters, font fallback, true color, and five underline styles.",
      "guide.html#commands-and-output",
    ],
    [
      "02",
      "A shell-aware workspace",
      "Command navigation, exit-status markers, and copyable command output.",
      "guide.html#shells-and-sessions",
    ],
    [
      "03",
      "Make it your terminal",
      "Themes, fonts, line height, cursor appearance, and terminal permissions.",
      "guide.html#appearance-and-settings",
    ],
  ]);
  const app = /* HTML */ `<section class="hero wrap" id="download">
      <div class="eyebrow">
        <span class="status-dot"></span> KETRATERM DESKTOP APP
      </div>
      <h1>Your shell.<br /><span>A place to work.</span></h1>
      <div class="hero-bottom">
        <p>
          A desktop terminal for Windows, macOS, and Linux.<br />Local shells,
          tabs, split panes, and room to make it yours.
        </p>
        <div class="actions">
          <a class="button" href="download.html">Get KetraTerm <span>↓</span></a
          ><a class="button secondary" href="gallery.html"
            >See it in use <span>↗</span></a
          >
        </div>
      </div>
    </section>
    <section class="showcase wrap" aria-label="KetraTerm screenshot">
      <div class="screenshot-label">
        <span>THE DESKTOP EXPERIENCE</span><span>Windows · macOS · Linux</span>
      </div>
      <a href="gallery.html"
        ><img
          src="screenshots/12.png"
          alt="KetraTerm running the btop4win system monitor, with CPU, memory, and process views"
          width="1918"
          height="1146"
          fetchpriority="high"
      /></a>
      <div class="screenshot-caption">
        <span>KetraTerm running btop4win.</span
        ><a href="gallery.html">Explore screenshots ↗</a>
      </div>
    </section>
    <section class="section wrap" id="features">
      <div class="section-heading">
        <div>
          <span class="eyebrow">BUILT FOR THE COMMAND LINE</span>
          <h2>The details matter.</h2>
        </div>
        <a class="text-link" href="guide.html#appearance-and-settings"
          >Explore the app guide ↗</a
        >
      </div>
      ${features}
    </section>
    <section class="section wrap split" id="config">
      <div>
        <span class="eyebrow">SETTLE INTO YOUR WORKSPACE</span>
        <h2>Make the app yours.</h2>
        <p>
          Choose shell profiles, tune appearance, and control terminal
          permissions.
        </p>
        <a class="text-link" href="guide.html">App guide ↗</a>
      </div>
      <div class="link-list">
        <a href="guide.html#shells-and-sessions"
          >Shell discovery and integration <span>↗</span></a
        >
        <a href="settings.html">App settings <span>↗</span></a>
        <a href="guide.html#completion"
          >Completion support and setup <span>↗</span></a
        >
        <a href="changelog.html">App changelog <span>↗</span></a>
      </div>
    </section>`;
  const libraryInstall = /* HTML */ `<section
    class="library-band"
    id="integration"
  >
    <div class="wrap split">
      <div>
        <span class="eyebrow">FOR LIBRARY AUTHORS & APPLICATION TEAMS</span>
        <h2>A terminal engine.<br />On your terms.</h2>
        <p>
          Embed a Swing view or run a headless pipeline. Bring your own
          transport, shell model, completion sources, and host services.
        </p>
        <a class="button" href="${doc("README.md")}">Installation guide ↗</a>
      </div>
      <div class="code-panel">
        <div class="code-caption">build.gradle.kts <span>Kotlin DSL</span></div>
        <pre><code><span class="code-key">dependencies</span> {
    implementation(platform(
        <span class="code-string">"io.github.ketraterm:ketraterm-bom:{version}"</span>
    ))
    implementation(
        <span class="code-string">"io.github.ketraterm:ketraterm-swing"</span>
    )
}</code></pre>
        <p>Java 25 · Kotlin 2.4+ · Java APIs</p>
      </div>
    </div>
  </section>`;
  const library = /* HTML */ `<section class="page-hero wrap">
      <span class="eyebrow">KETRATERM LIBRARIES</span>
      <h1>Bring the terminal<br />to your application.</h1>
      <p>Independent modules. Explicit ownership. Kotlin and Java APIs.</p>
      <div class="actions">
        <a class="button" href="${doc("ketraterm-swing/README.md")}"
          >Embed a Swing terminal ↗</a
        ><a
          class="button secondary"
          href="${doc("ketraterm-headless/README.md")}"
          >Start headless ↗</a
        >
      </div>
    </section>
    <section class="wrap section compact" id="modular">
      ${cards([
        [
          "01",
          "Headless pipeline",
          "Parse output, maintain grid and history, encode input, and coordinate a session without a UI.",
          doc("ketraterm-headless/README.md"),
        ],
        [
          "02",
          "Swing terminal",
          "Rendering, selection, search, links, and input collection for a host-owned session.",
          doc("ketraterm-ui-swing/README.md"),
        ],
        [
          "03",
          "Optional integrations",
          "Local PTY processes, shell metadata, completion sources, and reusable host controls.",
          doc("docs/features/embedding.md"),
        ],
      ])}
    </section>
    ${libraryInstall}
    <section class="wrap section split" id="architecture">
      <div>
        <span class="eyebrow">START SMALL</span>
        <h2>A clear dependency boundary.</h2>
        <p>
          The BOM aligns library versions. The headless and Swing entry points
          select a coherent set of modules. PTY hosting and completion remain
          optional.
        </p>
        <a class="text-link" href="${doc("README.md")}"
          >Installation and examples ↗</a
        >
      </div>
      <div class="link-list">
        ${[
          ["Module guides", doc("modules.md")],
          [
            "Configuration and host services",
            doc("docs/library/configuration.md"),
          ],
          [
            "Compatibility and migrations",
            doc("docs/library/compatibility.md"),
          ],
          ["Versioned API reference", "versions.html"],
          ["Library changelog", doc("CHANGELOG.md")],
          ["Current limitations", doc("docs/terminal-feature-gap-map.md")],
        ]
          .map(([t, u]) => `<a href="${u}">${t}<span>↗</span></a>`)
          .join("")}
      </div>
    </section>`;
  const download = /* HTML */ `<section class="page-hero wrap">
      <span class="eyebrow">KETRATERM DESKTOP</span>
      <h1>Make yourself<br />at home.</h1>
      <p>
        Choose an installer or a portable archive. Downloads come directly from
        GitHub Releases.
      </p>
    </section>
    <section class="wrap download-layout">
      <div class="download-card" data-download>
        <div class="eyebrow">LATEST STABLE RELEASE</div>
        <h2>Download KetraTerm</h2>
        <p id="release-status" role="status">Checking release availability…</p>
        <div class="download-controls">
          <label
            >Operating system<select
              id="download-os"
              aria-label="Operating system"
            >
              <option value="windows">Windows</option>
              <option value="macos">macOS</option>
              <option value="linux">Linux</option>
              <option value="java">Any OS · bring Java</option>
            </select></label
          ><label id="arch-field"
            >Architecture<select id="download-arch" aria-label="Architecture">
              <option value="">Choose your Mac</option>
              <option value="arm64">Apple silicon</option>
              <option value="x64">Intel</option>
            </select></label
          >
        </div>
        <p class="small" id="detection-note">
          You can change the detected operating system.
        </p>
        <div id="download-options"></div>
        <noscript
          ><p>
            JavaScript enables OS detection and asset selection.
            <a href="${repository}/releases/latest"
              >Download from GitHub Releases</a
            >.
          </p></noscript
        ><a class="text-link" href="${repository}/releases"
          >All releases and checksums ↗</a
        >
      </div>
      <div class="download-notes">
        <span class="eyebrow">BEFORE YOU START</span>
        <h3>Your shell, ready to go.</h3>
        <p>
          Use your installed shell. KetraTerm discovers local profiles and adds
          prompt hooks to supported interactive launches.
        </p>
        <a href="guide.html#shells-and-sessions">Shell support ↗</a>
        <h3>Prefer your own runtime?</h3>
        <p>
          The Java-preinstalled archive requires Java 25. The platform
          installers and portable packages bundle a runtime.
        </p>
      </div>
    </section>
    <section class="section wrap">
      <div class="link-list">
        <a href="guide.html">Using and configuring the app <span>↗</span></a
        ><a href="changelog.html">Application changelog <span>↗</span></a
        ><a href="${repository}/issues">Report a problem <span>↗</span></a>
      </div>
    </section>`;
  const versionRows = versions
    .map(
      (v) =>
        `<tr><th scope="row">${escape(v.label)} ${v.id === "dev" ? "" : `<span class="tag">${v.stable ? "stable" : "prerelease"}</span>`}</th><td>${v.guides ? `<a href="guides/${v.id}/docs/README.html">Guides</a> · <a href="guides/${v.id}/modules.html">Modules</a> · <a href="guides/${v.id}/CHANGELOG.html">Changelog</a>` : "API archive only"}</td><td>${v.api ? `<a href="api/${v.id}/index.html">API reference ↗</a>` : "Not generated"}</td></tr>`,
    )
    .join("");
  const versionsPage = /* HTML */ `<section class="page-hero wrap">
      <span class="eyebrow">DOCUMENTATION ARCHIVE</span>
      <h1>Keep the context.<br />Choose a version.</h1>
      <p>
        Released documentation is retained alongside the development guides.
        Choose the version that matches your dependency.
      </p>
    </section>
    <section class="wrap section compact">
      <div class="table-scroll">
        <table class="version-table">
          <thead>
            <tr>
              <th>Version</th>
              <th>Documentation</th>
              <th>Dokka</th>
            </tr>
          </thead>
          <tbody>
            ${versionRows}
          </tbody>
        </table>
      </div>
      <p class="muted">
        Development docs describe the current checkout. Earlier API-only
        archives may not include Markdown guides. Only versions retained in the
        documentation archive appear here.
      </p>
    </section>`;
  const gallery = /* HTML */ `<section class="page-hero wrap">
      <span class="eyebrow">IN USE</span>
      <h1>A closer look.</h1>
      <p>
        Terminal screenshots from the project. Appearance may vary by platform,
        version, font, and settings.
      </p>
    </section>
    <section class="gallery wrap">
      ${[
        [12, "System monitoring with btop4win"],
        [1, "True color, text styles, and Unicode in the Rich demo"],
        [13, "Lazygit and a build running in split panes"],
        [2, "Command status markers beside PowerShell output"],
      ]
        .map(
          ([n, caption]) =>
            `<figure><a href="screenshots/${n}.png"><img loading="lazy" src="screenshots/${n}.png" alt="${caption}"></a><figcaption>${caption} <a href="screenshots/${n}.png">View full size ↗</a></figcaption></figure>`,
        )
        .join("")}
    </section>`;
  return [
    ["index.html", "Desktop app", app],
    ["library/index.html", "Libraries", library],
    ["download.html", "Download", download],
    ["library/versions.html", "Documentation versions", versionsPage],
    ["gallery.html", "Screenshots", gallery],
  ].map(([file, title, body]) => ({
    file,
    html: layout({ file, title, body, guideId, appUrl }),
  }));
}
