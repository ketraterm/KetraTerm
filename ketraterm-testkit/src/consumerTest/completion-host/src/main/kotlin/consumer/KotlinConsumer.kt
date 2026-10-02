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
package consumer

import io.github.ketraterm.completion.host.TerminalBoundedDirectoryScanner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

fun main() =
    runBlocking {
        JavaConsumer.verify()
        val directory = Files.createTempDirectory("completion-host-consumer")
        val first = directory.resolve("alpha")
        val second = directory.resolve("beta")
        try {
            Files.writeString(first, "")
            Files.writeString(second, "")
            val scanner = TerminalBoundedDirectoryScanner(maxVisitedEntries = 4, scanBudgetNanos = 10_000_000_000L)
            check(scanner.scan(directory, "a").map { it.name } == listOf("alpha"))
            Files.delete(first)
            check(scanner.scan(directory, "a").isEmpty())
            check(scanner.scan(second, "").isEmpty())
        } finally {
            Files.deleteIfExists(first)
            Files.deleteIfExists(second)
            Files.delete(directory)
        }
    }
