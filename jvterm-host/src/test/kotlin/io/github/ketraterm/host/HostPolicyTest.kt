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
package io.github.ketraterm.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HostPolicy")
class HostPolicyTest {
    @Test
    fun `defaults allow implemented host controls and deny future clipboard controls`() {
        val policy = HostPolicy()

        assertEquals(TerminalTitleOrigin.LOCAL, policy.titlePolicy.origin)
        assertEquals(TerminalTitlePermission.ALLOW, policy.titlePolicy.localPermission)
        assertEquals(TerminalTitlePermission.ALLOW, policy.titlePolicy.remotePermission)
        assertEquals(TerminalTitleOverflowPolicy.CLAMP, policy.titlePolicy.overflowPolicy)
        assertEquals(TerminalTitlePolicy.DEFAULT_MAX_LENGTH, policy.titlePolicy.maxLength)
        assertEquals(HostControlPolicy.ALLOW, policy.hyperlinkPolicy)
        assertEquals(HostControlPolicy.ALLOW, policy.currentWorkingDirectoryPolicy)
        assertEquals(HostControlPolicy.ALLOW, policy.notificationPolicy)
        assertEquals(HostControlPolicy.ALLOW, policy.windowManipulationPolicy)
        assertEquals(HostControlPolicy.ALLOW, policy.palettePolicy)
        assertEquals(HostControlPolicy.ALLOW, policy.terminalResponsePolicy)
        assertEquals(TerminalClipboardOrigin.REMOTE, policy.clipboardPolicy.origin)
        assertEquals(TerminalClipboardPermission.DENY, policy.clipboardPolicy.localWritePermission)
        assertEquals(TerminalClipboardPermission.DENY, policy.clipboardPolicy.remoteWritePermission)
        assertEquals(TerminalClipboardPermission.DENY, policy.clipboardPolicy.readPermission)
        assertEquals(false, policy.clipboardPolicy.allowlisted)
        assertEquals(TerminalClipboardPolicy.DEFAULT_MAX_DECODED_BYTES, policy.clipboardPolicy.maxDecodedBytes)
    }

    @Test
    fun `bounds reject invalid values at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            HostPolicy(titlePolicy = TerminalTitlePolicy(maxLength = -1))
        }
        assertThrows(IllegalArgumentException::class.java) { HostPolicy(maxHyperlinkEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { HostPolicy(maxHyperlinkUriLength = -1) }
        assertThrows(IllegalArgumentException::class.java) { HostPolicy(maxHyperlinkIdLength = -1) }
        assertThrows(IllegalArgumentException::class.java) { HostPolicy(maxNotificationTitleLength = -1) }
        assertThrows(IllegalArgumentException::class.java) { HostPolicy(maxNotificationBodyLength = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            HostPolicy(maxCurrentWorkingDirectoryUriLength = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostPolicy(clipboardPolicy = TerminalClipboardPolicy(maxDecodedBytes = -1))
        }
    }
}
