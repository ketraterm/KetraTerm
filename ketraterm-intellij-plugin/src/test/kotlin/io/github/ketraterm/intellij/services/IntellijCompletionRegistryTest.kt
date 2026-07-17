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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Integration tests for IntelliJ completion source composition and lifecycle. */
class IntellijCompletionRegistryTest {
    @Test
    fun `additional provider factory composes source and closes its resources`() {
        var resourceClosed = false
        val factory =
            IntellijCompletionProviderFactory {
                IntellijCompletionProviderRegistration(
                    sourceEntry =
                        TerminalCompletionSourceEntry(
                            source = { request ->
                                listOf(
                                    TerminalCompletionCandidate(
                                        replacementText = "custom",
                                        replacementStartOffset = 0,
                                        replacementEndOffset = request.cursorOffset,
                                        source = "custom-provider",
                                        kind = TerminalCompletionCandidateKind.COMMAND,
                                    ),
                                )
                            },
                            priority = 500,
                        ),
                    resources = listOf(AutoCloseable { resourceClosed = true }),
                )
            }
        val registry = IntellijCompletionRegistry(specs = emptyList())
        val session = registry.openSession(context("custom").copy(providerFactories = listOf(factory)))

        assertEquals("custom", session.provider.suggestions(request("cu")).single().replacementText)

        session.close()
        assertTrue(resourceClosed)
        registry.close()
    }

    @Test
    fun `git branch snapshot refreshes the owning session provider`() {
        val registry = IntellijCompletionRegistry()
        try {
            val changed = CountDownLatch(1)
            val session =
                registry.openSession(
                    context("first").copy(
                        providerFactories =
                            listOf(
                                IntellijGitBranchProviderFactory {
                                    listOf(
                                        TerminalCompletionDomainValue("feature/terminal", detail = "local branch"),
                                    )
                                },
                            ),
                    ),
                )
            session.onSourceChanged(changed::countDown)

            session.provider.suggestions(request("git switch fe"))
            assertTrue("completion source refresh timed out", changed.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var suggestions = session.provider.suggestions(request("git switch fe"))
            while (suggestions.none { it.source == "intellij-git-branch" } && System.nanoTime() < deadline) {
                Thread.sleep(5)
                suggestions = session.provider.suggestions(request("git switch fe"))
            }

            val suggestion = suggestions.single { it.source == "intellij-git-branch" }
            assertEquals("feature/terminal", suggestion.replacementText)
            assertEquals("local branch", suggestion.detail)
            assertEquals("intellij-git-branch", suggestion.source)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `remote git branch snapshot is available for checkout but not switch`() {
        val registry = IntellijCompletionRegistry()
        try {
            val changed = CountDownLatch(1)
            val session =
                registry.openSession(
                    context("remote-branch").copy(
                        providerFactories =
                            listOf(
                                IntellijGitRemoteBranchProviderFactory {
                                    listOf(
                                        TerminalCompletionDomainValue(
                                            "origin/feature/terminal",
                                            detail = "remote branch"
                                        )
                                    )
                                },
                            ),
                    ),
                )
            session.onSourceChanged(changed::countDown)

            session.provider.suggestions(request("git checkout or"))
            assertTrue("completion source refresh timed out", changed.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var suggestions = session.provider.suggestions(request("git checkout or"))
            while (suggestions.none { it.source == "intellij-git-remote-branch" } && System.nanoTime() < deadline) {
                Thread.sleep(5)
                suggestions = session.provider.suggestions(request("git checkout or"))
            }

            val suggestion = suggestions.single { it.source == "intellij-git-remote-branch" }
            assertEquals("origin/feature/terminal", suggestion.replacementText)
            assertEquals("remote branch", suggestion.detail)
            assertTrue(
                session.provider.suggestions(request("git switch or"))
                    .none { it.source == "intellij-git-remote-branch" })
        } finally {
            registry.close()
        }
    }

    @Test
    fun `git tag snapshot is available for checkout but not switch`() {
        val registry = IntellijCompletionRegistry()
        try {
            val changed = CountDownLatch(1)
            val session =
                registry.openSession(
                    context("git-tag").copy(
                        providerFactories =
                            listOf(
                                IntellijGitTagProviderFactory {
                                    listOf(TerminalCompletionDomainValue("v2.0.0", detail = "tag"))
                                },
                            ),
                    ),
                )
            session.onSourceChanged(changed::countDown)

            session.provider.suggestions(request("git checkout v"))
            assertTrue("completion source refresh timed out", changed.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var suggestions = session.provider.suggestions(request("git checkout v"))
            while (suggestions.none { it.source == "intellij-git-tag" } && System.nanoTime() < deadline) {
                Thread.sleep(5)
                suggestions = session.provider.suggestions(request("git checkout v"))
            }

            val suggestion = suggestions.single { it.source == "intellij-git-tag" }
            assertEquals("v2.0.0", suggestion.replacementText)
            assertEquals("tag", suggestion.detail)
            assertTrue(session.provider.suggestions(request("git switch v")).none { it.source == "intellij-git-tag" })
        } finally {
            registry.close()
        }
    }

    @Test
    fun `Gradle task snapshot supports qualified tasks and project directory task names`() {
        val registry = IntellijCompletionRegistry()
        try {
            val changed = CountDownLatch(1)
            val session =
                registry.openSession(
                    context("gradle-task").copy(
                        workingDirectoryUriProvider = { "file:///project" },
                        providerFactories =
                            listOf(
                                IntellijGradleTaskProviderFactory {
                                    listOf(
                                        TerminalGradleTask(":test", "run root tests", projectDirectory = "."),
                                        TerminalGradleTask(
                                            ":app:runIde",
                                            "launch IDE sandbox",
                                            projectDirectory = "app"
                                        ),
                                    )
                                },
                            ),
                    ),
                )
            session.onSourceChanged(changed::countDown)

            session.provider.suggestions(request("./gradlew :app:runI"))
            assertTrue("completion source refresh timed out", changed.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var suggestions = session.provider.suggestions(request("./gradlew :app:runI"))
            while (suggestions.none { it.source == "intellij-gradle-task" } && System.nanoTime() < deadline) {
                Thread.sleep(5)
                suggestions = session.provider.suggestions(request("./gradlew :app:runI"))
            }

            val qualified = suggestions.single { it.source == "intellij-gradle-task" }
            assertEquals(":app:runIde", qualified.replacementText)
            assertEquals("launch IDE sandbox", qualified.detail)
            assertEquals(
                listOf("runIde"),
                session.provider.suggestions(request("./gradlew -p app runI"))
                    .filter { it.source == "intellij-gradle-task" }
                    .map { it.replacementText },
            )
        } finally {
            registry.close()
        }
    }

    @Test
    fun `project fuzzy path snapshot refreshes the owning session provider`() {
        val registry = IntellijCompletionRegistry()
        try {
            val changed = CountDownLatch(1)
            val session =
                registry.openSession(
                    context("project-files").copy(
                        workingDirectoryUriProvider = { "file:///project" },
                        providerFactories =
                            listOf(
                                IntellijProjectFileProviderFactory {
                                    listOf(TerminalFuzzyPathEntry("src/main/FuzzyTarget.kt", isDirectory = false))
                                },
                            ),
                    ),
                )
            session.onSourceChanged(changed::countDown)

            session.provider.suggestions(request("cat FzT"))
            assertTrue("completion source refresh timed out", changed.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var suggestions = session.provider.suggestions(request("cat FzT"))
            while (suggestions.none { it.source == "intellij-project-file" } && System.nanoTime() < deadline) {
                Thread.sleep(5)
                suggestions = session.provider.suggestions(request("cat FzT"))
            }

            val suggestion = suggestions.single { it.source == "intellij-project-file" }
            assertEquals("src/main/FuzzyTarget.kt", suggestion.replacementText)
            assertEquals("project file", suggestion.detail)
            assertEquals("PATH", suggestion.kind)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `git status paths refresh the owning session provider for an empty add argument`() {
        val registry = IntellijCompletionRegistry()
        try {
            val changed = CountDownLatch(1)
            val session =
                registry.openSession(
                    context("git-status-path").copy(
                        workingDirectoryUriProvider = { "file:///project" },
                        providerFactories =
                            listOf(
                                IntellijGitStatusPathProviderFactory {
                                    listOf(
                                        TerminalFuzzyPathEntry(
                                            "src/Changed.kt",
                                            isDirectory = false,
                                            detail = "changed file",
                                        ),
                                    )
                                },
                            ),
                    ),
                )
            session.onSourceChanged(changed::countDown)

            session.provider.suggestions(request("git add "))
            assertTrue("completion source refresh timed out", changed.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var suggestions = session.provider.suggestions(request("git add "))
            while (suggestions.none { it.source == "intellij-git-status-path" } && System.nanoTime() < deadline) {
                Thread.sleep(5)
                suggestions = session.provider.suggestions(request("git add "))
            }

            val suggestion = suggestions.single { it.source == "intellij-git-status-path" }
            assertEquals("src/Changed.kt", suggestion.replacementText)
            assertEquals("changed file", suggestion.detail)
            assertEquals("PATH", suggestion.kind)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `successful commands feed only their owning session MRU`() {
        val registry = IntellijCompletionRegistry(specs = emptyList())
        try {
            val first = registry.openSession(context("first"))
            val second = registry.openSession(context("second"))

            registry.recordFinishedCommand(
                sessionId = "first",
                profileId = "bash",
                metadata = successfulCommand("git --password secret"),
            )

            assertEquals(
                listOf("git --password secret"),
                first.provider.suggestions(request("git")).map { it.replacementText })
            assertTrue(second.provider.suggestions(request("git")).isEmpty())
        } finally {
            registry.close()
        }
    }

    @Test
    fun `closing session clears its MRU source`() {
        val registry = IntellijCompletionRegistry(specs = emptyList())
        try {
            val session = registry.openSession(context("first"))
            registry.recordFinishedCommand("first", "bash", successfulCommand("git --password secret"))
            val provider = session.provider

            session.close()

            assertTrue(provider.suggestions(request("git")).isEmpty())
        } finally {
            registry.close()
        }
    }

    @Test
    fun `accepted suggestion updates indexed feedback and persists compact snapshot`() {
        val persisted = CountDownLatch(1)
        val statsSource = TerminalCompletionSources.commandStats(commandSpecs = emptyList())
        val registry =
            IntellijCompletionRegistry(
                specs = emptyList(),
                statsSource = statsSource,
                persistStats = { persisted.countDown() },
            )
        try {
            val session = registry.openSession(context("first"))
            val request = request("git")
            session.feedbackHandler.onSuggestionFeedback(
                SwingShellSuggestionFeedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    suggestion =
                        SwingShellSuggestion(
                            replacementText = "git status",
                            replacementStartOffset = 0,
                            replacementEndOffset = 3,
                            source = "session-mru",
                            kind = "COMMAND",
                        ),
                    index = 0,
                    request = request,
                ),
            )

            assertTrue("feedback persistence timed out", persisted.await(5, TimeUnit.SECONDS))
            assertEquals(1, statsSource.feedbackSnapshot().single().acceptedCount)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `failed provider composition closes resources created by earlier factories`() {
        var resourceClosed = false
        val successfulFactory =
            IntellijCompletionProviderFactory {
                IntellijCompletionProviderRegistration(
                    sourceEntry = TerminalCompletionSourceEntry(source = { emptyList() }, priority = 1),
                    resources = listOf(AutoCloseable { resourceClosed = true }),
                )
            }
        val failingFactory = IntellijCompletionProviderFactory { error("factory failed") }
        val registry = IntellijCompletionRegistry(specs = emptyList())
        try {
            assertThrows(IllegalStateException::class.java) {
                registry.openSession(
                    context("failed").copy(providerFactories = listOf(successfulFactory, failingFactory)),
                )
            }
            assertTrue(resourceClosed)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `one provider close failure does not skip later resources`() {
        val closed = ArrayList<String>()
        val factory =
            IntellijCompletionProviderFactory {
                IntellijCompletionProviderRegistration(
                    sourceEntry = TerminalCompletionSourceEntry(source = { emptyList() }, priority = 1),
                    resources =
                        listOf(
                            AutoCloseable {
                                closed += "throwing"
                                error("close failed")
                            },
                            AutoCloseable { closed += "following" },
                        ),
                )
            }
        val registry = IntellijCompletionRegistry(specs = emptyList())
        val session = registry.openSession(context("close-failure").copy(providerFactories = listOf(factory)))

        assertThrows(IllegalStateException::class.java, session::close)
        assertEquals(listOf("throwing", "following"), closed)
        registry.close()
    }

    @Test
    fun `invalid session capacity is rejected before registry resources are started`() {
        assertThrows(IllegalArgumentException::class.java) {
            IntellijCompletionRegistry(sessionMruCapacity = 0)
        }
    }

    private fun context(sessionId: String): IntellijCompletionSessionContext =
        IntellijCompletionSessionContext(
            sessionId = sessionId,
            profileId = "bash",
            workingDirectoryUriProvider = { null },
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )

    private fun request(commandText: String): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(commandText, commandText.length, anchorColumn = 0, anchorRow = 0)

    private fun successfulCommand(commandText: String): TerminalShellIntegrationCommandMetadata =
        TerminalShellIntegrationCommandMetadata(
            recordId = 1,
            lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
            commandText = commandText,
            workingDirectoryUri = null,
            exitCode = 0,
            startedAtEpochMillis = 1,
            finishedAtEpochMillis = 2,
        )
}
