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

import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.util.Disposer
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.intellij.ui.IntellijTerminalClipboardHandler
import io.github.ketraterm.session.TerminalClipboardReadResult
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.host.SwingClipboardReadPrompt
import io.github.ketraterm.ui.swing.host.SwingClipboardReader
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Clipboard identity and pane readiness for one terminal, retired with its IDE client. */
internal class IntellijClipboardSession(
    private val projectClient: IntellijClipboardClient,
    private val applicationClient: IntellijClipboardClient,
    private val session: TerminalSession,
    private val reader: SwingClipboardReader,
    val clipboard: TerminalClipboardHandler = IntellijTerminalClipboardHandler(applicationClient),
) : Disposable {
    // DialogWrapper show/dispose require write-intent access, which Dispatchers.UI forbids.
    // Keep writes, reads and consent cleanup on one dispatcher to preserve event ordering.
    @Suppress("ObsoleteDispatchersEdt")
    private val uiContext = Dispatchers.EDT + ModalityState.nonModal().asContextElement() + projectClient.clientId.asContextElement()
    private val paneReady = CompletableDeferred<SwingClipboardReadPrompt>()
    private val disposed = AtomicBoolean()
    private val applicationLifetime = Disposable { session.close() }

    val isAlive: Boolean get() = !disposed.get() && projectClient.isAlive && applicationClient.isAlive

    init {
        require(projectClient.clientId == applicationClient.clientId) { "Clipboard owners must belong to the same IDE client" }
        Disposer.register(projectClient, this)
        Disposer.register(applicationClient, applicationLifetime)
    }

    /** Publishes consent UI only after the owning pane has been registered. */
    fun attach(prompt: SwingClipboardReadPrompt) {
        check(SwingUtilities.isEventDispatchThread())
        check(isAlive) { "Terminal clipboard client closed before pane attachment" }
        check(paneReady.complete(prompt)) { "Terminal clipboard pane already attached" }
    }

    /** Posts allowed writes independently of pane creation, in event order. */
    fun write(text: String) {
        postIfAlive { clipboard.copyText(text) }
    }

    /** Posts clipboard UI under the owning client rather than the parser thread's ambient client. */
    fun postIfAlive(action: () -> Unit) {
        projectClient.coroutineScope.launch(uiContext) {
            if (isAlive) {
                projectClient.checkCurrent()
                action()
            }
        }
    }

    /** The caller's session deadline covers readiness, consent, and native access. */
    suspend fun read(
        request: TerminalClipboardReadRequest,
        message: String,
    ): TerminalClipboardReadResult =
        withContext(uiContext) {
            val prompt = paneReady.await()
            if (!isAlive) throw CancellationException("Terminal clipboard client closed")
            projectClient.checkCurrent()
            val result = reader.read(request, prompt, message, clipboard)
            projectClient.checkCurrent()
            result
        }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        paneReady.cancel(CancellationException("Terminal clipboard session closed"))
        // Either owner closes the actual session, also revoking replies queued for output.
        // Disposing the registration here removes the application's reference to this tab.
        Disposer.dispose(applicationLifetime)
    }
}
