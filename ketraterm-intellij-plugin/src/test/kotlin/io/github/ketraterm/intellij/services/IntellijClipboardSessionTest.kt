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
import com.intellij.ide.ClientCopyPasteManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.client.ClientAppSession
import com.intellij.openapi.client.ClientProjectSession
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.*
import io.github.ketraterm.intellij.ui.IntellijTerminalClipboardHandler
import io.github.ketraterm.session.TerminalClipboardReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.host.SwingClipboardReadPrompt
import io.github.ketraterm.ui.swing.host.SwingClipboardReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Real IDE dispatch and session byte streams, with per-client clipboard services replaced by fakes. */
class IntellijClipboardSessionTest : BasePlatformTestCase() {
    private lateinit var lifetime: Disposable

    override fun setUp() {
        super.setUp()
        lifetime = Disposer.newDisposable(testRootDisposable, "clipboard fixture")
    }

    override fun tearDown() {
        try {
            Disposer.dispose(lifetime)
        } finally {
            super.tearDown()
        }
    }

    fun testStartupWriteCompletesBeforePaneAttachmentAndFollowingReadSeesIt() {
        val client = Client("startup", "old")
        val f = Fixture(client)
        f.connector.feedFromHost("\u001b]52;c;bmV3\u0007\u001b]52;c;?\u0007".toByteArray())
        await("startup write", client.written)
        await("read provider entry", f.entered)
        assertEquals("new", client.text)
        assertEquals(client.id, client.writeClient)
        assertEquals(0, client.reads.get())
        assertFalse(f.completed.isCompleted)
        f.attach()
        await("read response", f.completed)
        assertEquals("\u001b]52;c;bmV3\u001b\\", f.output())
        assertEquals(1, client.reads.get())
    }

    fun testAttachedPaneReadCannotOvertakeAnEarlierPostedWrite() {
        val f = Fixture(Client("attached", "old"))
        f.attach()
        f.connector.feedFromHost("\u001b]52;c;bmV3\u0007\u001b]52;c;?\u0007".toByteArray())
        await("ordered write and read", f.completed)
        assertEquals("\u001b]52;c;bmV3\u001b\\", f.output())
    }

    fun testCancelledReadBeforeAttachmentCannotShowConsentOrReadLater() {
        val client = Client("closed-before-attach", "secret")
        val f = Fixture(client, TerminalClipboardPermission.PROMPT)
        f.query()
        await("read provider entry", f.entered)
        f.session.close()
        await("cancelled request", f.completed)
        f.attach()
        assertFalse(f.prompt.component.isVisible)
        assertEquals(0, client.reads.get())
        assertEquals("", f.output())
    }

    fun testAskIsBoundToItsClientAndAnotherPaneCannotReplaceConsent() {
        val reader = SwingClipboardReader()
        val first = Fixture(Client("first", "first-client"), TerminalClipboardPermission.PROMPT, reader)
        val second = Fixture(Client("second", "second-client"), TerminalClipboardPermission.PROMPT, reader)
        first.attach()
        second.attach()
        first.query()
        waitFor("first consent") { first.prompt.component.isVisible }
        assertEquals(0, first.client.reads.get())
        second.query()
        await("second query denial", second.completed)
        assertEquals("\u001b]52;c;\u001b\\", second.output())
        assertFalse(second.prompt.component.isVisible)
        assertTrue(first.prompt.component.isVisible)
        click(first.prompt, "Allow once")
        await("first query approval", first.completed)
        assertEquals("\u001b]52;c;Zmlyc3QtY2xpZW50\u001b\\", first.output())
        assertEquals(1, first.client.reads.get())
        assertEquals(0, second.client.reads.get())
    }

    fun testClientDisposalDismissesConsentAndClosesTheOwningSession() {
        val client = Client("departing", "secret")
        val f = Fixture(client, TerminalClipboardPermission.PROMPT)
        f.attach()
        f.query()
        waitFor("consent") { f.prompt.component.isVisible }
        Disposer.dispose(client.projectSession)
        waitFor("consent cancellation") { !f.prompt.component.isVisible }
        await("request retirement", f.completed)
        click(f.prompt, "Allow once")
        assertTrue(f.session.state.value is TerminalSessionState.Closed)
        assertFalse(f.binding.isAlive)
        assertEquals(0, client.reads.get())
        assertEquals("", f.output())
    }

    fun testQueuedWriteIsDiscardedWhenClientIsDisposedBeforeEdtDelivery() {
        val client = Client("closed-write", "old")
        val f = Fixture(client)
        f.binding.write("must-not-be-written")
        Disposer.dispose(client.projectSession)
        val barrier = CompletableDeferred<Unit>()
        SwingUtilities.invokeLater { barrier.complete(Unit) }
        await("EDT write barrier", barrier)
        assertEquals("old", client.text)
        assertFalse(client.written.isCompleted)
    }

    fun testClientDisposalRetiresANativeReadThatReturnsLater() {
        val client = Client("blocked-owner", "secret")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        client.onRead = {
            entered.complete(Unit)
            release.await()
            "late-secret"
        }
        val f = Fixture(client)
        f.attach()
        f.query()
        try {
            await("native read entered", entered)
            Disposer.dispose(client.projectSession)
            assertTrue(f.session.state.value is TerminalSessionState.Closed)
        } finally {
            release.countDown()
        }
        await("native provider returned after disposal", f.providerDone)
        assertEquals("", f.output())
    }

    fun testCapturedClientDoesNotFollowAmbientClientAndPrimaryDoesNotAliasClipboard() {
        val owner = Client("owner", "owner-clipboard")
        val other = Client("other", "other-clipboard")
        val clipboard = IntellijTerminalClipboardHandler(owner.appSession)
        ClientId.withExplicitClientId(other.id) { clipboard.copyText("owner-update") }
        assertEquals("owner-update", owner.text)
        assertEquals("other-clipboard", other.text)
        runBlocking(Dispatchers.IO) {
            assertEquals("owner-update", clipboard.readText())
            assertNull(clipboard.readPrimarySelectionText())
            owner.primary = ""
            assertEquals("", clipboard.readPrimarySelectionText())
            owner.appDisposed.set(true)
            try {
                clipboard.readText()
                fail("Disposed client must not read a replacement or local clipboard")
            } catch (_: CancellationException) {
                // Required provider cancellation, not an unavailable result.
            }
        }
        assertEquals(1, owner.reads.get())
        assertEquals(0, other.reads.get())
    }

    private inner class Fixture(
        val client: Client,
        permission: TerminalClipboardPermission = TerminalClipboardPermission.ALLOW,
        reader: SwingClipboardReader = SwingClipboardReader(),
    ) {
        val connector = MockConnector()
        val entered = CompletableDeferred<Unit>()
        val providerDone = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<TerminalClipboardReadOutcome>()
        val prompt = SwingClipboardReadPrompt(JPanel())
        val session: TerminalSession =
            TerminalSession.create(
                TerminalBuffers.create(10, 3),
                connector,
                hostPolicy =
                    HostPolicy(
                        clipboardPolicy =
                            TerminalClipboardPolicy(
                                writePermission = TerminalClipboardPermission.ALLOW,
                                readPermission = permission,
                            ),
                    ),
                hostEvents =
                    object : HostEventSink by HostEventSink.NONE {
                        override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
                            binding.write(event.text)
                        }

                        override fun terminalClipboardReadCompleted(event: TerminalClipboardReadAuditEvent) {
                            completed.complete(event.outcome)
                        }
                    },
                clipboardReader =
                    TerminalClipboardReader { request ->
                        entered.complete(Unit)
                        try {
                            binding.read(request, "Read this terminal's clipboard?")
                        } finally {
                            providerDone.complete(Unit)
                        }
                    },
            )
        val binding: IntellijClipboardSession = IntellijClipboardSession(client.projectSession, session, reader)

        init {
            Disposer.register(lifetime) {
                Disposer.dispose(binding)
                prompt.close()
            }
            session.start(10, 3)
        }

        fun attach() {
            binding.attach(prompt)
        }

        fun query() {
            connector.feedFromHost("\u001b]52;c;?\u0007".toByteArray())
        }

        fun output(): String = connector.writtenBytes.toString(Charsets.US_ASCII)
    }

    private inner class Client(
        name: String,
        initialText: String,
    ) {
        val id = ClientId("clipboard-test-$name")
        val appDisposed = AtomicBoolean()
        private val projectDisposed = AtomicBoolean()
        val reads = AtomicInteger()
        val written = CompletableDeferred<Unit>()
        var onRead: (() -> String)? = null
        var writeClient: ClientId? = null

        @Volatile var text = initialText

        @Volatile var primary: String? = null
        private val manager =
            proxy(ClientCopyPasteManager::class.java) { method, arguments ->
                when (method) {
                    "setContents" -> {
                        assertTrue(SwingUtilities.isEventDispatchThread())
                        writeClient = ClientId.current
                        text = (arguments!![0] as Transferable).getTransferData(DataFlavor.stringFlavor) as String
                        written.complete(Unit)
                        null
                    }
                    "getContents" -> {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        reads.incrementAndGet()
                        onRead?.invoke() ?: text
                    }
                    "getSystemSelectionContents" -> primary?.let(::StringSelection)
                    else -> error("Unexpected clipboard call: $method")
                }
            }
        val appSession: ClientAppSession =
            proxy(ClientAppSession::class.java) { method, _ ->
                when (method) {
                    "getClientId" -> id
                    "isDisposed" -> appDisposed.get()
                    "dispose" -> {
                        appDisposed.set(true)
                        null
                    }
                    "getService" -> manager
                    else -> error("Unexpected app client call: $method")
                }
            }
        val projectSession: ClientProjectSession =
            proxy(ClientProjectSession::class.java) { method, _ ->
                when (method) {
                    "getClientId" -> id
                    "getAppSession" -> appSession
                    "isDisposed" -> projectDisposed.get()
                    "dispose" -> {
                        projectDisposed.set(true)
                        null
                    }
                    else -> error("Unexpected project client call: $method")
                }
            }

        init {
            Disposer.register(lifetime, projectSession)
        }
    }

    private fun <T> proxy(
        type: Class<T>,
        call: (String, Array<out Any?>?) -> Any?,
    ): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, arguments ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === arguments?.get(0)
                    "toString" -> "Clipboard fixture ${type.simpleName}"
                    else -> call(method.name, arguments)
                }
            },
        )

    private fun await(
        description: String,
        event: CompletableDeferred<*>,
    ) = waitFor(description) { event.isCompleted }

    private fun waitFor(
        description: String,
        condition: () -> Boolean,
    ) {
        PlatformTestUtil.waitWithEventsDispatching(description, condition, 10)
    }

    private fun click(
        prompt: SwingClipboardReadPrompt,
        text: String,
    ) {
        fun find(container: Container): JButton? {
            for (child in container.components) {
                if (child is JButton && child.text == text) return child
                if (child is Container) find(child)?.let { return it }
            }
            return null
        }
        requireNotNull(find(prompt.component)).doClick(0)
    }
}
