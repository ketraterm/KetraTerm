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
package io.github.ketraterm.core.util

import io.github.ketraterm.core.state.TerminalState

/** Resolves DEC coordinates once for both rectangle mutation and checksum queries. */
internal inline fun TerminalState.withResolvedRectangle(
    top: Int,
    left: Int,
    bottom: Int,
    right: Int,
    block: (Int, Int, Int, Int) -> Unit,
) {
    val originRows = modes.isOriginMode
    val originColumns = originRows && modes.isLeftRightMarginMode
    val rowBase = if (originRows) scrollTop else 0
    val rowLimit = if (originRows) scrollBottom else dimensions.height - 1
    val colBase = if (originColumns) effectiveLeftMargin else 0
    val colLimit = if (originColumns) effectiveRightMargin else dimensions.width - 1
    val requestedTop = rowBase.toLong() + if (top <= 0) 0 else top - 1
    val requestedLeft = colBase.toLong() + if (left <= 0) 0 else left - 1
    val requestedBottom = rowBase.toLong() + if (bottom <= 0) rowLimit - rowBase else bottom - 1
    val requestedRight = colBase.toLong() + if (right <= 0) colLimit - colBase else right - 1
    if (requestedTop > requestedBottom || requestedLeft > requestedRight) return
    block(
        requestedTop.coerceIn(rowBase.toLong(), rowLimit.toLong()).toInt(),
        requestedLeft.coerceIn(colBase.toLong(), colLimit.toLong()).toInt(),
        requestedBottom.coerceIn(rowBase.toLong(), rowLimit.toLong()).toInt(),
        requestedRight.coerceIn(colBase.toLong(), colLimit.toLong()).toInt(),
    )
}
