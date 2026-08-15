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

/**
 * Visits a lazily supplied tree depth-first without discovering or retaining
 * more than [maxVisited] nodes.
 *
 * @return exact number of nodes delivered to [visitor].
 */
internal fun <T> visitBoundedDepthFirst(
    roots: Iterable<T>,
    maxVisited: Int,
    cancellationCheckpoint: () -> Unit,
    children: (T) -> Iterable<T>,
    visitor: (T) -> Boolean,
): Int {
    require(maxVisited > 0) { "maxVisited must be > 0, was $maxVisited" }
    val pending = ArrayDeque<Iterator<T>>()
    pending.addLast(roots.iterator())
    var visited = 0
    while (pending.isNotEmpty() && visited < maxVisited) {
        cancellationCheckpoint()
        val iterator = pending.last()
        if (!iterator.hasNext()) {
            pending.removeLast()
            continue
        }
        val value = iterator.next()
        visited++
        if (!visitor(value)) break
        if (visited < maxVisited) {
            pending.addLast(children(value).iterator())
        }
    }
    return visited
}
