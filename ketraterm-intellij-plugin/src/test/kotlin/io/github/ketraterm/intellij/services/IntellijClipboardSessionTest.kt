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
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.UiInterceptors
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.*
import io.github.ketraterm.intellij.ui.IntellijMessageDialogs
import io.github.ketraterm.intellij.ui.IntellijTerminalClipboardHandler
import io.github.ketraterm.session.TerminalClipboardReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.host.SwingClipboardPrompts
import io.github.ketraterm.ui.swing.host.SwingClipboardReadPrompt
import io.github.ketraterm.ui.swing.host.SwingClipboardReader
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/** Real IDE dispatch and session byte streams, with a fake native clipboard boundary. */
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

    fun testProductReadDialogUsesLockCompatibleContextAndClosesOnSessionCancellation() {
        val shown = CompletableDeferred<MessageDialog>()
        UiInterceptors.registerPossible(
            testRootDisposable,
            object : UiInterceptors.UiInterceptor<MessageDialog>(MessageDialog::class.java) {
                override fun doIntercept(component: MessageDialog) {
                    // Interception skips DialogWrapper's show path. The platform's
                    // dispatch-thread check includes its required write-intent access.
                    assertTrue(ApplicationManager.getApplication().isDispatchThread)
                    shown.complete(component)
                }
            },
        )
        val prompt = SwingClipboardReadPrompt { request, decide -> IntellijMessageDialogs.showModeless(project, request, decide) }
        val f = Fixture(Client("product-read", "secret"), TerminalClipboardPermission.PROMPT, consent = prompt)
        f.attach()
        f.query()
        waitFor("product consent or provider failure") { shown.isCompleted || f.completed.isCompleted }
        assertTrue("The clipboard dispatcher must permit the platform dialog's lock", shown.isCompleted)
        val dialog = runBlocking { shown.await() }
        assertFalse(f.completed.isCompleted)
        f.session.close()
        await("product consent disposal", f.providerDone)
        assertTrue(dialog.isDisposed)
        assertEquals(0, f.client.reads.get())
        assertEquals("", f.output())
    }

    fun testProductWriteDialogUsesOwningClientAndExplicitConsent() {
        for (allow in listOf(false, true)) {
            val f = Fixture(Client("product-write-$allow", "old"))
            UiInterceptors.registerPossible(
                testRootDisposable,
                object : UiInterceptors.UiInterceptor<MessageDialog>(MessageDialog::class.java) {
                    override fun doIntercept(component: MessageDialog) {
                        assertEquals(f.client.id, ClientId.current)
                        component.close(if (allow) 0 else 1)
                    }
                },
            )
            val finished = CompletableDeferred<Unit>()
            f.binding.postIfAlive {
                try {
                    if (IntellijMessageDialogs.show(project, SwingClipboardPrompts.writeConfirmation("Terminal", "new")) == 0) {
                        f.binding.clipboard.copyText("new")
                    }
                    finished.complete(Unit)
                } catch (failure: Throwable) {
                    finished.completeExceptionally(failure)
                }
            }
            await("product write consent", finished)
            runBlocking { finished.await() }
            assertEquals(if (allow) "new" else "old", f.client.text)
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
        assertFalse(f.promptVisible)
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
        waitFor("first consent") { first.promptVisible }
        assertEquals(0, first.client.reads.get())
        second.query()
        await("second query denial", second.completed)
        assertEquals("\u001b]52;c;\u001b\\", second.output())
        assertFalse(second.promptVisible)
        assertTrue(first.promptVisible)
        first.decide(SwingClipboardReadPrompt.Decision.ALLOW_ONCE.ordinal)
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
        waitFor("consent") { f.promptVisible }
        Disposer.dispose(client.owner)
        waitFor("consent cancellation") { !f.promptVisible }
        await("request retirement", f.completed)
        f.decide(SwingClipboardReadPrompt.Decision.ALLOW_ONCE.ordinal)
        assertTrue(f.session.state.value is TerminalSessionState.Closed)
        assertFalse(f.binding.isAlive)
        assertEquals(0, client.reads.get())
        assertEquals("", f.output())
    }

    fun testQueuedWriteIsDiscardedWhenClientIsDisposedBeforeEdtDelivery() {
        val client = Client("closed-write", "old")
        val f = Fixture(client)
        f.binding.write("must-not-be-written")
        Disposer.dispose(client.owner)
        val barrier = CompletableDeferred<Unit>()
        SwingUtilities.invokeLater { barrier.complete(Unit) }
        await("EDT write barrier", barrier)
        assertEquals("old", client.text)
        assertFalse(client.written.isCompleted)
    }

    fun testApplicationClientDisposalCancelsConsentWhileProjectClientIsStillAlive() {
        val projectClient = Client("project", "secret")
        val applicationClient = Client("application", "secret", projectClient.id)
        val f = Fixture(projectClient, TerminalClipboardPermission.PROMPT, applicationClient = applicationClient)
        f.attach()
        f.query()
        waitFor("consent") { f.promptVisible }
        Disposer.dispose(applicationClient.owner)
        await("application client retirement", f.completed)
        waitFor("consent cancellation") { !f.promptVisible }
        assertTrue(projectClient.owner.isAlive)
        assertFalse(f.binding.isAlive)
        assertTrue(f.session.state.value is TerminalSessionState.Closed)
        assertEquals(0, applicationClient.reads.get())
        assertEquals("", f.output())
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
            Disposer.dispose(client.owner)
            assertTrue(f.session.state.value is TerminalSessionState.Closed)
        } finally {
            release.countDown()
        }
        await("native provider returned after disposal", f.providerDone)
        assertEquals("", f.output())
    }

    fun testRegisteredApplicationAndProjectOwnersHaveDistinctLifetimes() {
        val applicationClient = service<IntellijClipboardClient>()
        val projectClient = project.service<IntellijClipboardClient>()
        assertNotSame(applicationClient, projectClient)
        assertEquals(ClientId.current, applicationClient.clientId)
        assertEquals(ClientId.current, projectClient.clientId)
        applicationClient.checkCurrent()
        projectClient.checkCurrent()
    }

    fun testSameClientIdCannotImpersonateRegisteredService() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val applicationClient = IntellijClipboardClient(scope)
            val projectClient = IntellijClipboardClient(project, scope)
            assertEquals(ClientId.current, applicationClient.clientId)
            assertEquals(ClientId.current, projectClient.clientId)
            assertCancelled { applicationClient.checkCurrent() }
            assertCancelled { projectClient.checkCurrent() }
        } finally {
            scope.cancel()
        }
    }

    fun testPublicClipboardAdapterRejectsForeignContextBeforeReadingOrWriting() {
        val owner = Client("foreign-owner", "")
        val clipboard = IntellijTerminalClipboardHandler(owner.owner)
        val manager = CopyPasteManager.getInstance()
        @Suppress("UsePropertyAccessSyntax")
        manager.setContents(StringSelection("local-clipboard"))
        assertCancelled { clipboard.copyText("must-not-replace-local") }
        assertCancelled { clipboard.readText() }
        assertCancelled { clipboard.readPrimarySelectionText() }
        assertEquals("local-clipboard", manager.getContents(DataFlavor.stringFlavor))
    }

    fun testPublicClipboardAdapterUsesTheIdeFacadeAndRejectsDisposedOwner() {
        val owner = Client("local", "", ClientId.current)
        val clipboard = IntellijTerminalClipboardHandler(owner.owner)
        clipboard.copyText("public-api-text")
        assertEquals("public-api-text", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        runBlocking(Dispatchers.IO + owner.id.asContextElement()) {
            assertEquals("public-api-text", clipboard.readText())
            assertNull(clipboard.readPrimarySelectionText())
        }
        Disposer.dispose(owner.owner)
        assertCancelled { clipboard.readText() }
        assertCancelled { clipboard.copyText("must-not-write") }
        assertCancelled { clipboard.readPrimarySelectionText() }
    }

    fun testReplacementProjectClientDiscardsANativeResultBeforeOldClientDisposal() {
        val client = Client("reconnecting", "old-clipboard")
        val replacement = Client("replacement", "new-clipboard", client.id)
        val f = Fixture(client)
        client.onRead = {
            client.current = replacement.owner
            "must-not-be-delivered"
        }
        f.attach()
        f.query()
        await("replacement rejection", f.completed)
        assertTrue(client.owner.isAlive)
        assertEquals(TerminalClipboardReadOutcome.CANCELLED, runBlocking { f.completed.await() })
        assertEquals("", f.output())
    }

    fun testReplacementApplicationClientDiscardsDataReadThroughPublicFacade() {
        val projectClient = Client("project", "", ClientId.current)
        val applicationClient = Client("application", "", projectClient.id)
        val replacement = Client("replacement", "", projectClient.id)
        val armed = AtomicBoolean()
        val accessed = CompletableDeferred<Unit>()
        @Suppress("UsePropertyAccessSyntax")
        CopyPasteManager.getInstance().setContents(
            object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor

                override fun getTransferData(flavor: DataFlavor): String {
                    if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                    if (armed.get()) {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        applicationClient.current = replacement.owner
                        accessed.complete(Unit)
                    }
                    return "replacement-client-secret"
                }
            },
        )
        val f =
            Fixture(
                projectClient,
                applicationClient = applicationClient,
                clipboard = IntellijTerminalClipboardHandler(applicationClient.owner),
            )
        f.attach()
        armed.set(true)
        try {
            f.query()
            await("replacement rejection", f.completed)
            assertTrue(accessed.isCompleted)
            assertTrue(applicationClient.owner.isAlive)
            assertEquals(TerminalClipboardReadOutcome.CANCELLED, runBlocking { f.completed.await() })
            assertEquals("", f.output())
        } finally {
            armed.set(false)
            @Suppress("UsePropertyAccessSyntax")
            CopyPasteManager.getInstance().setContents(StringSelection(""))
        }
    }

    fun testMissingClientRejectsQueuedWritesInsteadOfUsingLocalClipboard() {
        val client = Client("missing", "old")
        val f = Fixture(client)
        f.binding.write("must-not-be-written")
        client.current = null
        val barrier = CompletableDeferred<Unit>()
        SwingUtilities.invokeLater { barrier.complete(Unit) }
        await("EDT write barrier", barrier)
        assertEquals("old", client.text)
        assertFalse(client.written.isCompleted)
    }

    private inner class Fixture(
        val client: Client,
        permission: TerminalClipboardPermission = TerminalClipboardPermission.ALLOW,
        reader: SwingClipboardReader = SwingClipboardReader(),
        applicationClient: Client = client,
        clipboard: TerminalClipboardHandler = applicationClient.clipboard,
        consent: SwingClipboardReadPrompt? = null,
    ) {
        val connector = MockConnector()
        val entered = CompletableDeferred<Unit>()
        val providerDone = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<TerminalClipboardReadOutcome>()
        var promptVisible = false
        var decide: (Int?) -> Unit = {}
        val prompt =
            consent ?: SwingClipboardReadPrompt { _, decision ->
                promptVisible = true
                decide = decision
                AutoCloseable { promptVisible = false }
            }
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
        val binding: IntellijClipboardSession = IntellijClipboardSession(client.owner, applicationClient.owner, session, reader, clipboard)

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
        val id: ClientId = ClientId("clipboard-test-$name"),
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile var current: IntellijClipboardClient? = null
        val owner = IntellijClipboardClient(scope, id) { current }.also { current = it }
        val reads = AtomicInteger()
        val written = CompletableDeferred<Unit>()
        var onRead: (() -> String)? = null
        var writeClient: ClientId? = null

        @Volatile var text = initialText

        val clipboard =
            object : TerminalClipboardHandler {
                override fun copyText(text: String) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    assertEquals(id, ClientId.current)
                    writeClient = ClientId.current
                    this@Client.text = text
                    written.complete(Unit)
                }

                override fun readText(): String {
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    assertEquals(id, ClientId.current)
                    reads.incrementAndGet()
                    return onRead?.invoke() ?: text
                }
            }

        init {
            Disposer.register(lifetime, owner)
            Disposer.register(owner) { scope.cancel() }
        }
    }

    private fun assertCancelled(action: () -> Unit) {
        try {
            action()
            fail("Expected client cancellation")
        } catch (_: CancellationException) {
            // Client identity/lifetime rejection must propagate as cancellation.
        }
    }

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
}
