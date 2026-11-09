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
package io.github.ketraterm.ui.swing.settings

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertFalse

class TerminalHyperlinkHandlerTest {
    @ParameterizedTest
    @ValueSource(strings = ["https://bad host/", "https://example.test/%", "https://[broken", "https://example.test/\ncontrol", "\u0000"])
    fun `malformed links are rejected without a browser request or an exception`(uri: String) {
        assertFalse(TerminalHyperlinkHandler.SYSTEM.openHyperlink(uri))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "relative/path", "file:///tmp/example", "javascript:alert(1)", "data:text/plain,hello", "custom:command"])
    fun `unsupported links are rejected before contacting the desktop`(uri: String) {
        assertFalse(TerminalHyperlinkHandler.SYSTEM.openHyperlink(uri))
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://example.test/", "http://example.test/", "mailto:user@example.test"])
    fun `hosts can deny even supported hyperlink schemes`(uri: String) {
        assertFalse(TerminalHyperlinkHandler.NONE.openHyperlink(uri))
    }
}
