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
package io.github.ketraterm.completion.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalCompletionTriggerEvaluatorTest {
    @Test
    fun `shouldTrigger respects minimum non-whitespace characters threshold`() {
        assertFalse(TerminalCompletionTriggerEvaluator.shouldTrigger("g", 1, 2))
        assertTrue(TerminalCompletionTriggerEvaluator.shouldTrigger("go", 2, 2))
        assertTrue(TerminalCompletionTriggerEvaluator.shouldTrigger("git", 3, 2))
    }

    @Test
    fun `isLiveTrigger matches hyphen option prefix`() {
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("-", 1))
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("git -", 5))
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("docker --", 9))
    }

    @Test
    fun `isLiveTrigger matches path separators`() {
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("/", 1))
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("\\", 1))
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("cd /", 4))
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("./", 2))
    }

    @Test
    fun `isLiveTrigger matches environment variable symbol`() {
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("$", 1))
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("echo $", 6))
    }

    @Test
    fun `isLiveTrigger matches single space after finished word of at least length two`() {
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("go ", 3))
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("git ", 4))
        assertTrue(TerminalCompletionTriggerEvaluator.isLiveTrigger("docker run ", 11))
    }

    @Test
    fun `isLiveTrigger rejects space after too short words`() {
        assertFalse(TerminalCompletionTriggerEvaluator.isLiveTrigger("g ", 2))
        assertFalse(TerminalCompletionTriggerEvaluator.isLiveTrigger("a ", 2))
    }

    @Test
    fun `isLiveTrigger rejects multiple spaces or space on empty command`() {
        assertFalse(TerminalCompletionTriggerEvaluator.isLiveTrigger(" ", 1))
        assertFalse(TerminalCompletionTriggerEvaluator.isLiveTrigger("  ", 2))
        assertFalse(TerminalCompletionTriggerEvaluator.isLiveTrigger("git  ", 5))
    }
}
