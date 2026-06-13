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
package io.github.jvterm.host

/**
 * Host-facing events emitted while parser commands are mapped to core state.
 *
 * This sink is intentionally metadata-only. Grid mutation, terminal modes, and
 * terminal-to-host byte responses remain owned by the public core APIs.
 */
interface HostEventSink {
    /**
     * Called when the parser emits BEL.
     */
    fun bell()

    /**
     * Called after the OSC icon title metadata changes.
     *
     * @param title new icon title.
     */
    fun iconTitleChanged(title: String)

    /**
     * Called after the OSC window title metadata changes.
     *
     * @param title new window title.
     */
    fun windowTitleChanged(title: String)

    /**
     * Called when the shell requests a window resize.
     *
     * @param rows target row count.
     * @param columns target column count.
     */
    fun resizeWindow(
        rows: Int,
        columns: Int,
    )

    companion object {
        /**
         * Event sink used when the host does not need metadata callbacks.
         */
        @JvmField
        val NONE: HostEventSink =
            object : HostEventSink {
                override fun bell() = Unit

                override fun iconTitleChanged(title: String) = Unit

                override fun windowTitleChanged(title: String) = Unit

                override fun resizeWindow(
                    rows: Int,
                    columns: Int,
                ) = Unit
            }
    }
}
