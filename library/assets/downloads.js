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

import { stableVersion } from "./releases.js";

export const releasesUrl = "https://github.com/ketraterm/KetraTerm/releases";

// Asset names are the publication contract in publish-binaries.yml.
const packages = [
  [
    "windows",
    "x64",
    "KetraTerm-Windows-Setup-x64.exe",
    "Windows installer · x64",
  ],
  [
    "windows",
    "x64",
    "KetraTerm-Windows-Portable-x64.zip",
    "Windows portable · x64",
  ],
  [
    "macos",
    "arm64",
    "KetraTerm-macOS-Apple-Silicon.dmg",
    "macOS installer · Apple silicon",
  ],
  [
    "macos",
    "arm64",
    "KetraTerm-macOS-Apple-Silicon-Portable.zip",
    "macOS portable · Apple silicon",
  ],
  ["macos", "x64", "KetraTerm-macOS-Intel.dmg", "macOS installer · Intel"],
  [
    "macos",
    "x64",
    "KetraTerm-macOS-Intel-Portable.zip",
    "macOS portable · Intel",
  ],
  ["linux", "x64", "KetraTerm-Linux-x64.deb", "Debian / Ubuntu · x64"],
  ["linux", "x64", "KetraTerm-Linux-x64.rpm", "RPM package · x64"],
  ["linux", "x64", "KetraTerm-Linux-x64.tar.gz", "Linux portable · x64"],
  ["java", "", "KetraTerm-Java-Preinstalled.zip", "Archive · Java 25 required"],
];

export function detectOS({
  userAgentData,
  platform = "",
  userAgent = "",
  maxTouchPoints = 0,
}) {
  const name = userAgentData?.platform || platform;
  // Mobile browsers can report a desktop platform; don't recommend a desktop binary.
  if (
    /Android|iPhone|iPad/i.test(userAgent) ||
    (/Mac/i.test(name) && maxTouchPoints > 1)
  )
    return null;
  if (/Win/i.test(name)) return "windows";
  if (/Mac/i.test(name)) return "macos";
  if (/Linux/i.test(name) && !/CrOS/i.test(userAgent)) return "linux";
  return null;
}

export function validAsset(asset) {
  if (
    !asset ||
    typeof asset.name !== "string" ||
    !Number.isFinite(asset.size) ||
    asset.size < 0
  )
    return false;
  try {
    const url = new URL(asset.browser_download_url);
    return (
      url.origin === "https://github.com" &&
      !url.username &&
      !url.password &&
      url.pathname.startsWith("/ketraterm/KetraTerm/releases/download/") &&
      !url.search &&
      !url.hash
    );
  } catch {
    return false;
  }
}

export function selectAssets(assets, os, arch) {
  return packages
    .filter(
      ([platform, architecture]) =>
        platform === os && (os !== "macos" || architecture === arch),
    )
    .flatMap(([, , name, label]) => {
      const asset = assets.find(
        (item) => item.name === name && validAsset(item),
      );
      return asset ? [{ ...asset, label }] : [];
    });
}

export function releaseAssets(release) {
  if (
    release?.draft ||
    release?.prerelease ||
    !stableVersion(release?.tag_name) ||
    !Array.isArray(release?.assets)
  ) {
    throw new Error("No stable release metadata available");
  }
  return release.assets.filter(validAsset);
}
