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
package io.github.ketraterm.app.ui

import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class TerminalTuiRequirementsTest {
    @Test
    fun `missing required tools fail rather than skip while optional tools skip explicitly`() {
        val key = "terminal.test.requiredTuis"
        val original = System.getProperty(key)
        try {
            System.setProperty(key, "NEOVIM, WSL_TMUX")
            val failure = assertFailsWith<AssertionError> { TuiTool.NEOVIM.requireAvailable(false, "missing executable") }
            assertContains(failure.message.orEmpty(), "missing executable")
            assertFailsWith<TestAbortedException> { TuiTool.LESS.requireAvailable(false, "not installed") }
            TuiTool.NEOVIM.requireAvailable(true, "available")
        } finally {
            if (original == null) System.clearProperty(key) else System.setProperty(key, original)
        }
    }

    @Test
    fun `unknown required tools fail instead of silently dropping coverage`() {
        val key = "terminal.test.requiredTuis"
        val original = System.getProperty(key)
        try {
            System.setProperty(key, "NEOVMI")
            assertFailsWith<IllegalArgumentException> { TuiTool.NEOVIM.requireAvailable(true, "available") }
        } finally {
            if (original == null) System.clearProperty(key) else System.setProperty(key, original)
        }
    }
}
