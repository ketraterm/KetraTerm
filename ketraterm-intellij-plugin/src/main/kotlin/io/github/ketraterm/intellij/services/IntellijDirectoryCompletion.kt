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

import io.github.ketraterm.completion.host.*

/** IntelliJ-local name for the shared bounded completion scheduler contract. */
internal typealias IntellijCompletionLoadScheduler = TerminalCompletionLoadScheduler

/** IntelliJ-local name for the shared generation-safe filesystem provider. */
internal typealias IntellijAsyncFileSystemProvider = TerminalAsyncFileSystemProvider

/** IntelliJ-local name for the shared authority-preserving path resolver. */
internal typealias IntellijCompletionPathResolver = TerminalCompletionPathResolver

/** IntelliJ-local name for the shared blocking directory-scan contract. */
internal typealias IntellijDirectoryScanner = TerminalDirectoryScanner

/** IntelliJ-local name for the shared bounded local-filesystem fallback scanner. */
internal typealias BoundedIntellijDirectoryScanner = TerminalBoundedDirectoryScanner
