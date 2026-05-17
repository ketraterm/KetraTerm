package com.gagik.terminal.pty

import com.gagik.core.TerminalBuffers
import com.gagik.terminal.session.TerminalSession
import java.io.IOException

/**
 * Factory for local PTY-backed terminal sessions.
 */
object TerminalPtySessions {
    /**
     * Starts a PTY process and connects it to parser, core, integration, and
     * input encoding components.
     *
     * PTY stdout is consumed on a daemon reader thread and fed to the parser.
     * Parser/core response bytes and UI input events are serialized onto PTY
     * stdin by the returned [TerminalSession].
     *
     * @param options PTY process and terminal dimensions.
     * @return running terminal session.
     * @throws IOException when PTY4J cannot start the process.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun start(options: TerminalPtyOptions = TerminalPtyOptions()): TerminalSession {
        return start(options, Pty4jTerminalProcessFactory)
    }

    internal fun start(
        options: TerminalPtyOptions,
        processFactory: TerminalProcessFactory,
    ): TerminalSession {
        val connector = PtyConnectors.create(options, processFactory)
        val terminal = TerminalBuffers.create(
            width = options.columns,
            height = options.rows,
            maxHistory = options.maxHistory,
        )
        terminal.setTreatAmbiguousAsWide(options.treatAmbiguousAsWide)
        val hostEventBridge = SessionHostEventBridge(options.eventListener)
        val session = TerminalSession.create(
            terminal = terminal,
            connector = connector,
            hostEvents = hostEventBridge,
        )
        hostEventBridge.attach(session)
        session.start(options.columns, options.rows)
        return session
    }
}
