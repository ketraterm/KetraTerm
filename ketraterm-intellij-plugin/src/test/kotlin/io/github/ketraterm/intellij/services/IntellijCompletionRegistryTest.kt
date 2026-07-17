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

import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IntellijCompletionRegistryTest {
    @Test
    fun `git branch snapshot refreshes the owning session provider`() {
        val registry = IntellijCompletionRegistry()
        try {
            val changed = CountDownLatch(1)
            val session =
                registry.openSession(
                    context("first").copy(
                        gitBranchLoader = {
                            listOf(
                                TerminalCompletionDomainValue("feature/terminal", detail = "local branch"),
                            )
                        },
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
