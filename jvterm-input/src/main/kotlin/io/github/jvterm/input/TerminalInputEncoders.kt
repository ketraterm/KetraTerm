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
package io.github.jvterm.input

import io.github.jvterm.core.api.TerminalInputState
import io.github.jvterm.input.api.TerminalInputEncoder
import io.github.jvterm.input.impl.DefaultTerminalInputEncoder
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.protocol.host.TerminalHostOutput

/**
 * Factory for creating terminal input encoder instances.
 */
object TerminalInputEncoders {
    /**
     * Creates a terminal input encoder.
     *
     * @param inputState read-only core mode state used for input decisions.
     * @param output host-bound byte sink.
     * @param policy policy for ambiguous or unsupported keyboard encodings.
     * @return a new terminal input encoder instance.
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        inputState: TerminalInputState,
        output: TerminalHostOutput,
        policy: TerminalInputPolicy = TerminalInputPolicy(),
    ): TerminalInputEncoder = DefaultTerminalInputEncoder(inputState, output, policy)
}
