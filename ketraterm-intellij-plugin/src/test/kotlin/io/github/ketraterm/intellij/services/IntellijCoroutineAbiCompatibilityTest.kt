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
package io.github.ketraterm.intellij.services

import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets

/** Guards shared classes against coroutine cancellation bridges absent from IntelliJ's bundled runtime. */
class IntellijCoroutineAbiCompatibilityTest {
    /** Ensures plugin-loaded shared classes call explicit cancellation overloads. */
    @Test
    fun `shared classes avoid coroutine cancellation default bridges`() {
        for (resource in PLUGIN_LOADED_SHARED_CLASSES) {
            val bytecode =
                requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
                    "Missing class resource: $resource"
                }.use { it.readBytes() }

            assertFalse(
                "$resource must use an explicit cancellation overload",
                String(bytecode, StandardCharsets.ISO_8859_1).contains(CANCEL_DEFAULT_BRIDGE),
            )
        }
    }

    private companion object {
        private const val CANCEL_DEFAULT_BRIDGE = "cancel\$default"
        private val PLUGIN_LOADED_SHARED_CLASSES =
            arrayOf(
                "io/github/ketraterm/session/TerminalSession.class",
                "io/github/ketraterm/ui/swing/api/SwingTerminal.class",
                "io/github/ketraterm/ui/swing/api/TerminalHyperlinkDiscoveryController.class",
                "io/github/ketraterm/workspace/TerminalWorkspace.class",
            )
    }
}
