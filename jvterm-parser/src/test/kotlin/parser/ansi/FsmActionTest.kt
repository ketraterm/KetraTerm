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
package com.gagik.parser.ansi

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("FsmAction")
class FsmActionTest {
    private val allActions: List<Int> =
        listOf(
            FsmAction.IGNORE,
            FsmAction.EXECUTE,
            FsmAction.EXECUTE_AND_CLEAR,
            FsmAction.CLEAR_SEQUENCE,
            FsmAction.PRINT_ASCII,
            FsmAction.PRINT_UTF8,
            FsmAction.COLLECT_INTERMEDIATE,
            FsmAction.PARAM_DIGIT,
            FsmAction.PARAM_SEPARATOR,
            FsmAction.PARAM_COLON,
            FsmAction.SET_PRIVATE_MARKER,
            FsmAction.ESC_DISPATCH,
            FsmAction.CSI_DISPATCH,
            FsmAction.OSC_START,
            FsmAction.OSC_PUT_ASCII,
            FsmAction.OSC_PUT_UTF8,
            FsmAction.DCS_IGNORE_START,
            FsmAction.DCS_PUT_ASCII,
            FsmAction.DCS_PUT_UTF8,
            FsmAction.OSC_EXECUTE_CONTROL,
            FsmAction.OSC_END,
            FsmAction.DCS_END,
            FsmAction.STRING_END,
        )

    @Nested
    @DisplayName("constants and layout")
    inner class ConstantsAndLayout {
        @Test
        fun `action ids are contiguous and covered by COUNT`() {
            assertAll(
                { assertEquals(FsmAction.COUNT, allActions.size) },
                { assertEquals((0 until FsmAction.COUNT).toList(), allActions) },
                { assertEquals(allActions.size, allActions.toSet().size, "action ids must be unique") },
            )
        }

        @Test
        fun `COUNT is one past the highest action id`() {
            assertEquals(allActions.max() + 1, FsmAction.COUNT)
        }

        @Test
        fun `action ids fit in the transition action byte`() {
            assertTrue(allActions.all { it in 0..0xFF })
        }
    }

    @Nested
    @DisplayName("string actions")
    inner class StringActions {
        @Test
        fun `string-specific actions are separate from global control execution`() {
            val stringActions =
                setOf(
                    FsmAction.OSC_EXECUTE_CONTROL,
                    FsmAction.OSC_END,
                    FsmAction.DCS_END,
                    FsmAction.STRING_END,
                )

            assertAll(
                { assertTrue(FsmAction.EXECUTE !in stringActions) },
                { assertTrue(FsmAction.EXECUTE_AND_CLEAR !in stringActions) },
            )
        }
    }
}
