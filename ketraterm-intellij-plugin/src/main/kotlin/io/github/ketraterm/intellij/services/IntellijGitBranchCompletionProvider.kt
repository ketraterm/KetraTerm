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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads local Git branches from the repository containing a terminal directory.
 *
 * Repository state is accessed under an IntelliJ read action. Invalid,
 * non-local, remote-authority, disposed-project, and non-repository inputs
 * produce an empty snapshot rather than falling back to another repository.
 *
 * @property project IntelliJ project whose Git repository model is queried.
 */
internal class IntellijGitBranchLoader(
    private val project: Project,
) {
    /**
     * Loads a bounded, deterministically ordered local-branch snapshot.
     *
     * The current branch is excluded because inserting it would not change the
     * command. When repositories are nested, the deepest containing repository
     * wins.
     *
     * @param workingDirectoryUri local `file` URI used to select a repository.
     * @return at most 2,048 immutable completion values, or an empty list when
     * the directory cannot be mapped to a usable project repository.
     */
    fun load(workingDirectoryUri: String?): List<TerminalCompletionDomainValue> {
        if (project.isDisposed) return emptyList()
        val workingDirectory = workingDirectoryUri.toLocalPath() ?: return emptyList()
        return ApplicationManager.getApplication().runReadAction<List<TerminalCompletionDomainValue>> {
            if (project.isDisposed) return@runReadAction emptyList()
            val repository = selectRepository(GitRepositoryManager.getInstance(project).repositories, workingDirectory)
                ?: return@runReadAction emptyList()
            val currentBranchName = repository.currentBranch?.name
            repository.branches.localBranches
                .asSequence()
                .filterNot { branch -> branch.name == currentBranchName }
                .map { branch ->
                    TerminalCompletionDomainValue(
                        value = branch.name,
                        detail = "local branch",
                    )
                }
                .sortedWith(BRANCH_ORDER)
                .take(MAX_BRANCHES)
                .toList()
        }
    }

    private companion object {
        private const val MAX_BRANCHES = 2_048
        private val BRANCH_ORDER =
            compareBy<TerminalCompletionDomainValue, String>(String.CASE_INSENSITIVE_ORDER) { it.value }
                .thenBy { it.value }

        private fun selectRepository(
            repositories: List<GitRepository>,
            workingDirectory: Path,
        ): GitRepository? =
            repositories
                .asSequence()
                .filter { repository -> workingDirectory.startsWith(repository.root.toNioPath()) }
                .maxByOrNull { repository -> repository.root.path.length }

        private fun String?.toLocalPath(): Path? {
            if (this == null) return null
            return try {
                val uri = URI(this)
                if (!uri.scheme.equals("file", ignoreCase = true)) return null
                val authority = uri.authority
                if (!authority.isNullOrEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
                Path.of(URI("file", null, uri.path ?: return null, null)).toAbsolutePath().normalize()
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Session-local, generation-safe Git branch snapshot read by the shared engine.
 *
 * [values] never blocks on Git or filesystem work. It returns the latest ready
 * immutable snapshot and schedules refresh work through [scheduler]. Directory
 * changes advance a generation so stale loads cannot publish. Failed loads
 * clear their in-flight marker, retain the last ready snapshot, and may be
 * retried by the next request. Callbacks run on the scheduler's worker thread.
 *
 * @property workingDirectoryUriProvider thread-safe supplier for the current
 * terminal working-directory URI.
 * @property scheduler bounded asynchronous work scheduler.
 * @property loader blocking host loader that returns a bounded branch snapshot.
 * @property onSnapshotChanged callback invoked after publishing the active generation.
 * @property nanoTime monotonic clock used only for snapshot expiry.
 * @property snapshotTtlNanos positive lifetime of a ready snapshot.
 * @throws IllegalArgumentException if [snapshotTtlNanos] is not positive.
 */
internal class IntellijGitBranchCompletionProvider(
    private val workingDirectoryUriProvider: () -> String?,
    private val scheduler: IntellijCompletionLoadScheduler,
    private val loader: (String?) -> List<TerminalCompletionDomainValue>,
    private val onSnapshotChanged: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val snapshotTtlNanos: Long = TimeUnit.SECONDS.toNanos(DEFAULT_SNAPSHOT_TTL_SECONDS),
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private var key: String? = null
    private var snapshot = emptyList<TerminalCompletionDomainValue>()
    private var createdAtNanos = 0L
    private var hasSnapshot = false
    private var generation = 0L
    private var inFlightGeneration = NO_GENERATION

    init {
        require(snapshotTtlNanos > 0L) { "snapshotTtlNanos must be > 0, was $snapshotTtlNanos" }
    }

    /**
     * Returns the latest ready branch snapshot and starts refresh work if needed.
     *
     * @return immutable ready snapshot, or an empty list before the first
     * successful load and after closure.
     */
    fun values(): List<TerminalCompletionDomainValue> {
        if (closed.get()) return emptyList()
        val requestedKey = workingDirectoryUriProvider()
        val now = nanoTime()
        var loadGeneration = NO_GENERATION
        synchronized(lock) {
            if (requestedKey != key) {
                key = requestedKey
                snapshot = emptyList()
                createdAtNanos = 0L
                hasSnapshot = false
                generation++
                inFlightGeneration = NO_GENERATION
            }
            if (hasSnapshot && now - createdAtNanos < snapshotTtlNanos) return snapshot
            if (inFlightGeneration == NO_GENERATION) {
                inFlightGeneration = generation
                loadGeneration = generation
            }
        }
        if (loadGeneration != NO_GENERATION) submitLoad(requestedKey, loadGeneration)
        return synchronized(lock) { snapshot }
    }

    private fun submitLoad(
        requestedKey: String?,
        requestedGeneration: Long,
    ) {
        val accepted =
            scheduler.schedule {
                try {
                    val loaded = loader(requestedKey).toList()
                    var publish = false
                    synchronized(lock) {
                        if (inFlightGeneration == requestedGeneration) inFlightGeneration = NO_GENERATION
                        if (!closed.get() && generation == requestedGeneration && key == requestedKey) {
                            snapshot = loaded
                            createdAtNanos = nanoTime()
                            hasSnapshot = true
                            publish = true
                        }
                    }
                    if (publish) {
                        try {
                            onSnapshotChanged()
                        } catch (_: RuntimeException) {
                            // The owning pane may close while publication is in flight.
                        }
                    }
                } finally {
                    synchronized(lock) {
                        if (inFlightGeneration == requestedGeneration) inFlightGeneration = NO_GENERATION
                    }
                }
            }
        if (!accepted) {
            synchronized(lock) {
                if (inFlightGeneration == requestedGeneration) inFlightGeneration = NO_GENERATION
            }
        }
    }

    /**
     * Invalidates pending generations and releases the retained snapshot.
     *
     * Closing is idempotent. Already-running loader work is not interrupted,
     * but its result is prevented from publishing.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            generation++
            inFlightGeneration = NO_GENERATION
            snapshot = emptyList()
            hasSnapshot = false
        }
    }

    private companion object {
        private const val DEFAULT_SNAPSHOT_TTL_SECONDS = 2L
        private const val NO_GENERATION = -1L
    }
}
