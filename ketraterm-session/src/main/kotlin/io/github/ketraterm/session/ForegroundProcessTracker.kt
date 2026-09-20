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
package io.github.ketraterm.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/** One polling job shared by all consumers, bounded by its parent session and subscriptions. */
internal class ForegroundProcessTracker(
    scope: CoroutineScope,
    private val readName: () -> String?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableName = MutableStateFlow<String?>(null)
    val name = mutableName.asStateFlow()

    init {
        scope.launch(ioDispatcher + CoroutineName("terminal-foreground-process")) {
            mutableName.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { observed ->
                if (!observed) return@collectLatest
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val nextName =
                            try {
                                readName()
                            } catch (_: IOException) {
                                null
                            } catch (_: SecurityException) {
                                null
                            }
                        currentCoroutineContext().ensureActive()
                        mutableName.value = nextName
                        delay(POLL_INTERVAL_MILLIS.milliseconds)
                    }
                } finally {
                    mutableName.value = null
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
    }
}
