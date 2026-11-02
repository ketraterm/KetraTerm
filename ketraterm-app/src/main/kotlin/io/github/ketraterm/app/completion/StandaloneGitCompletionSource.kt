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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/** Reads local Git references on demand; process lifetime belongs to the completion request. */
internal class StandaloneGitCompletionSource(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startProcess: (ProcessBuilder) -> Process = ProcessBuilder::start,
) : TerminalCompletionSource {
    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        require(limit > 0)
        if (context.command?.name != "git" || context.expectedValueDomain != TerminalCompletionValueDomain.GIT_BRANCH) return emptyList()
        val directory = TerminalLocalFileUriResolver.resolve(request.workingDirectoryUri) ?: return emptyList()
        val output = readReferences(directory) ?: return emptyList()
        val includeRemote = context.currentCommand?.name in REMOTE_REFERENCE_COMMANDS
        val values = ArrayList<TerminalCompletionDomainValue>()
        for (line in output.lineSequence().take(MAX_REFERENCES)) {
            currentCoroutineContext().ensureActive()
            val fields = line.split('\u0000')
            if (fields.size != 3 || (fields[0] != " " && fields[0] != "*") || fields[2].isNotEmpty()) continue
            val ref = fields[1]
            if (ref.length > 4096 || ref.endsWith('/') || ref.any { it.isISOControl() || it.isWhitespace() }) continue
            val value =
                when {
                    ref.startsWith("refs/heads/") && fields[0] != "*" ->
                        TerminalCompletionDomainValue(ref.removePrefix("refs/heads/"), detail = "local branch", scoreAdjustment = 2)
                    includeRemote && ref.startsWith("refs/remotes/") ->
                        TerminalCompletionDomainValue(ref.removePrefix("refs/remotes/"), detail = "remote branch", scoreAdjustment = 1)
                    includeRemote && ref.startsWith("refs/tags/") ->
                        TerminalCompletionDomainValue(ref.removePrefix("refs/tags/"), detail = "tag")
                    else -> null
                }
            if (value != null) values += value
        }
        return TerminalCompletionSources.valueDomainCandidates(
            request,
            context,
            TerminalCompletionValueDomain.GIT_BRANCH,
            SOURCE_ID,
            values,
            limit,
        )
    }

    private suspend fun readReferences(directory: Path): String? =
        withTimeoutOrNull(QUERY_TIMEOUT_MILLIS.milliseconds) {
            withContext(ioDispatcher) {
                coroutineScope {
                    val builder =
                        ProcessBuilder(
                            "git",
                            "--no-optional-locks",
                            "--no-pager",
                            "for-each-ref",
                            "--count=$MAX_REFERENCES",
                            "--format=%(HEAD)%00%(refname)%00%(symref)",
                            "refs/heads/",
                            "refs/remotes/",
                            "refs/tags/",
                        ).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
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
        private val REMOTE_REFERENCE_COMMANDS = setOf("checkout", "merge", "rebase")
        private val REPOSITORY_ENVIRONMENT_VARIABLES =
            setOf("GIT_DIR", "GIT_COMMON_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_NAMESPACE", "GIT_PREFIX")
    }
}
