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
package io.github.ketraterm.completion.host

import io.github.ketraterm.completion.api.TerminalDirectoryListingRequest
import io.github.ketraterm.completion.api.TerminalFileEntry
import io.github.ketraterm.completion.api.TerminalFileSystemProvider

/** Direct suspending local-filesystem provider used by path completion. */
class TerminalLocalFileSystemProvider
    @JvmOverloads
    constructor(
        private val resolver: TerminalCompletionPathResolver = TerminalCompletionPathResolver(),
        private val scanner: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(),
    ) : TerminalFileSystemProvider {
        /** Resolves and scans the requested local directory in the caller's coroutine. */
        override suspend fun listDirectory(request: TerminalDirectoryListingRequest): List<TerminalFileEntry> {
            val directory = resolver.resolve(request) ?: return emptyList()
            return scanner.scan(directory, request.entryNamePrefix)
        }
    }
