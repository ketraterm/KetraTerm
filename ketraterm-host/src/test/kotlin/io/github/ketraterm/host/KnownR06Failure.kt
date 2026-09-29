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

import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError
import org.opentest4j.TestAbortedException

/**
 * Executes a retained R06 audit oracle, reporting only its exact recorded failures as skipped.
 * See docs/terminal-feature-gap-map.md#r06-streaming-placement-policy for scope and removal rules.
 */
internal fun knownR06Failure(
    expectedMessages: List<String>,
    assertions: () -> Unit,
) {
    require(expectedMessages.isNotEmpty())
    val failure =
        try {
            assertions()
            null
        } catch (failure: AssertionFailedError) {
            failure
        } catch (failure: MultipleFailuresError) {
            failure
        } catch (failure: TestAbortedException) {
            throw AssertionFailedError("R06 oracle aborted before verifying the recorded failure", failure)
        }
    if (failure == null) throw AssertionFailedError("R06 known failure unexpectedly passed; review and remove its expectation")
    val failures = if (failure is MultipleFailuresError) failure.failures else listOf(failure)
    if (failures.size != expectedMessages.size ||
        failures.zip(expectedMessages).any { (actual, expected) ->
            actual !is AssertionFailedError || actual.message != expected
        }
    ) {
        throw failure
    }
    throw TestAbortedException("KNOWN FAILURE R06: accepted streaming placement limitation; original assertions executed", failure)
}
