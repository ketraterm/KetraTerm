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
package io.github.ketraterm.session

import io.github.ketraterm.core.api.*
import io.github.ketraterm.host.*
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
import io.github.ketraterm.input.event.*
import io.github.ketraterm.input.policy.BackspacePolicy
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalOutputParserFactory
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.transport.checkBounds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Runtime terminal session that binds core, parser, input encoding, and a
 * transport connector.
 *
 * The connector owns transport threads. This session owns parser/core mutation
 * serialization and all host-bound write ordering.
 *
 * Ordinary input and core replies share a bounded 8 MiB byte queue. Paste and
 * text replacement retain their source and admission-time modes/policy for
 * background encoding, bounded to 16 operations and 16 * 1024 * 1024 combined
 * UTF-16/deletion units, including active work. One writer preserves order.
 * [submitInput] and [submitBytes] report admission, not transport completion; the
 * encoder interface methods discard that result. Queue
 * exhaustion or outbound worker failure closes the session with [failure]; closing a
 * session discards pending output. Custom encoders bind to session-owned mode
 * sources and output sinks through [TerminalInputEncoderFactory].
 * Unexpected cancellation in connector writes or bulk encoding also closes the
 * session without retrying potentially partial output, retaining the cause. Cancellation during
 * an already claimed shutdown preserves the first termination event.
 *
 * Closure freezes terminal state after already-admitted work and parser EOF finish.
 * Late input, policy/presentation setters and render requests are ignored; resize
 * throws [IllegalStateException]. Retained state remains readable, but no later
 * presentation mutation or publication is supported. Observe [state] reaching
 * [TerminalSessionState.Closed] to know final cleanup/publication has completed.
 *
 * A session publishes one active render viewport. Independently scrolling
 * views of the same session are unsupported: each new viewport request replaces
 * the previous request. Separate sessions are separate terminal pipelines.
 *
 * Shell metadata comes exclusively from the integration selected at construction.
 * With no integration, shell features are unavailable. Hosts can supply their own
 * model using [TerminalShellIntegrationFactory.host], or select an optional protocol
 * producer. Session closure stops observations without cancelling host-owned producers.
 *
 * Successful construction transfers exclusive access to the mutable terminal, parser,
 * response reader, connector, and render publisher to this session. Collaborators
 * must describe the same terminal pipeline and must not be shared with another
 * active session. Configure core state before construction; while the session is
 * open, use its synchronized settings, input, and frame APIs. Direct mutation or
 * frame reads through retained core references bypass that serialization.
 *
 * @property shellIntegrationState shared host-side prompt and command marker state.
 * @property workerDispatcher non-owned dispatcher used for session background work.
 * @property ioDispatcher non-owned dispatcher for connector writes, metadata queries, and clipboard providers.
 */
public class TerminalSession private constructor(
    private val terminal: TerminalBuffer,
    private val runtime: SessionRuntime,
    private val responseReader: TerminalHostResponseReader,
    private val connector: TerminalConnector,
    private val parser: TerminalOutputParser,
    private val inputEncoderFactory: TerminalInputEncoderFactory? = null,
    private val hyperlinkResolver: TerminalHyperlinkResolver = TerminalHyperlinkResolver.NONE,
    private val outboundWriteLock: Any = Any(),
    private val hostCommandAdapter: HostCommandAdapter? = null,
    private var inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    startupCommand: TerminalStartupCommand? = null,
) : TerminalConnectorListener,
    TerminalInputEncoder,
    TerminalInputState,
    TerminalRenderFrameReader,
    AutoCloseable {
    /**
     * Assembles a session around a caller-supplied parser and response reader.
     * The caller owns the parser's host-event wiring, including accepted shell
     * events if its selected producer interprets protocols. Use [create] for
     * standard parser assembly and automatic dispatch to that producer.
     * Host-owned models supplied through [TerminalShellIntegrationFactory.host]
     * need no parser callbacks.
     *
     * [terminal], [renderReader], [responseReader], and [parser] must operate on
     * the same core state; adapters around that state are permitted. The session
     * owns their exclusive runtime access, finalizes parser EOF, and closes the connector.
     * [renderPublisher] must have no other writer. [inputEncoderFactory] creates
     * separate admission and streaming encoders bound to session-owned output.
     * A supplied [hostCommandAdapter] must be the adapter used by [parser]; it
     * receives session host-policy, theme-palette, and Backarrow-default changes.
     * Custom parsers supplied without this adapter own their host-policy updates
     * and Backarrow-default mode-report wiring.
     */
    public constructor(
        terminal: TerminalBuffer,
        renderPublisher: TerminalRenderPublisher,
        renderReader: TerminalRenderFrameReader,
        responseReader: TerminalHostResponseReader,
        connector: TerminalConnector,
        parser: TerminalOutputParser,
        inputEncoderFactory: TerminalInputEncoderFactory? = null,
        hyperlinkResolver: TerminalHyperlinkResolver = TerminalHyperlinkResolver.NONE,
        hostCommandAdapter: HostCommandAdapter? = null,
        inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
        workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        shellIntegration: TerminalShellIntegrationFactory? = null,
    ) : this(
        terminal,
        SessionRuntime(renderReader, renderPublisher, shellIntegration),
        responseReader,
        connector,
        parser,
        inputEncoderFactory,
        hyperlinkResolver,
        Any(),
        hostCommandAdapter,
        inputPolicy,
        workerDispatcher,
        ioDispatcher,
    )

    /**
     * Borrows the latest copied render cache, or returns null before first publication.
     *
     * Safe from any thread, including after closure; this does not request a new frame.
     * The callback may read/copy borrowed primitive planes but must not mutate or retain
     * the cache or its arrays. It must not close the session or reenter its mutation or
     * publication APIs. Failures and non-local returns release the lease before escaping.
     * A null result can also be the callback's own result. No lease or callback object is
     * allocated for an inlined call; the publisher remains owned exclusively by session.
     */
    public inline fun <T> readPublishedFrame(block: (TerminalRenderCache) -> T): T? {
        val cache = acquirePublishedFrame() ?: return null
        try {
            return block(cache)
        } finally {
            releasePublishedFrame(cache)
        }
    }

    @PublishedApi
    @JvmSynthetic
    internal fun acquirePublishedFrame(): TerminalRenderCache? = runtime.publisher.acquireFrontLease()

    @PublishedApi
    @JvmSynthetic
    internal fun releasePublishedFrame(cache: TerminalRenderCache): Unit = runtime.publisher.releaseFrontLease(cache)

    /** Bounded projection supplied by the selected shell producer, or an empty model when absent. */
    public val shellIntegrationState: TerminalShellIntegrationView get() = runtime.shellState

    /**
     * Immutable startup expectation supplied by the selected shell producer.
     * True permits reserving prompt presentation space before metadata arrives;
     * false means no expectation was supplied, including sessions without a producer.
     * This does not report current prompt readiness or identify a running shell.
     */
    public val promptMarkersExpected: Boolean = runtime.shellIntegration?.promptMarkersExpected == true
    private val renderReader: TerminalRenderFrameReader get() = runtime.reader
    private val pendingRenderRequest = AtomicLong(packRenderRequest(scrollbackOffset = 0, viewportRows = 0))
    private val pendingRenderGeneration = AtomicLong(0)

    private val mutationLock: Any get() = runtime.mutationLock
    private var processingOutput = false
    private val connectorLifecycleLock = Any()
    private val connectorResizeLock = Any()
    private var startAttempted = false
    private val closingEvent = AtomicReference<TerminalSessionCloseEvent?>(null)
    private val startupSubmission = startupCommand?.let(::StartupCommandSubmission)

    /** Retained startup submission outcome, or null when no command was configured. */
    public val startupCommandStatus: StateFlow<TerminalStartupCommandStatus>?
        get() = startupSubmission?.status
    private val responseScratch = ByteArray(RESPONSE_BUFFER_SIZE)
    private val synchronizedTimeoutJob = AtomicReference<Job?>(null)
    private val renderRequests = Channel<Unit>(Channel.CONFLATED)
    private val immediateRenderRequests = Channel<Unit>(Channel.CONFLATED)
    private val sessionJob = SupervisorJob()
    private val sessionScope =
        CoroutineScope(
            sessionJob +
                workerDispatcher +
                CoroutineName("terminal-session-${SESSION_COUNTER.getAndIncrement()}"),
        )
    private val outboundWriter = OutboundWriter(connector, outboundWriteLock)
    private val editContextOwner = Any()
    private var inputRevision = 0L // Protected by outboundWriteLock.
    private var outputRevision = 0L // Protected by mutationLock; includes resize.
    private val inputEncoder =
        createInputEncoder(
            object : TerminalInputState {
                override fun getInputModeBits(): Long {
                    check(Thread.holdsLock(outboundWriteLock)) { "Modes are available only during input encoding" }
                    return terminal.getInputModeBits()
                }
            },
            object : SessionTerminalHostOutput() {
                override fun writeBytes(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    check(Thread.holdsLock(outboundWriteLock)) { "Output is available only during input encoding" }
                    outboundWriter.append(bytes, offset, length)
                }
            },
            inputPolicy,
        )

    // Only the outbound worker uses this mode word, encoder, and its scratch.
    private var bulkInputModeBits = 0L
    private val streamingOutput =
        object : SessionTerminalHostOutput() {
            override fun writeBytes(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                if (isSessionClosed()) throw CancellationException("Terminal session closed")
                check(state.value === TerminalSessionState.Running) { "Output is unavailable before session start" }
                sessionJob.ensureActive()
                connector.write(bytes, offset, length)
            }
        }
    private var clipboardReads: ClipboardReadHandler? = null
    private val bulkInputEncoder =
        createInputEncoder(
            object : TerminalInputState {
                override fun getInputModeBits(): Long {
                    check(state.value === TerminalSessionState.Running) { "Modes are unavailable before session start" }
                    return bulkInputModeBits
                }
            },
            streamingOutput,
            inputPolicy,
        ).also {
            require(it !== inputEncoder) { "inputEncoderFactory must create independent encoder instances" }
        }

    private fun createInputEncoder(
        modes: TerminalInputState,
        output: io.github.ketraterm.protocol.host.TerminalHostOutput,
        policy: TerminalInputPolicy,
    ): TerminalInputEncoder = inputEncoderFactory?.create(modes, output, policy) ?: TerminalInputEncoders.create(modes, output, policy)

    private val mutableState = MutableStateFlow<TerminalSessionState>(TerminalSessionState.Created)
    private val mutableRenderGeneration = runtime.renderGeneration

    /**
     * Lifecycle state retained for current and future collectors. Closed is
     * published after cleanup and the final frame publication attempt, so a
     * successful final frame is available to both early and late observers.
     */
    public val state: StateFlow<TerminalSessionState> = mutableState.asStateFlow()

    /**
     * Best-effort foreground executable name, or `null` when unavailable.
     *
     * OS queries run once per second on the I/O dispatcher, only while collected.
     * Collectors share one tracker; no work is tied to rendering or terminal output.
     * Session closure cancels tracking and clears the retained name. Consumers own
     * their collection lifetime; this state flow does not complete on closure.
     */
    public val foregroundProcessName: StateFlow<String?> by lazy {
        ForegroundProcessTracker(sessionScope, connector::foregroundProcessName, ioDispatcher).name
    }

    /**
     * Latest successfully published render generation, or `-1` before the
     * first frame is available.
     */
    public val renderGeneration: StateFlow<Long> = mutableRenderGeneration.asStateFlow()

    /**
     * Revision of the active shell command line, tracked only while collected.
     *
     * Collectors share one tracker running on the session worker dispatcher,
     * independently of render publication. Tracking buffers are allocated on
     * subscription and released when the last collector leaves. Merely reading
     * [StateFlow.value] does not start tracking.
     *
     * The value is `-1` before an active command line is observed and
     * resets to `-1` when tracking stops. A new subscription samples the latest
     * available context without waiting for more terminal output.
     *
     * The selected integration supplies changes. Host-owned editing state does
     * not wait for rendering or require reading the grid.
     *
     * Nonnegative values identify changes in command text, cursor offset/anchor,
     * or snapshot availability. Treat them as opaque revisions;
     * intermediate changes may be conflated. Consumers should debounce this
     * signal and call [activeShellCommandLine] when they need a command snapshot.
     * Session closure cancels tracking; collectors own their collection lifetime.
     */
    public val activeShellCommandLineRevision: StateFlow<Long> by lazy {
        (runtime.shellIntegration?.commandLineChanges ?: emptyFlow()).stateIn(
            scope = sessionScope,
            started = SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
            initialValue = NO_SHELL_COMMAND_LINE_REVISION,
        )
    }

    internal val isCoroutineScopeActive: Boolean
        get() = sessionJob.isActive

    private val outboundJob: Job

    init {
        outboundJob =
            sessionScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                try {
                    outboundWriter.run()
                } catch (cancelled: CancellationException) {
                    // Only session termination may stop this essential worker without failure.
                    if (!isSessionClosed()) failWrite(cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    failWrite(failure)
                }
            }
        sessionScope.launch {
            renderRequests.consumeEach {
                drainRenderRequests()
            }
        }
        if (startupSubmission != null) {
            sessionScope.launch {
                combine(checkNotNull(runtime.shellIntegration).promptReady, state, startupSubmission.status) { ready, lifecycle, status ->
                    Triple(ready, lifecycle, status)
                }.takeWhile { (_, _, status) -> status == TerminalStartupCommandStatus.WAITING }
                    .collect { (promptReady, lifecycle, _) ->
                        val ready = promptReady && lifecycle == TerminalSessionState.Running
                        if (ready) {
                            try {
                                submitStartupCommand()
                            } catch (failure: OutboundCapacityException) {
                                failWrite(failure)
                            }
                        }
                    }
            }
        }
    }

    /**
     * Returns true after either local shutdown, remote closure, or transport
     * failure has made the session unable to accept more input.
     * This becomes true when shutdown starts; [state] reaches Closed after cleanup.
     */
    public val isClosed: Boolean
        get() = isSessionClosed()

    /**
     * Remote process exit code after [onClosed] receives one.
     */
    public val exitCode: Int?
        get() = (state.value as? TerminalSessionState.Closed)?.event?.exitCode

    /**
     * The first startup, transport, or outbound worker failure, including unexpected
     * writer cancellation; `null` for normal remote closure or local close.
     */
    public val failure: Throwable?
        get() = (state.value as? TerminalSessionState.Closed)?.event?.failure

    /**
     * Resolves a primitive render-frame hyperlink id to a target URI.
     *
     * UI components call this from explicit user activation paths after reading
     * [TerminalRenderCache.hyperlinkIds]. The lookup is outside the paint loop
     * and returns `null` when metadata is unavailable or was evicted by policy.
     *
     * @param hyperlinkId cell hyperlink id; `0` means no hyperlink.
     * @return target URI, or `null` if none.
     */
    public fun hyperlinkUri(hyperlinkId: Int): String? = synchronized(mutationLock) { hyperlinkResolver.uriForHyperlinkId(hyperlinkId) }

    /** Current immutable effective palette, read under the session mutation lock. */
    public val palette: TerminalColorPalette
        get() = synchronized(mutationLock) { terminal.palette }

    /** Detached durable mode state, sampled under session serialization and readable after closure. */
    public val modeSnapshot: TerminalModeSnapshot
        get() = synchronized(mutationLock) { terminal.getModeSnapshot() }

    /**
     * Returns one coherent primitive snapshot of input modes under session
     * serialization, without allocation. The retained modes remain readable
     * after closure through [TerminalInputState]'s decoding helpers.
     */
    override fun getInputModeBits(): Long = synchronized(mutationLock) { terminal.getInputModeBits() }

    /** Records host window minimization for terminal window reports; ignored after closure. */
    public fun setWindowMinimized(minimized: Boolean) {
        synchronized(mutationLock) {
            if (!isSessionClosed()) terminal.setWindowMinimized(minimized)
        }
    }

    /**
     * Returns the latest recorded current-working-directory URI.
     *
     * The selected integration owns directory metadata. Raw OSC callbacks do
     * not modify a host-owned model.
     *
     * @return absolute `file://` URI reported by the shell or host, or `null`
     *   before one is recorded.
     */
    public fun currentWorkingDirectoryUri(): String? = shellIntegrationState.currentWorkingDirectoryUri()

    /**
     * Returns the active shell command-line snapshot, or `null` after closure
     * or when no trustworthy editing context is available.
     *
     * The selected integration is authoritative, including `null`. A host
     * snapshot is read directly; a protocol producer may reconstruct bounded
     * text from the grid. Hosts must keep UTF-16 offsets and live-grid anchors
     * current after corresponding output and geometry changes.
     *
     * @return active command-line snapshot, or `null` when unavailable.
     */
    public fun activeShellCommandLine(): TerminalShellCommandLineSnapshot? {
        if (isSessionClosed()) return null
        val snapshot = runtime.shellIntegration?.activeCommandLine()
        return if (isSessionClosed()) null else snapshot
    }

    /**
     * Captures a conditional-edit context from the selected authoritative shell
     * producer. Returns null before start, during shutdown, while the writer has
     * pending/active work, when editing is unavailable, or for a producer without
     * atomic revision support (including the legacy StateFlow-only host factory).
     *
     * Capture at request time, before asynchronous suggestion work. The host must
     * publish unavailable editing state while its own pending input makes the
     * model untrustworthy; queue idleness is not acknowledgement by a remote shell.
     */
    public fun captureCommandEdit(): TerminalCommandEditContext? =
        synchronized(mutationLock) {
            runtime.shellIntegration?.withCommandLine { revision, snapshot ->
                synchronized(outboundWriteLock) {
                    if (!isAcceptingInput() || snapshot == null || !outboundWriter.isIdle()) {
                        null
                    } else {
                        TerminalCommandEditContext(snapshot, editContextOwner, inputRevision, outputRevision, revision)
                    }
                }
            }
        }

    /**
     * Atomically validates [expected] and admits the complete semantic [events].
     * Checks session identity, cancellation, producer revision/snapshot and all
     * intervening input/output/resize under mutation, producer and admission
     * serialization. Rejection sends no prefix and does not consume capacity.
     * Success uses the same bounds, mode/policy capture and writer as [submitInput].
     *
     * The caller owns editor semantics: translate valid caret/deletion spans to
     * events before calling. This method does not infer shell editing units from
     * UTF-16 offsets. Cancel obsolete requests through [TerminalCommandEditContext.cancel].
     * Cancellation/shutdown overlapping admission may win or lose; acceptance is
     * not transport completion or a remote execution acknowledgement.
     */
    public fun submitInput(
        expected: TerminalCommandEditContext,
        events: List<TerminalInputEvent>,
    ): TerminalInputAdmission =
        finishAdmission {
            synchronized(mutationLock) {
                runtime.shellIntegration?.withCommandLine { revision, snapshot ->
                    synchronized(outboundWriteLock) {
                        inputRejection() ?: when {
                            expected.isCancelled -> TerminalInputAdmission.CANCELLED
                            expected.owner !== editContextOwner ||
                                expected.inputRevision != inputRevision ||
                                expected.outputRevision != outputRevision ||
                                expected.shellRevision != revision ||
                                expected.commandLine != snapshot -> TerminalInputAdmission.STALE_CONTEXT
                            else -> {
                                enqueueCompoundInput(events)
                                TerminalInputAdmission.ACCEPTED
                            }
                        }
                    }
                } ?: synchronized(outboundWriteLock) { inputRejection() ?: TerminalInputAdmission.UNSUPPORTED_CONTEXT }
            }
        }

    /**
     * Starts the connector after resizing core and transport to [columns] x
     * [rows]. This synchronous call permits one attempt. Concurrent or reentrant
     * attempts throw [IllegalStateException], as does starting a closed session.
     *
     * State remains [TerminalSessionState.Created] and input is ignored until
     * [TerminalConnector.start] returns successfully. Startup output is consumed
     * synchronously, but replies wait in the bounded outbound queue. If shutdown
     * has not begun, [TerminalSessionState.Running] then admits input and the
     * writer starts, preserving queued replies ahead of later input.
     *
     * Startup failure closes owned resources, retains the failure in [state],
     * and rethrows it with any cleanup failures suppressed.
     *
     * @param columns initial terminal width count.
     * @param rows initial terminal height count.
     */
    public fun start(
        columns: Int,
        rows: Int,
    ) {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        synchronized(connectorLifecycleLock) {
            check(!isSessionClosed() && !startAttempted) {
                "session already started or closed"
            }
            startAttempted = true

            try {
                if (isSessionClosed()) return
                synchronized(mutationLock) {
                    terminal.resize(columns, rows)
                }
                if (isSessionClosed()) return
                resizeConnector(columns, rows)
                if (!isSessionClosed()) {
                    connector.start(this)
                    if (!isSessionClosed()) {
                        mutableState.compareAndSet(TerminalSessionState.Created, TerminalSessionState.Running)
                    }
                }
            } catch (failure: Throwable) {
                transitionToClosed(TerminalSessionCloseEvent(exitCode = null, failure = failure, locallyRequested = false))
                throw failure
            }
        }
        outboundJob.start()
        try {
            submitStartupCommand()
        } catch (failure: OutboundCapacityException) {
            failWrite(failure)
        }
    }

    /**
     * Resizes core and the active connector, then requests a new published frame.
     *
     * @param columns target terminal column width.
     * @param rows target terminal row height.
     * @param oldScrollbackOffset The scrollback offset that was active in the UI before this resize.
     *   Pass 0 if the viewport was at the live screen (no scrollback).
     * @return A [Pair] of (newScrollbackOffset, newHistorySize) that the UI should apply to
     *   re-anchor the viewport to the same logical content after reflow.
     * @see resizeViewport for an atomic history baseline including discarded rows.
     */
    public fun resize(
        columns: Int,
        rows: Int,
        oldScrollbackOffset: Int = 0,
    ): Pair<Int, Int> {
        val result = resizeViewport(columns, rows, oldScrollbackOffset)
        return result.scrollbackOffset to result.historySize
    }

    /**
     * Resizes the terminal and connector and captures the viewport anchor and
     * history baseline under the same mutation lock. This keeps viewport resizes
     * ordered with application-requested column switches.
     *
     * Reflow may replace history storage and change its discarded-row counter.
     * Consumers that retain scrollback position must adopt the complete result
     * together, so later output is measured against the resized history.
     *
     * @param columns target terminal column width; must be positive.
     * @param rows target terminal row height; must be positive.
     * @param oldScrollbackOffset pre-resize whole-row offset, or zero for live output.
     * @return the resized viewport and history metadata from one synchronized state.
     * @throws IllegalStateException if closure has begun. Invalid dimensions still fail validation first.
     */
    public fun resizeViewport(
        columns: Int,
        rows: Int,
        oldScrollbackOffset: Int = 0,
    ): TerminalViewportResizeResult = checkNotNull(tryResizeViewport(columns, rows, oldScrollbackOffset)) { "session is closed" }

    /**
     * Attempts the same terminal reflow as [resizeViewport].
     *
     * Returns null when closure wins admission. Terminal state and the connector then remain unchanged.
     * Presentation owners can continue to read retained frames.
     * Invalid dimensions fail validation first. Other failures propagate to the caller.
     * An admitted resize requests render publication even if a collaborator fails afterward.
     *
     * Calls serialize with terminal mutation. An admitted core resize can finish during closure.
     * Connector resize and disposal share a separate lock. Closure prevents new connector resize calls.
     * This method is synchronous and can block in the connector.
     *
     * @param columns positive terminal column count.
     * @param rows positive terminal row count.
     * @param oldScrollbackOffset whole-row offset above the live screen, or zero.
     * @return the complete resize anchor, or null if closure has begun.
     */
    public fun tryResizeViewport(
        columns: Int,
        rows: Int,
        oldScrollbackOffset: Int = 0,
    ): TerminalViewportResizeResult? {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        if (isSessionClosed()) return null

        try {
            return synchronized(mutationLock) {
                if (isSessionClosed()) return null
                outputRevision++
                val (scrollbackOffset, historySize) = terminal.resize(columns, rows, oldScrollbackOffset)
                var resizedViewport: TerminalViewportResizeResult? = null
                renderReader.readRenderFrame { frame ->
                    resizedViewport = TerminalViewportResizeResult(scrollbackOffset, historySize, frame.discardedCount)
                }
                resizeConnector(columns, rows)
                checkNotNull(resizedViewport) { "Render reader did not expose the resized terminal frame" }
            }
        } finally {
            invalidateRender()
        }
    }

    private fun resizeConnector(
        columns: Int,
        rows: Int,
    ) {
        synchronized(connectorResizeLock) {
            if (!isSessionClosed()) connector.resize(columns, rows)
        }
    }

    /**
     * Applies the host's East Asian Ambiguous width policy for future writes.
     *
     * Existing stored content keeps its current cell shape; changing this
     * setting does not reinterpret already-written rows.
     *
     * @param enabled whether ambiguous codepoints occupy two cells.
     */
    public fun setTreatAmbiguousAsWide(enabled: Boolean) {
        synchronized(mutationLock) {
            if (isSessionClosed()) return
            terminal.setTreatAmbiguousAsWide(enabled)
        }
    }

    /**
     * Sets the theme-configured color palette for the session.
     *
     * This method propagates the theme changes down to the core under the
     * mutation lock.
     *
     * @param palette the theme color palette configuration.
     */
    public fun setThemePalette(palette: TerminalColorPalette) {
        synchronized(mutationLock) {
            if (isSessionClosed()) return
            val adapter = hostCommandAdapter
            if (adapter != null) adapter.setThemePalette(palette) else terminal.setThemePalette(palette)
        }
        invalidateRender()
    }

    /**
     * Updates the current and default cursor shape for the session.
     */
    public fun setCursorShape(shape: TerminalRenderCursorShape) {
        synchronized(mutationLock) {
            if (isSessionClosed()) return
            terminal.setDefaultCursorShape(shape)
            terminal.setCursorShape(shape)
        }
        invalidateRender()
    }

    /**
     * Updates the active host security policy dynamically.
     *
     * @param policy new security policy.
     */
    public fun setHostPolicy(policy: HostPolicy) {
        synchronized(mutationLock) {
            synchronized(outboundWriteLock) {
                if (isSessionClosed()) return
                hostCommandAdapter?.setHostPolicy(policy)
                clipboardReads?.policyChanged()
            }
        }
    }

    /**
     * Updates the active terminal input policy dynamically.
     *
     * Serializes with outbound encoding and publishes the Backarrow default for
     * mode queries. This monitor protects encoding and admission only; a blocked
     * connector never holds it.
     * Encoder rejection propagates without changing the session policy or mode-report default.
     *
     * @param policy new input policy.
     */
    override fun setInputPolicy(policy: TerminalInputPolicy) {
        synchronized(outboundWriteLock) {
            if (isSessionClosed()) return
            inputEncoder.setInputPolicy(policy)
            inputPolicy = policy
            hostCommandAdapter?.setDefaultBackarrowSendsBackspace(
                policy.backspacePolicy == BackspacePolicy.BACKSPACE,
            )
        }
    }

    /**
     * Updates only the active paste control-character policy.
     *
     * Other host-bound input behavior, including PTY-specific Return handling,
     * is preserved. The update is serialized with input encoding because the
     * default encoder owns reusable scratch buffers.
     *
     * @param policy new control-character policy; bracketed-paste protection remains enabled.
     */
    public fun setPasteControlPolicy(policy: PasteControlPolicy) {
        synchronized(outboundWriteLock) {
            if (isSessionClosed()) return
            val next = inputPolicy.copy(pasteControlPolicy = policy)
            inputEncoder.setInputPolicy(next)
            inputPolicy = next
        }
    }

    private fun inputRejection(): TerminalInputAdmission? =
        when {
            isSessionClosed() -> TerminalInputAdmission.CLOSED
            state.value !== TerminalSessionState.Running -> TerminalInputAdmission.NOT_RUNNING
            else -> null
        }

    private inline fun admitInput(block: () -> Unit): TerminalInputAdmission =
        finishAdmission {
            synchronized(outboundWriteLock) {
                inputRejection() ?: run {
                    block()
                    TerminalInputAdmission.ACCEPTED
                }
            }
        }

    private inline fun finishAdmission(block: () -> TerminalInputAdmission): TerminalInputAdmission {
        try {
            val result = block()
            if (result == TerminalInputAdmission.ACCEPTED) outboundWriter.signalPending()
            return result
        } catch (failure: OutboundCapacityException) {
            failWrite(failure)
            return TerminalInputAdmission.CAPACITY_EXCEEDED
        }
    }

    /**
     * Copies exactly [length] bytes starting at [offset] into the ordered writer.
     * The caller must keep the range stable until return and may then reuse it.
     * Bytes are neither decoded nor sanitized, encoded, or framed as paste.
     * Use [submitInput] for keyboard, mouse, IME, or paste semantics.
     *
     * Concurrent producers are serialized at admission with keys, bulk input,
     * startup commands and parser replies. Empty ranges succeed only while
     * running and do not cancel startup. The byte queue is bounded to 8 MiB;
     * exceeding its remaining capacity fails the session without admitting any
     * of this range. Admission is not write completion: close discards pending
     * work and may interrupt an active write. Resize is synchronous and has no
     * position in this byte queue; it is not a flush or write barrier.
     *
     * @throws IllegalArgumentException for invalid slices, even before start or
     * after close. Validation is overflow-safe and does not change the session.
     */
    @JvmOverloads
    public fun submitBytes(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): TerminalInputAdmission {
        bytes.checkBounds(offset, length)
        return admitInput {
            outboundWriter.submit(signal = false) { outboundWriter.append(bytes, offset, length) }
            if (length != 0) {
                inputRevision++
                startupSubmission?.cancel(TerminalStartupCommandStatus.CANCELLED_BY_INPUT)
            }
        }
    }

    /**
     * Admits one immutable semantic event with explicit lifecycle/capacity feedback.
     * Keys, focus and mouse encode synchronously into the byte queue. Paste and
     * replacement retain source text and admission-time modes/policy for the
     * background encoder within the shared bulk budget (16 operations and
     * 16,777,216 UTF-16/deletion units, including active work).
     *
     * Mode/policy suppression and empty input still return ACCEPTED while running;
     * that result promises admission, not emitted bytes or transport completion.
     * Encoder argument failures propagate with staged bytes rolled back. Background
     * encoding/transport failures close the session through [state] and [failure].
     * Existing [TerminalInputEncoder] methods use this path and discard the result.
     * Mouse coordinates and replacement counts retain their event conventions.
     */
    public fun submitInput(event: TerminalInputEvent): TerminalInputAdmission =
        when (event) {
            is TerminalPasteEvent, is TerminalTextReplacementEvent ->
                admitInput {
                    val workUnits = event.workUnits()
                    enqueueTextInput(workUnits, event.cancelsStartup()) { encodeEvent(event) }
                    if (workUnits != 0L) inputRevision++
                }
            else ->
                admitInput {
                    outboundWriter.submit(signal = false) { inputEncoder.encodeEvent(event) }
                    inputRevision++
                    if (event.cancelsStartup()) startupSubmission?.cancel(TerminalStartupCommandStatus.CANCELLED_BY_INPUT)
                }
        }

    /**
     * Admits [events] as one indivisible outbound operation, copying the list before
     * return. Keep the list stable during the call; event values are immutable.
     * All events use one admission-time mode/policy snapshot on the background
     * encoder. Later keys, bytes and replies cannot interleave even when this
     * operation spans native writes. Closure/failure may still interrupt its prefix.
     *
     * At most 256 events fit in one compound operation. It shares the bulk budget
     * with paste/replacement, counting each event's retained text and deletion
     * work with a minimum of one unit. Over-budget batches fail closed before any event is
     * admitted. An empty batch is an accepted no-op only while running.
     *
     * This overload guarantees byte ordering only. Use a captured command-edit
     * context with the conditional overload to validate a shell revision.
     * Observe [state] to cancel response-dependent work on termination.
     */
    public fun submitInput(events: List<TerminalInputEvent>): TerminalInputAdmission = admitInput { enqueueCompoundInput(events) }

    private fun enqueueCompoundInput(events: List<TerminalInputEvent>) {
        if (events.size > MAX_COMPOUND_INPUT_EVENTS) throw OutboundCapacityException("Terminal input exceeds 256 events")
        if (events.isNotEmpty()) {
            val owned = events.toTypedArray()
            var workUnits = 0L
            var cancelStartup = false
            for (event in owned) {
                workUnits += maxOf(1L, event.workUnits())
                cancelStartup = cancelStartup || event.cancelsStartup()
            }
            enqueueTextInput(workUnits, cancelStartup) {
                for (event in owned) encodeEvent(event)
            }
            inputRevision++
        }
    }

    private fun TerminalInputEvent.workUnits(): Long =
        when (this) {
            is TerminalKeyEvent -> associatedText?.length?.toLong() ?: 0L
            is TerminalPasteEvent -> text.length.toLong()
            is TerminalTextReplacementEvent -> replacementText.length.toLong() + deleteAfterCursorCount + deleteBeforeCursorCount
            is TerminalFocusEvent, is TerminalMouseEvent -> 0L
        }

    private fun TerminalInputEvent.cancelsStartup(): Boolean =
        when (this) {
            is TerminalKeyEvent -> type != TerminalKeyEventType.RELEASE
            is TerminalPasteEvent -> text.isNotEmpty()
            is TerminalTextReplacementEvent -> true
            is TerminalFocusEvent, is TerminalMouseEvent -> false
        }

    private fun TerminalInputEncoder.encodeEvent(event: TerminalInputEvent) {
        when (event) {
            is TerminalKeyEvent -> encodeKey(event)
            is TerminalPasteEvent -> encodePaste(event)
            is TerminalTextReplacementEvent -> encodeTextReplacement(event)
            is TerminalFocusEvent -> encodeFocus(event)
            is TerminalMouseEvent -> encodeMouse(event)
        }
    }

    private inline fun enqueueTextInput(
        workUnits: Long,
        cancelStartup: Boolean,
        crossinline encode: TerminalInputEncoder.() -> Unit,
    ) {
        if (workUnits != 0L) {
            val modeBits = terminal.getInputModeBits()
            val policy = inputPolicy
            outboundWriter.submitBulk(workUnits, signal = false) {
                bulkInputModeBits = modeBits
                bulkInputEncoder.setInputPolicy(policy)
                bulkInputEncoder.encode()
            }
        }
        if (cancelStartup) startupSubmission?.cancel(TerminalStartupCommandStatus.CANCELLED_BY_INPUT)
    }

    /**
     * Clears the active screen and its history without sending input to the connector.
     *
     * Call from any thread outside a frame lease or output callback.
     * The mutation lock orders this operation with output and resize.
     * Admission succeeds before start and while running. Closure returns false without changing retained output.
     * An operation admitted before closure can finish.
     *
     * The cursor position, modes, pen, margins, tab stops, saved cursor, and inactive buffer remain unchanged.
     * Pending wrap is cancelled. Blank cells use the current erase attributes.
     * New line identities invalidate old anchors. Applied frames invalidate selection and refresh search.
     * The selected shell producer receives [TerminalShellIntegration.bufferCleared] under mutation serialization.
     * Host-owned metadata remains host-owned; its old line identities no longer resolve.
     *
     * This operation has no position in the outbound queue and does not wait for pending writes.
     * Parser state remains intact, including incomplete escape sequences and UTF-8 input.
     * Collaborator failures propagate after any completed mutation; render invalidation still runs.
     *
     * @return true when clearing was admitted, or false when closure has begun.
     * @throws IllegalStateException on reentry from an output or clear callback.
     */
    public fun clearBuffer(): Boolean {
        try {
            synchronized(mutationLock) {
                if (isSessionClosed()) return false
                check(!processingOutput) { "Output callbacks must not reenter the session" }
                outputRevision++
                processingOutput = true
                try {
                    terminal.eraseBuffer()
                    var buffer: TerminalRenderBufferKind? = null
                    renderReader.readRenderFrame { frame -> buffer = frame.activeBuffer }
                    runtime.shellIntegration?.bufferCleared(
                        checkNotNull(buffer) { "Render reader did not expose the cleared terminal frame" },
                    )
                } finally {
                    processingOutput = false
                }
            }
            return true
        } finally {
            invalidateRender()
        }
    }

    override fun encodeKey(event: TerminalKeyEvent) {
        submitInput(event)
    }

    override fun encodePaste(event: TerminalPasteEvent) {
        submitInput(event)
    }

    override fun encodeTextReplacement(event: TerminalTextReplacementEvent) {
        submitInput(event)
    }

    override fun encodeFocus(event: TerminalFocusEvent) {
        submitInput(event)
    }

    override fun encodeMouse(event: TerminalMouseEvent) {
        submitInput(event)
    }

    /**
     * Consumes host bytes synchronously, mutating parser/core before returning.
     * Output callbacks must not reenter this operation.
     * Parser or callback failures stop the input call and propagate unchanged.
     * The connector must stop delivery and report the failure through [onError].
     */
    override fun onBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        bytes.checkBounds(offset, length)
        if (isSessionClosed()) return

        try {
            synchronized(mutationLock) {
                if (isSessionClosed()) return
                check(!processingOutput) { "Output callbacks must not reenter the session" }
                if (length != 0) outputRevision++
                processingOutput = true
                try {
                    parser.accept(bytes, offset, length)
                    runtime.shellIntegration?.outputProcessed()
                    drainResponses()
                } finally {
                    processingOutput = false
                }
                submitStartupCommand()
            }
        } catch (failure: OutboundCapacityException) {
            failWrite(failure)
        }
        invalidateRender(immediate = false)
    }

    private fun submitStartupCommand() {
        val submission = startupSubmission
        if (submission?.status?.value == TerminalStartupCommandStatus.WAITING) {
            synchronized(mutationLock) {
                if (processingOutput || runtime.shellIntegration?.promptReady?.value != true) return
                outboundWriter.submit {
                    if (isAcceptingInput()) {
                        renderReader.readRenderFrame { frame ->
                            if (frame.activeBuffer == TerminalRenderBufferKind.PRIMARY) {
                                submission.submit(inputEncoder)
                                if (submission.status.value == TerminalStartupCommandStatus.SUBMITTED) inputRevision++
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Invalidates the current requested viewport without changing its offset or
     * row count.
     */
    private fun invalidateRender(immediate: Boolean = true) {
        if (isSessionClosed()) return
        pendingRenderGeneration.incrementAndGet()
        renderRequests.trySend(Unit)
        if (immediate) immediateRenderRequests.trySend(Unit)
    }

    /**
     * Requests a render-cache publication for a caller-owned scrollback viewport.
     *
     * The offset is transient render request state, not terminal state. UI
     * layers own scrollback policy and pass the desired offset for each
     * publication they need. A newer request replaces the active render
     * viewport for this session.
     *
     * @param scrollbackOffset logical whole-row offset from live viewport.
     */
    public fun requestRender(scrollbackOffset: Int) {
        requestRender(scrollbackOffset, viewportRows = 0)
    }

    /**
     * Requests a render-cache publication with optional render-only viewport
     * overscan rows.
     *
     * [viewportRows] greater than zero asks the render reader to expose that
     * many rows when possible. This is UI composition state and must not resize
     * the terminal or connector.
     *
     * @param scrollbackOffset logical whole-row offset from live viewport.
     * @param viewportRows row count requested from the render cache.
     */
    public fun requestRender(
        scrollbackOffset: Int,
        viewportRows: Int,
    ) {
        if (isSessionClosed()) return
        pendingRenderRequest.set(
            packRenderRequest(
                scrollbackOffset = scrollbackOffset.coerceAtLeast(0),
                viewportRows = viewportRows.coerceAtLeast(0),
            ),
        )
        invalidateRender()
    }

    private fun scheduleSynchronizedOutputTimeout() {
        if (synchronizedTimeoutJob.get() != null || isSessionClosed()) return

        val timeoutJob =
            sessionScope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(SYNCHRONIZED_OUTPUT_TIMEOUT_MS.milliseconds)
                    var changed = false
                    synchronized(mutationLock) {
                        if (terminal.getModeSnapshot().isSynchronizedOutput) {
                            terminal.setSynchronizedOutput(false)
                            changed = true
                        }
                    }
                    if (changed) invalidateRender()
                } finally {
                    synchronizedTimeoutJob.compareAndSet(currentCoroutineContext().job, null)
                }
            }

        if (synchronizedTimeoutJob.compareAndSet(null, timeoutJob)) {
            timeoutJob.start()
        } else {
            timeoutJob.cancel(CancellationException("Synchronized output timeout superseded"))
        }
    }

    private fun cancelSynchronizedOutputTimeout() {
        synchronizedTimeoutJob
            .getAndSet(null)
            ?.cancel(CancellationException("Synchronized output timeout cancelled"))
    }

    private suspend fun drainRenderRequests() {
        var publishedGeneration = mutableRenderGeneration.value
        val failedGeneration = NO_RENDER_GENERATION
        while (!isSessionClosed()) {
            currentCoroutineContext().ensureActive()
            immediateRenderRequests.tryReceive()
            val generation = pendingRenderGeneration.get()
            if (generation == publishedGeneration || generation == failedGeneration) return

            val request = pendingRenderRequest.get()
            val offset = unpackScrollbackOffset(request)
            val rows = unpackViewportRows(request)

            val modeSnapshot = terminal.getModeSnapshot()
            if (modeSnapshot.isSynchronizedOutput) {
                scheduleSynchronizedOutputTimeout()
                return
            }

            cancelSynchronizedOutputTimeout()

            val context = currentCoroutineContext()
            try {
                synchronized(mutationLock) {
                    if (isSessionClosed()) return
                    runtime.publisher.updateAndPublish(this, offset, rows)
                    publishedGeneration = generation
                    context.ensureActive()
                    mutableRenderGeneration.value = generation
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }

            // The first publication after an idle period is immediate. While
            // host output remains active, wait one display interval before
            // sampling the packed request and generation again. Explicit UI
            // requests interrupt the wait so scrolling and resizing stay
            // responsive. The last host invalidation becomes a trailing frame.
            withTimeoutOrNull(RENDER_PUBLICATION_INTERVAL_MS.milliseconds) {
                immediateRenderRequests.receiveCatching()
            }
        }
    }

    /**
     * Reads a short-lived render frame while holding the terminal mutation lock.
     *
     * UI callers should use this session-level reader rather than reading the
     * core buffer directly, so parser output and resize cannot mutate the grid
     * while a renderer copies primitive row data.
     * Frames and their backing arrays are borrowed for the callback only.
     * The callback must not block on UI work, close the session, or reenter mutation.
     *
     * @param consumer the frame consumer to invoke.
     */
    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
        readRenderFrame(scrollbackOffset = 0, consumer = consumer)
    }

    /**
     * Reads a render frame with a specified scrollback offset.
     *
     * @param scrollbackOffset whole-row offset from the live viewport.
     * @param consumer the frame consumer to invoke.
     */
    override fun readRenderFrame(
        scrollbackOffset: Int,
        consumer: TerminalRenderFrameConsumer,
    ) {
        synchronized(mutationLock) {
            renderReader.readRenderFrame(scrollbackOffset, consumer)
        }
    }

    /**
     * Reads a render frame with a specified scrollback offset and viewport rows limit.
     *
     * @param scrollbackOffset whole-row offset from the live viewport.
     * @param viewportRows row count requested.
     * @param consumer the frame consumer to invoke.
     */
    override fun readRenderFrame(
        scrollbackOffset: Int,
        viewportRows: Int,
        consumer: TerminalRenderFrameConsumer,
    ) {
        synchronized(mutationLock) {
            renderReader.readRenderFrame(scrollbackOffset, viewportRows, consumer)
        }
    }

    /**
     * Reads an absolute terminal row range while holding the mutation lock.
     *
     * Resolving absolute rows and copying their frame are one atomic operation,
     * so incoming output cannot shift a selection onto unrelated scrollback
     * rows between coordinate conversion and frame access.
     *
     * @param startAbsoluteRow inclusive first requested absolute row.
     * @param endAbsoluteRow inclusive last requested absolute row.
     * @param consumer the frame consumer to invoke.
     */
    override fun readRenderFrameForAbsoluteRange(
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        consumer: TerminalRenderFrameConsumer,
    ) {
        synchronized(mutationLock) {
            renderReader.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
        }
    }

    /**
     * Records remote closure, releases the connector, and finalizes parser/render
     * state exactly once. The connector must deliver its final bytes first.
     */
    override fun onClosed(exitCode: Int?) {
        transitionToClosed(TerminalSessionCloseEvent(exitCode = exitCode, failure = null, locallyRequested = false))
    }

    /**
     * Records transport failure and treats it as remote closure without a
     * process exit code.
     *
     * @param error the transport failure exception.
     */
    override fun onError(error: Throwable) {
        transitionToClosed(TerminalSessionCloseEvent(exitCode = null, failure = error, locallyRequested = false))
    }

    /**
     * Stops accepting input, releases the connector, flushes parser EOF, and
     * synchronously publishes the last requested viewport, including synchronized
     * output. Publication waits for active frame-copy callbacks and available
     * render buffers; do not call from a render-reader or leased-cache callback.
     *
     * The first termination owns cleanup and its event is retained. Concurrent or
     * reentrant calls return without waiting; observe [state] for completed cleanup.
     * Every cleanup is attempted. Local cleanup failures are rethrown with later
     * failures suppressed; failure-triggered cleanup adds them to the original cause.
     */
    override fun close() {
        transitionToClosed(
            event = TerminalSessionCloseEvent(exitCode = null, failure = null, locallyRequested = true),
        )
    }

    private fun drainResponses() {
        synchronized(mutationLock) {
            outboundWriter.submit {
                while (!isSessionClosed()) {
                    val count = responseReader.readResponseBytes(responseScratch, 0, responseScratch.size)
                    if (count <= 0) break
                    outboundWriter.append(responseScratch, 0, count)
                }
            }
        }
    }

    private fun failWrite(failure: Exception) {
        transitionToClosed(
            TerminalSessionCloseEvent(exitCode = null, failure = failure, locallyRequested = false),
        )
    }

    private fun transitionToClosed(event: TerminalSessionCloseEvent) {
        if (!closingEvent.compareAndSet(null, event)) return
        var failure: Throwable? = event.failure

        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) {
                    failure = error
                } else if (previous !== error) {
                    previous.addSuppressed(error)
                }
            }
        }

        try {
            cleanup {
                synchronized(connectorLifecycleLock) {
                    synchronized(connectorResizeLock) { connector.close() }
                }
            }
            cleanup { clipboardReads?.close() }
            cleanup { outboundWriter.close() }
            cleanup {
                synchronized(outboundWriteLock) {
                    startupSubmission?.cancel(TerminalStartupCommandStatus.CLOSED)
                }
            }
            cancelSynchronizedOutputTimeout()
            renderRequests.close()
            immediateRenderRequests.close()
            sessionScope.cancel(CancellationException("Terminal session closed"))
            // Serialize against an in-flight publication so it cannot replace
            // this final frame after EOF has been flushed.
            synchronized(mutationLock) {
                cleanup { parser.endOfInput() }
                cleanup {
                    val request = pendingRenderRequest.get()
                    runtime.publisher.updateAndPublish(this, unpackScrollbackOffset(request), unpackViewportRows(request))
                    mutableRenderGeneration.value = pendingRenderGeneration.incrementAndGet()
                }
            }
        } finally {
            mutableState.value = TerminalSessionState.Closed(event)
        }
        if (event.failure == null) failure?.let { throw it }
    }

    private fun isSessionClosed(): Boolean = closingEvent.get() != null

    private fun isAcceptingInput(): Boolean = !isSessionClosed() && state.value === TerminalSessionState.Running

    public companion object {
        private val SESSION_COUNTER =
            AtomicInteger(1)
        private const val RESPONSE_BUFFER_SIZE: Int = 1024
        private const val MAX_COMPOUND_INPUT_EVENTS: Int = 256
        private const val NO_RENDER_GENERATION: Long = -1L
        private const val NO_SHELL_COMMAND_LINE_REVISION: Long = -1L
        internal const val RENDER_PUBLICATION_INTERVAL_MS: Long = 16L
        private const val SYNCHRONIZED_OUTPUT_TIMEOUT_MS: Long = 100L

        private fun packRenderRequest(
            scrollbackOffset: Int,
            viewportRows: Int,
        ): Long = (scrollbackOffset.toLong() shl 32) or (viewportRows.toLong() and 0xffff_ffffL)

        private fun unpackScrollbackOffset(request: Long): Int = (request ushr 32).toInt()

        private fun unpackViewportRows(request: Long): Int = request.toInt()

        /**
         * Assembles a production session from a buffer exposing core and render roles.
         *
         * Uses the same services and customization factories as the separate-reader
         * overload. Ownership transfers only after successful construction.
         * Custom factories must not perform I/O or start jobs during construction.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            terminal: TerminalRenderBuffer,
            connector: TerminalConnector,
            hostEvents: HostEventSink = HostEventSink.NONE,
            hostPolicy: HostPolicy = HostPolicy(),
            inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
            kittyKeyboardSupportedFlags: Int = KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK,
            workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
            startupCommand: TerminalStartupCommand? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            modeReportCapabilities: Int = 0,
            clipboardReader: TerminalClipboardReader? = null,
            clipboardReadTimeSource: TimeSource = TimeSource.Monotonic,
            shellIntegration: TerminalShellIntegrationFactory? = null,
            inputEncoderFactory: TerminalInputEncoderFactory? = null,
            parserFactory: TerminalOutputParserFactory? = null,
        ): TerminalSession =
            create(
                terminal = terminal,
                renderReader = terminal,
                connector = connector,
                hostEvents = hostEvents,
                hostPolicy = hostPolicy,
                inputPolicy = inputPolicy,
                kittyKeyboardSupportedFlags = kittyKeyboardSupportedFlags,
                workerDispatcher = workerDispatcher,
                startupCommand = startupCommand,
                ioDispatcher = ioDispatcher,
                modeReportCapabilities = modeReportCapabilities,
                clipboardReader = clipboardReader,
                clipboardReadTimeSource = clipboardReadTimeSource,
                shellIntegration = shellIntegration,
                inputEncoderFactory = inputEncoderFactory,
                parserFactory = parserFactory,
            )

        /**
         * Assembles core and render collaborators with session-owned host services.
         *
         * Dimensions are validated before factory calls or connector ownership
         * transfer. The collaborators must describe the same terminal state; equal
         * dimensions alone cannot establish that relationship. Factory failures
         * leave the connector with its caller and start no session workers.
         *
         * @param terminal core buffer transferred to the session's exclusive runtime ownership.
         * @param renderReader render projection of the same core state.
         * @param connector transport connector transferred to the session; closed on every terminal lifecycle path.
         * @param hostEvents metadata events target.
         * @param hostPolicy safety policy.
         * @param inputPolicy key-encoding policy.
         * @param modeReportCapabilities implemented host actions from TerminalHostModeCapability; defaults to none.
         * @param kittyKeyboardSupportedFlags progressive Kitty keyboard flags
         * the active input host can provide truthfully. Defaults to the
         * conservative portable-host profile.
         * @param workerDispatcher non-owned dispatcher used for render publication and timeouts.
         * @param startupCommand optional command submitted once after the selected integration reports prompt readiness.
         * User input before readiness cancels submission.
         * @param ioDispatcher non-owned dispatcher for connector writes, metadata queries, and clipboard providers.
         * @param clipboardReader session-bound clipboard/consent operation, or null for unavailable reads.
         * @param clipboardReadTimeSource monotonic elapsed-time source for clipboard provider and reply-commit deadline checks.
         *   Deterministic tests should share its time domain with the worker dispatcher's delay scheduler.
         *   This does not change the fixed read timeout or other session timers.
         * @param shellIntegration sole shell metadata producer; null leaves shell features unavailable.
         * @param inputEncoderFactory creates independent admission and bulk encoders bound to session-owned output.
         * @param parserFactory customizes parsing using the assembled host sink and its live clipboard budget.
         * Pass both to [TerminalParsers.create] when adding a custom OSC handler.
         * Its callback runs under mutation serialization and may read frames before later output.
         * It must not call mutating session APIs or close the session.
         * @throws IllegalArgumentException when [startupCommand] is supplied without [shellIntegration].
         * @throws IllegalArgumentException when the initial render frame is absent or has incompatible dimensions,
         * or the input factory reuses one encoder instance.
         * @return standard production terminal session.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            terminal: TerminalBuffer,
            renderReader: TerminalRenderFrameReader,
            connector: TerminalConnector,
            hostEvents: HostEventSink = HostEventSink.NONE,
            hostPolicy: HostPolicy = HostPolicy(),
            inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
            kittyKeyboardSupportedFlags: Int = KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK,
            workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
            startupCommand: TerminalStartupCommand? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            modeReportCapabilities: Int = 0,
            clipboardReader: TerminalClipboardReader? = null,
            clipboardReadTimeSource: TimeSource = TimeSource.Monotonic,
            shellIntegration: TerminalShellIntegrationFactory? = null,
            inputEncoderFactory: TerminalInputEncoderFactory? = null,
            parserFactory: TerminalOutputParserFactory? = null,
        ): TerminalSession {
            require(startupCommand == null || shellIntegration != null) {
                "startupCommand requires a shell integration that supplies prompt readiness"
            }
            val outboundWriteLock = Any()
            var suppliedFrame = false
            renderReader.readRenderFrame { frame ->
                require(frame.columns == terminal.width && frame.rows == terminal.height) {
                    "renderReader dimensions must match terminal dimensions"
                }
                suppliedFrame = true
            }
            require(suppliedFrame) { "renderReader must supply an initial frame" }
            val renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height)
            val runtime = SessionRuntime(renderReader, renderPublisher, shellIntegration)
            val recordingHostEvents =
                SessionHostEventSink(
                    delegate = hostEvents,
                    shellIntegration = runtime.shellIntegration,
                )
            val sink =
                HostCommandAdapter(
                    terminal,
                    recordingHostEvents,
                    hostPolicy,
                    kittyKeyboardSupportedFlags,
                    modeReportCapabilities = modeReportCapabilities,
                    defaultBackarrowSendsBackspace = inputPolicy.backspacePolicy == BackspacePolicy.BACKSPACE,
                )
            val parser =
                parserFactory?.create(sink, sink::clipboardWriteLimitBytes)
                    ?: TerminalParsers.create(sink, clipboardWriteLimitBytes = sink::clipboardWriteLimitBytes)

            val session =
                TerminalSession(
                    terminal = terminal,
                    runtime = runtime,
                    responseReader = terminal,
                    connector = connector,
                    parser = parser,
                    inputEncoderFactory = inputEncoderFactory,
                    hyperlinkResolver = TerminalHyperlinkResolver(sink::hyperlinkUri),
                    outboundWriteLock = outboundWriteLock,
                    hostCommandAdapter = sink,
                    inputPolicy = inputPolicy,
                    workerDispatcher = workerDispatcher,
                    ioDispatcher = ioDispatcher,
                    startupCommand = startupCommand,
                )
            val clipboardReads =
                ClipboardReadHandler(
                    scope = session.sessionScope,
                    ioDispatcher = ioDispatcher,
                    lock = outboundWriteLock,
                    writer = session.outboundWriter,
                    output = { session.streamingOutput },
                    reader = clipboardReader,
                    policy = { sink.currentPolicy },
                    isClosed = session::isSessionClosed,
                    audit = hostEvents::terminalClipboardReadCompleted,
                    timeSource = clipboardReadTimeSource,
                )
            session.clipboardReads = clipboardReads
            recordingHostEvents.resizeConnector = session::resizeConnector
            recordingHostEvents.clipboardReadRequest = { request ->
                session.drainResponses()
                clipboardReads.request(request)
            }
            return session
        }
    }
}

private class SessionRuntime(
    val reader: TerminalRenderFrameReader,
    val publisher: TerminalRenderPublisher,
    factory: TerminalShellIntegrationFactory?,
) {
    val mutationLock = Any()
    val renderGeneration = MutableStateFlow(-1L)
    val shellIntegration =
        factory?.create(
            TerminalShellIntegrationContext(mutationLock, reader, publisher, renderGeneration.asStateFlow()),
        )
    val shellState = shellIntegration?.state ?: TerminalShellIntegrationState()
}

private class SessionHostEventSink(
    private val delegate: HostEventSink,
    private val shellIntegration: TerminalShellIntegration?,
) : HostEventSink {
    var resizeConnector: ((Int, Int) -> Unit)? = null
    var clipboardReadRequest: ((TerminalClipboardReadRequest) -> Unit)? = null

    override fun terminalClipboardReadRequested(request: TerminalClipboardReadRequest) {
        checkNotNull(clipboardReadRequest).invoke(request)
    }

    override fun paletteChanged(palette: TerminalColorPalette) = delegate.paletteChanged(palette)

    override fun hyperlinkRegistered(
        hyperlinkId: Int,
        uri: String,
        id: String?,
    ) = delegate.hyperlinkRegistered(hyperlinkId, uri, id)

    override fun hyperlinkRemoved(hyperlinkId: Int) = delegate.hyperlinkRemoved(hyperlinkId)

    override fun hyperlinksCleared() = delegate.hyperlinksCleared()

    override fun bell() {
        delegate.bell()
    }

    override fun iconTitleChanged(title: String) {
        delegate.iconTitleChanged(title)
    }

    override fun windowTitleChanged(title: String) {
        delegate.windowTitleChanged(title)
    }

    override fun currentWorkingDirectoryChanged(uri: String) {
        shellIntegration?.observeWorkingDirectory(uri)
        delegate.currentWorkingDirectoryChanged(uri)
    }

    override fun resizeWindow(
        rows: Int,
        columns: Int,
    ) {
        delegate.resizeWindow(rows, columns)
    }

    override fun resizeForColumnMode(
        rows: Int,
        columns: Int,
    ) {
        checkNotNull(resizeConnector).invoke(columns, rows)
    }

    override fun columnModeChanged(
        rows: Int,
        columns: Int,
    ) {
        delegate.columnModeChanged(rows, columns)
    }

    override fun moveWindow(
        x: Int,
        y: Int,
    ) {
        delegate.moveWindow(x, y)
    }

    override fun minimizeWindow() {
        delegate.minimizeWindow()
    }

    override fun deminimizeWindow() {
        delegate.deminimizeWindow()
    }

    override fun raiseWindow() {
        delegate.raiseWindow()
    }

    override fun lowerWindow() {
        delegate.lowerWindow()
    }

    override fun setMaximized(maximize: Boolean) {
        delegate.setMaximized(maximize)
    }

    override fun shellIntegrationMarker(event: ShellIntegrationEvent) {
        shellIntegration?.observeShellMarker(event)
        delegate.shellIntegrationMarker(event)
    }

    override fun showNotification(
        title: String,
        body: String,
        level: NotificationLevel,
    ) {
        delegate.showNotification(title, body, level)
    }

    override fun terminalClipboardRequest(event: TerminalClipboardAuditEvent) {
        delegate.terminalClipboardRequest(event)
    }

    override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
        delegate.terminalClipboardWrite(event)
    }

    override fun terminalClipboardPrompt(event: TerminalClipboardPromptEvent) {
        delegate.terminalClipboardPrompt(event)
    }
}
