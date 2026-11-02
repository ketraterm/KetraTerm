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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalGitCompletionSnapshot
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/** Reads local Git references on demand; process lifetime belongs to the completion request. */
internal class StandaloneGitCompletionLoader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startProcess: (ProcessBuilder) -> Process = ProcessBuilder::start,
) {
    suspend fun load(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
    ): TerminalGitCompletionSnapshot {
        val directory =
            TerminalLocalFileUriResolver.resolve(request.workingDirectoryUri)
                ?: return TerminalGitCompletionSnapshot()
        val remotes = context.expectedValueDomain == TerminalCompletionValueDomain.GIT_REMOTE
        val output =
            readGit(directory, if (remotes) listOf("remote") else REFERENCE_ARGUMENTS)
                ?: return TerminalGitCompletionSnapshot()
        val localBranches = ArrayList<TerminalCompletionDomainValue>()
        val remoteBranches = ArrayList<TerminalCompletionDomainValue>()
        val tags = ArrayList<TerminalCompletionDomainValue>()
        val remoteNames = ArrayList<TerminalCompletionDomainValue>()
        var currentBranch: String? = null
        for (line in output.lineSequence().take(MAX_REFERENCES)) {
            currentCoroutineContext().ensureActive()
            if (remotes) {
                if (line.isNotEmpty() && line.length <= 4096 && line.none { it.isISOControl() || it.isWhitespace() }) {
                    remoteNames += TerminalCompletionDomainValue(line, detail = "remote")
                }
                continue
            }
            val fields = line.split('\u0000')
            if (fields.size != 3 || (fields[0] != " " && fields[0] != "*") || fields[2].isNotEmpty()) continue
            val ref = fields[1]
            if (ref.length > 4096 || ref.endsWith('/') || ref.any { it.isISOControl() || it.isWhitespace() }) continue
            when {
                ref.startsWith("refs/heads/") -> {
                    val name = ref.removePrefix("refs/heads/")
                    if (fields[0] == "*") currentBranch = name
                    localBranches += TerminalCompletionDomainValue(name, detail = "local branch", scoreAdjustment = 2)
                }
                ref.startsWith("refs/remotes/") ->
                    remoteBranches +=
                        TerminalCompletionDomainValue(ref.removePrefix("refs/remotes/"), detail = "remote branch", scoreAdjustment = 1)
                ref.startsWith("refs/tags/") ->
                    tags += TerminalCompletionDomainValue(ref.removePrefix("refs/tags/"), detail = "tag")
            }
        }
        return TerminalGitCompletionSnapshot(localBranches, remoteBranches, tags, remoteNames, currentBranch)
    }

    private suspend fun readGit(
        directory: Path,
        arguments: List<String>,
    ): String? =
        withTimeoutOrNull(QUERY_TIMEOUT_MILLIS.milliseconds) {
            withContext(ioDispatcher) {
                coroutineScope {
                    val builder =
                        ProcessBuilder(listOf("git", "--no-optional-locks", "--no-pager") + arguments)
                            .directory(directory.toFile())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                    // Repository selection comes from the request, not the app's launch repository.
                    builder.environment().keys.removeAll { it.uppercase() in REPOSITORY_ENVIRONMENT_VARIABLES }
                    builder.environment()["GIT_NO_LAZY_FETCH"] = "1"
                    val process = startProcess(builder)
                    val cancellation =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                // Pipe reads are not reliably interruptible on every host; close their writer.
                                process.destroyForcibly()
                            }
                        }
                    try {
                        runInterruptible {
                            process.outputStream.close()
                            val bytes = process.inputStream.use { it.readNBytes(MAX_OUTPUT_BYTES + 1) }
                            if (bytes.size > MAX_OUTPUT_BYTES || process.waitFor() != 0) return@runInterruptible null
                            Charsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(bytes))
                                .toString()
                        }
                    } finally {
                        process.destroyForcibly()
                        cancellation.cancel()
                    }
                }
            }
        }

    internal companion object {
        const val SOURCE_ID = "local-git-reference"
        const val MAX_REFERENCES = 8192
        const val MAX_OUTPUT_BYTES = 1_048_576
        const val QUERY_TIMEOUT_MILLIS = 1500L
        private val REFERENCE_ARGUMENTS =
            listOf(
                "for-each-ref",
                "--count=$MAX_REFERENCES",
                "--format=%(HEAD)%00%(refname)%00%(symref)",
                "refs/heads/",
                "refs/remotes/",
                "refs/tags/",
            )
        private val REPOSITORY_ENVIRONMENT_VARIABLES =
            setOf("GIT_DIR", "GIT_COMMON_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_NAMESPACE", "GIT_PREFIX")
    }
}
