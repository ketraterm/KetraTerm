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
import {parseHTML} from "linkedom";
import {pages} from "../src/pages.mjs";

test("download fallback survives OS and architecture changes after lookup failure", async (t) => {
  const { document, Event } = parseHTML(
    pages([], "dev", "https://example.invalid").find(
      (page) => page.file === "download.html",
    ).html,
  );
  // Linkedom models select.value as read-only; the browser allows assignments.
  for (const select of document.querySelectorAll("select"))
    Object.defineProperty(select, "value", { value: "", writable: true });
  for (const [name, value] of Object.entries({
    document,
    navigator: { platform: "Win32" },
  })) {
    const original = Object.getOwnPropertyDescriptor(globalThis, name);
    Object.defineProperty(globalThis, name, { value, configurable: true });
    t.after(() => {
      if (original) Object.defineProperty(globalThis, name, original);
      else delete globalThis[name];
    });
  }
  t.mock.method(globalThis, "fetch", async () => {
    throw new Error("Offline");
  });
  await import("../assets/site.js");
  const assertFallback = () => {
    assert.equal(
      document.querySelector("#download-options a")?.href,
      "https://github.com/ketraterm/KetraTerm/releases",
    );
    assert.match(
      document.querySelector("#release-status").textContent,
      /unavailable/,
    );
  };
  assertFallback();
  for (const [id, value] of [
    ["download-os", "macos"],
    ["download-arch", "arm64"],
    ["download-os", "linux"],
  ]) {
    const select = document.getElementById(id);
    select.value = value;
    select.dispatchEvent(new Event("change"));
    assertFallback();
  }
});
