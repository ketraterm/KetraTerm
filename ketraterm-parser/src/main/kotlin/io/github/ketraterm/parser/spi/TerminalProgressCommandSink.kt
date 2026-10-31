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
package io.github.ketraterm.parser.spi

import io.github.ketraterm.protocol.TerminalProgressState

/**
 * Optional parser capability for application-controlled OSC 9;4 progress metadata.
 *
 * Sinks implementing only [TerminalCommandSink] ignore this protocol without treating
 * its payload as a notification or printable text. A delegate must expose this interface
 * explicitly to forward progress; ordinary command-sink delegation does not opt in.
 * State retention, permission, expiry, and UI presentation belong downstream.
 */
public interface TerminalProgressCommandSink : TerminalCommandSink {
    /**
     * Reports progress synchronously on the parser caller's serialized thread.
     *
     * [percentage] is in `0..100`; it is zero for [TerminalProgressState.HIDDEN] and
     * [TerminalProgressState.INDETERMINATE], whose wire percentage is ignored. Zero
     * is a real determinate value for the other states. The parser supplies zero
     * for omitted numeric fields and rejects malformed or out-of-range commands.
     * Implementations must consume the callback promptly without reentering parsing.
     */
    public fun setProgress(
        state: TerminalProgressState,
        percentage: Int,
    )
}
