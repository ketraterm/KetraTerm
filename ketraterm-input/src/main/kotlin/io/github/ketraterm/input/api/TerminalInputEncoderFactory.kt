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
package io.github.ketraterm.input.api

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.host.TerminalHostOutput

/**
 * Creates independently bound input encoders for a host's serialized pipelines.
 *
 * Session construction calls this twice: one encoder admits ordinary input and
 * one streams bulk input on the writer. Return a fresh instance on each call;
 * those instances can run concurrently and must not share mutable encoding state.
 * Construction must not read modes, emit bytes, start jobs, or retain external I/O.
 */
public fun interface TerminalInputEncoderFactory {
    /**
     * Binds an encoder exclusively to the supplied mode source and byte sink.
     *
     * Read [inputState] only during encoding. Write only through [output], and
     * only during an encoding call; byte ranges are synchronously consumed.
     * Calls and policy updates on each returned encoder are serialized.
     * [TerminalInputEncoder.setInputPolicy] must apply subsequent policy changes.
     *
     * @param inputState current event modes, including admission-time bulk snapshots.
     * @param output host-owned ordered byte sink; do not close or retain its byte ranges.
     * @param policy initial policy, to be applied before the first event.
     */
    public fun create(
        inputState: TerminalInputState,
        output: TerminalHostOutput,
        policy: TerminalInputPolicy,
    ): TerminalInputEncoder
}
