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
package io.github.jvterm.host

/**
 * Host-facing safety limits for host-owned metadata.
 *
 * These limits prevent untrusted terminal output from retaining unbounded host
 * metadata while still allowing normal shell and TUI OSC 8 hyperlink usage.
 *
 * @property maxHyperlinkEntries maximum distinct OSC 8 hyperlink keys retained
 * by the adapter before least-recently-used entries are evicted.
 * @property maxHyperlinkUriLength maximum accepted OSC 8 URI length in UTF-16
 * code units. Longer URIs are ignored and mapped to no active hyperlink.
 * @property maxHyperlinkIdLength maximum accepted OSC 8 `id=` parameter length
 * in UTF-16 code units. Longer IDs are ignored and mapped to no active
 * hyperlink.
 * @property maxNotificationTitleLength maximum accepted character length for desktop notification titles.
 * @property maxNotificationBodyLength maximum accepted character length for desktop notification bodies.
 */
data class HostPolicy(
    val maxHyperlinkEntries: Int = 4096,
    val maxHyperlinkUriLength: Int = 4096,
    val maxHyperlinkIdLength: Int = 256,
    val maxNotificationTitleLength: Int = 256,
    val maxNotificationBodyLength: Int = 1024,
) {
    init {
        require(maxHyperlinkEntries > 0) {
            "maxHyperlinkEntries must be positive, got $maxHyperlinkEntries"
        }
        require(maxHyperlinkUriLength >= 0) {
            "maxHyperlinkUriLength must be non-negative, got $maxHyperlinkUriLength"
        }
        require(maxHyperlinkIdLength >= 0) {
            "maxHyperlinkIdLength must be non-negative, got $maxHyperlinkIdLength"
        }
        require(maxNotificationTitleLength >= 0) {
            "maxNotificationTitleLength must be non-negative, got $maxNotificationTitleLength"
        }
        require(maxNotificationBodyLength >= 0) {
            "maxNotificationBodyLength must be non-negative, got $maxNotificationBodyLength"
        }
    }
}
