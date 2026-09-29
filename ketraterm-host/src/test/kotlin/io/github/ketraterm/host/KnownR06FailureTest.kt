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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError
import org.opentest4j.TestAbortedException

class KnownR06FailureTest {
    @Test
    fun `unexpected abort cannot bypass verification`() {
        val original = TestAbortedException("unexpected skip")
        val failure =
            assertThrows(AssertionFailedError::class.java) {
                knownR06Failure(listOf("recorded")) { throw original }
            }
        assertSame(original, failure.cause)
    }

    @Test
    fun `only the recorded assertion is reported as known failure`() {
        val original = AssertionFailedError("recorded")
        val aborted =
            assertThrows(TestAbortedException::class.java) {
                knownR06Failure(listOf("recorded")) { throw original }
            }
        assertSame(original, aborted.cause)
    }

    @Test
    fun `all recorded split failures must match in order`() {
        val original = MultipleFailuresError("splits", listOf(AssertionFailedError("first"), AssertionFailedError("second")))
        val aborted =
            assertThrows(TestAbortedException::class.java) {
                knownR06Failure(listOf("first", "second")) { throw original }
            }
        assertSame(original, aborted.cause)
        for (expected in listOf(
            listOf("first"),
            listOf("first", "changed"),
            listOf("second", "first"),
            listOf("first", "second", "extra"),
        )) {
            val failure =
                assertThrows(MultipleFailuresError::class.java) {
                    knownR06Failure(expected) { throw original }
                }
            assertSame(original, failure)
        }
    }

    @Test
    fun `unexpected pass requires removal of the expectation`() {
        val failure =
            assertThrows(AssertionFailedError::class.java) {
                knownR06Failure(listOf("recorded")) { }
            }
        assertEquals("R06 known failure unexpectedly passed; review and remove its expectation", failure.message)
    }

    @Test
    fun `changed assertions and runtime exceptions remain failures`() {
        for (original in listOf(AssertionFailedError("changed"), IllegalStateException("recorded"))) {
            val failure =
                assertThrows(original.javaClass) {
                    knownR06Failure(listOf("recorded")) { throw original }
                }
            assertSame(original, failure)
        }
        val original = MultipleFailuresError("splits", listOf(IllegalStateException("recorded")))
        val failure =
            assertThrows(MultipleFailuresError::class.java) {
                knownR06Failure(listOf("recorded")) { throw original }
            }
        assertSame(original, failure)
    }
}
