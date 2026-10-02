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
package io.github.ketraterm.input

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.protocol.host.TerminalHostOutput

/**
 * Factory for creating terminal input encoder instances.
 */
public object TerminalInputEncoders {
    /**
     * Creates a terminal input encoder.
     *
     * The returned encoder owns reusable scratch storage and is not thread-safe. Serialize
     * encoding and policy updates with mode reads and all other producers of [output].
     * Output ranges are borrowed only during each synchronous sink call; sink failures
     * propagate and may leave a partially written input operation.
     *
     * @param inputState read-only core mode state used for input decisions.
     * @param output host-bound byte sink.
     * @param policy policy for ambiguous or unsupported keyboard encodings.
     * @return a new terminal input encoder instance.
     */
    @JvmStatic
    @JvmOverloads
    public fun create(
        inputState: TerminalInputState,
        output: TerminalHostOutput,
        policy: io.github.ketraterm.input.policy.TerminalInputPolicy =
            io.github.ketraterm.input.policy
                .TerminalInputPolicy(),
    ): io.github.ketraterm.input.api.TerminalInputEncoder =
        io.github.ketraterm.input.impl
            .DefaultTerminalInputEncoder(inputState, output, policy)
}
