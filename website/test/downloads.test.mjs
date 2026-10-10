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
import fs from "node:fs";
import {detectOS, releaseAssets, selectAssets, validAsset,} from "../assets/downloads.js";

const asset = (name) => ({
  name,
  size: 1024,
  browser_download_url: `https://github.com/ketraterm/KetraTerm/releases/download/v1.0.0/${name}`,
});

test("detects desktop OS without treating mobile browsers as desktops", () => {
  assert.equal(detectOS({ platform: "Win32" }), "windows");
  assert.equal(detectOS({ platform: "MacIntel" }), "macos");
  assert.equal(detectOS({ platform: "Linux x86_64" }), "linux");
  assert.equal(detectOS({ platform: "Linux", userAgent: "Android" }), null);
  assert.equal(detectOS({ platform: "MacIntel", maxTouchPoints: 5 }), null);
  assert.equal(detectOS({ platform: "Linux", userAgent: "CrOS" }), null);
  assert.equal(
    detectOS({ platform: "", userAgentData: { platform: "Windows" } }),
    "windows",
  );
  assert.equal(detectOS({ platform: "unknown" }), null);
});

test("selects only published packages for the chosen OS and explicit Mac architecture", () => {
  const assets = [
    "KetraTerm-Windows-Setup-x64.exe",
    "KetraTerm-Windows-Portable-x64.zip",
    "KetraTerm-macOS-Intel.dmg",
    "KetraTerm-macOS-Apple-Silicon.dmg",
    "KetraTerm-Linux-x64.deb",
    "KetraTerm-Linux-x64.rpm",
    "KetraTerm-Linux-x64.tar.gz",
    "KetraTerm-Java-Preinstalled.zip",
  ].map(asset);
  assert.equal(selectAssets(assets, "windows", "").length, 2);
  assert.equal(selectAssets(assets, "linux", "").length, 3);
  assert.equal(selectAssets(assets, "java", "").length, 1);
  assert.equal(selectAssets(assets, "macos", "").length, 0);
  assert.equal(
    selectAssets(assets, "macos", "arm64")[0].name,
    "KetraTerm-macOS-Apple-Silicon.dmg",
  );
  assert.equal(
    selectAssets(assets, "macos", "x64")[0].name,
    "KetraTerm-macOS-Intel.dmg",
  );
  assert.deepEqual(selectAssets([], "windows", ""), []);
  const workflow = fs.readFileSync(
    new URL("../../.github/workflows/publish-binaries.yml", import.meta.url),
    "utf8",
  );
  for (const item of assets)
    assert.ok(
      workflow.includes(item.name),
      `${item.name} must match the binary workflow`,
    );
});

test("rejects untrusted download URLs, malformed release data, and prereleases", () => {
  for (const url of [
    "javascript:alert(1)",
    "https://github.com.evil.test/ketraterm/KetraTerm/releases/download/x",
    "https://github.com/other/project/releases/download/x",
    "https://user@github.com/ketraterm/KetraTerm/releases/download/x",
  ]) {
    assert.equal(
      validAsset({ ...asset("test"), browser_download_url: url }),
      false,
    );
  }
  for (const release of [
    {},
    null,
    { tag_name: "v1", assets: [], prerelease: true },
    { tag_name: "v1", assets: [], draft: true },
    { tag_name: "v1.0.0-alpha01", assets: [], prerelease: false },
    { tag_name: "v1.0.0-rc.1", assets: [], prerelease: false },
    { tag_name: "v1.0.0-preview", assets: [], prerelease: false },
  ])
    assert.throws(() => releaseAssets(release));
  assert.deepEqual(
    releaseAssets({ tag_name: "v1.0.0", assets: [asset("test"), null] }),
    [asset("test")],
  );
});
