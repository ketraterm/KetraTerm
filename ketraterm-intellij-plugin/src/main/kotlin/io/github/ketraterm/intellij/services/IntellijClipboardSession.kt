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

import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.client.ClientProjectSession
import com.intellij.openapi.util.Disposer
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.intellij.ui.IntellijTerminalClipboardHandler
import io.github.ketraterm.session.TerminalClipboardReadResult
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.host.SwingClipboardReadPrompt
import io.github.ketraterm.ui.swing.host.SwingClipboardReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Clipboard identity and pane readiness for one terminal, retired with its IDE client. */
internal class IntellijClipboardSession(
    private val client: ClientProjectSession,
    private val session: TerminalSession,
    private val reader: SwingClipboardReader,
) : Disposable {
    val clipboard = IntellijTerminalClipboardHandler(client.appSession)
    private val paneReady = CompletableDeferred<SwingClipboardReadPrompt>()
    private val disposed = AtomicBoolean()

    val isAlive: Boolean get() = !disposed.get() && !client.isDisposed && !client.appSession.isDisposed

    init {
        Disposer.register(client, this)
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
        ClientId.withExplicitClientId(client.clientId) {
            ApplicationManager.getApplication().invokeLater({
                if (isAlive) action()
            }, ModalityState.nonModal())
        }
    }

    /** The caller's session deadline covers readiness, consent, and native access. */
    suspend fun read(
        request: TerminalClipboardReadRequest,
        message: String,
    ): TerminalClipboardReadResult =
        withContext(Dispatchers.UI + ModalityState.nonModal().asContextElement() + client.clientId.asContextElement()) {
            val prompt = paneReady.await()
            if (!isAlive) throw CancellationException("Terminal clipboard client closed")
            reader.read(request, prompt, message, clipboard)
        }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        paneReady.cancel(CancellationException("Terminal clipboard session closed"))
        // Closing the actual session also revokes replies already queued for output.
        session.close()
    }
}
