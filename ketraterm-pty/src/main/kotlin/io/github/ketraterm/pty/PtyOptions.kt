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
package io.github.ketraterm.pty

import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.policy.EnterNewLineModePolicy
import io.github.ketraterm.input.policy.PasteLineEndingPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.TerminalCapabilityIdentity
import io.github.ketraterm.protocol.TerminalHostModeCapability
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalStartupCommand
import java.nio.file.Path

/**
 * Configuration for starting a local PTY-backed terminal session.
 *
 * Use [create] and [copy] for named construction and immutable updates.
 * Command and environment collections are defensively copied when built.
 * Callbacks, policies, and shell producers retain their documented host ownership.
 *
 * @property command command and arguments passed to the PTY child process.
 * @property environment environment variables for the child process. `TERM` and
 * `COLORTERM` default to the shared terminal capability identity when the
 * caller does not provide them.
 * @property workingDirectory initial process working directory, or `null` to let
 * PTY4J use its platform default.
 * @property columns initial terminal width in cells.
 * @property rows initial terminal height in rows.
 * @property treatAmbiguousAsWide whether East Asian Ambiguous codepoints occupy
 * two cells in the core width policy for future writes.
 * @property inputPolicy host-bound input encoding policy. Local PTY sessions
 * default Return/Enter to CR even when LNM is active, because contemporary PTY
 * line disciplines can otherwise turn DEC CR LF into an extra newline.
 * Unbracketed paste line endings are likewise canonicalized to CR so every
 * pasted boundary has the same input semantics as Enter.
 * @property maxHistory maximum scrollback lines retained by the core buffer.
 * @property readBufferSize buffer size used by the PTY stdout reader thread.
 * @property readerThreadName name for the daemon PTY stdout reader thread.
 * @property watcherThreadName name for the daemon process exit watcher thread.
 * @property eventListener host callbacks for parser-discovered PTY metadata
 * events such as BEL and title changes.
 * @property hostPolicy safety policy for terminal-triggered host actions.
 * @property modeReportCapabilities implemented host actions from TerminalHostModeCapability.
 * @property startupCommand command to submit once [shellIntegration] reports a ready prompt.
 * The caller is responsible for installing its shell hooks before launch.
 * @property shellIntegration selected producer of shell metadata, editing context and
 * prompt readiness. Null installs no shell integration. PTY assembly does not
 * select a protocol implementation or own the producer's lifetime.
 */
public class PtyOptions private constructor(
    builder: Builder,
) {
    /** Creates a validated snapshot with default values. */
    public constructor() : this(Builder())

    public val command: List<String> = java.util.List.copyOf(builder.command)
    public val environment: Map<String, String> = java.util.Map.copyOf(builder.environment)
    public val workingDirectory: Path? = builder.workingDirectory
    public val columns: Int = builder.columns
    public val rows: Int = builder.rows
    public val treatAmbiguousAsWide: Boolean = builder.treatAmbiguousAsWide
    public val inputPolicy: TerminalInputPolicy = builder.inputPolicy
    public val maxHistory: Int = builder.maxHistory
    public val readBufferSize: Int = builder.readBufferSize
    public val readerThreadName: String = builder.readerThreadName
    public val watcherThreadName: String = builder.watcherThreadName
    public val eventListener: PtyEventListener = builder.eventListener
    public val hostPolicy: HostPolicy = builder.hostPolicy
    public val startupCommand: TerminalStartupCommand? = builder.startupCommand
    public val modeReportCapabilities: Int = builder.modeReportCapabilities
    public val shellIntegration: TerminalShellIntegrationFactory? = builder.shellIntegration

    /** Returns a detached mutable draft. Builders are caller-confined and never retained by snapshots. */
    public fun toBuilder(): Builder = Builder(this)

    /**
     * Configures a fresh draft synchronously and returns a validated immutable snapshot.
     * Exceptions propagate without changing this snapshot. Supplied services remain host-owned.
     */
    public fun copy(configure: java.util.function.Consumer<Builder>): PtyOptions = toBuilder().also { configure.accept(it) }.build()

    /** Mutable construction draft. Not thread-safe; [build] never retains this draft. */
    public class Builder internal constructor(
        source: PtyOptions? = null,
    ) {
        /** Draft value for [PtyOptions.command]; validated when [build] is called. */
        public var command: List<String> = source?.command ?: defaultCommand()

        /** Draft value for [PtyOptions.environment]; validated when [build] is called. */
        public var environment: Map<String, String> = source?.environment ?: defaultEnvironment()

        /** Draft value for [PtyOptions.workingDirectory]; validated when [build] is called. */
        public var workingDirectory: Path? = if (source != null) source.workingDirectory else Path.of(System.getProperty("user.home"))

        /** Draft value for [PtyOptions.columns]; validated when [build] is called. */
        public var columns: Int = source?.columns ?: 80

        /** Draft value for [PtyOptions.rows]; validated when [build] is called. */
        public var rows: Int = source?.rows ?: 24

        /** Draft value for [PtyOptions.treatAmbiguousAsWide]; validated when [build] is called. */
        public var treatAmbiguousAsWide: Boolean = source?.treatAmbiguousAsWide ?: false

        /** Draft value for [PtyOptions.inputPolicy]; validated when [build] is called. */
        public var inputPolicy: TerminalInputPolicy = source?.inputPolicy ?: defaultInputPolicy()

        /** Draft value for [PtyOptions.maxHistory]; validated when [build] is called. */
        public var maxHistory: Int = source?.maxHistory ?: 1000

        /** Draft value for [PtyOptions.readBufferSize]; validated when [build] is called. */
        public var readBufferSize: Int = source?.readBufferSize ?: 8192

        /** Draft value for [PtyOptions.readerThreadName]; validated when [build] is called. */
        public var readerThreadName: String = source?.readerThreadName ?: "terminal-pty-reader"

        /** Draft value for [PtyOptions.watcherThreadName]; validated when [build] is called. */
        public var watcherThreadName: String = source?.watcherThreadName ?: "terminal-pty-watcher"

        /** Draft value for [PtyOptions.eventListener]; validated when [build] is called. */
        public var eventListener: PtyEventListener = source?.eventListener ?: PtyEventListener.NONE

        /** Draft value for [PtyOptions.hostPolicy]; validated when [build] is called. */
        public var hostPolicy: HostPolicy = source?.hostPolicy ?: HostPolicy()

        /** Draft value for [PtyOptions.startupCommand]; validated when [build] is called. */
        public var startupCommand: TerminalStartupCommand? = source?.startupCommand

        /** Draft value for [PtyOptions.modeReportCapabilities]; validated when [build] is called. */
        public var modeReportCapabilities: Int = source?.modeReportCapabilities ?: 0

        /** Draft value for [PtyOptions.shellIntegration]; validated when [build] is called. */
        public var shellIntegration: TerminalShellIntegrationFactory? = source?.shellIntegration

        /** Validates and freezes current values; later draft changes cannot affect the result. */
        public fun build(): PtyOptions = PtyOptions(this)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PtyOptions &&
            command == other.command &&
            environment == other.environment &&
            workingDirectory == other.workingDirectory &&
            columns == other.columns &&
            rows == other.rows &&
            treatAmbiguousAsWide == other.treatAmbiguousAsWide &&
            inputPolicy == other.inputPolicy &&
            maxHistory == other.maxHistory &&
            readBufferSize == other.readBufferSize &&
            readerThreadName == other.readerThreadName &&
            watcherThreadName == other.watcherThreadName &&
            eventListener == other.eventListener &&
            hostPolicy == other.hostPolicy &&
            startupCommand == other.startupCommand &&
            modeReportCapabilities == other.modeReportCapabilities &&
            shellIntegration == other.shellIntegration

    override fun hashCode(): Int {
        var result = 1
        result = 31 * result + command.hashCode()
        result = 31 * result + environment.hashCode()
        result = 31 * result + workingDirectory.hashCode()
        result = 31 * result + columns.hashCode()
        result = 31 * result + rows.hashCode()
        result = 31 * result + treatAmbiguousAsWide.hashCode()
        result = 31 * result + inputPolicy.hashCode()
        result = 31 * result + maxHistory.hashCode()
        result = 31 * result + readBufferSize.hashCode()
        result = 31 * result + readerThreadName.hashCode()
        result = 31 * result + watcherThreadName.hashCode()
        result = 31 * result + eventListener.hashCode()
        result = 31 * result + hostPolicy.hashCode()
        result = 31 * result + startupCommand.hashCode()
        result = 31 * result + modeReportCapabilities.hashCode()
        result = 31 * result + shellIntegration.hashCode()
        return result
    }

    init {
        require(startupCommand == null || shellIntegration != null) {
            "startupCommand requires shell integration with prompt readiness"
        }
        require(modeReportCapabilities and TerminalHostModeCapability.ALL.inv() == 0) {
            "invalid host mode-report capabilities: $modeReportCapabilities"
        }
        require(command.isNotEmpty()) { "PTY command must not be empty" }
        require(command.none { it.isEmpty() }) { "PTY command elements must not be empty" }
        require(columns > 0) { "PTY columns must be positive, got $columns" }
        require(rows > 0) { "PTY rows must be positive, got $rows" }
        require(maxHistory >= 0) { "PTY maxHistory must be >= 0, got $maxHistory" }
        require(readBufferSize > 0) { "PTY readBufferSize must be positive, got $readBufferSize" }
        require(readerThreadName.isNotBlank()) { "PTY readerThreadName must not be blank" }
        require(watcherThreadName.isNotBlank()) { "PTY watcherThreadName must not be blank" }
    }

    public companion object {
        /** Creates a fresh caller-confined draft initialized to defaults. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Configures a draft synchronously and returns one validated immutable snapshot. */
        @JvmStatic
        public fun create(configure: java.util.function.Consumer<Builder>): PtyOptions = builder().also { configure.accept(it) }.build()

        /**
         * Returns the platform default interactive shell command.
         *
         * @return default interactive shell command.
         */
        @JvmStatic
        public fun defaultCommand(): List<String> {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("windows")) {
                val comspec = System.getenv("COMSPEC")
                return listOf(if (comspec.isNullOrBlank()) "cmd.exe" else comspec)
            }
            val shell = System.getenv("SHELL")
            return listOf(if (shell.isNullOrBlank()) "/bin/sh" else shell, "-l")
        }

        /**
         * Returns a process environment suitable for contemporary shells and TUIs.
         *
         * @return default process environment variables.
         */
        @JvmStatic
        public fun defaultEnvironment(): Map<String, String> {
            val env = LinkedHashMap(System.getenv())
            env["TERM"] = TerminalCapabilityIdentity.TERM_NAME
            env["COLORTERM"] = TerminalCapabilityIdentity.COLOR_TERM_TRUECOLOR
            return env
        }

        /**
         * Returns the default input policy for local PTY-backed sessions.
         *
         * @return default terminal input policy.
         */
        @JvmStatic
        public fun defaultInputPolicy(): TerminalInputPolicy =
            TerminalInputPolicy(
                enterNewLineModePolicy = EnterNewLineModePolicy.SEND_CR,
                pasteLineEndingPolicy = PasteLineEndingPolicy.CARRIAGE_RETURN,
            )
    }
}
