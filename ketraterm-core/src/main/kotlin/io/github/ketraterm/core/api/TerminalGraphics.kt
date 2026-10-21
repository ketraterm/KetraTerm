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
package io.github.ketraterm.core.api

import io.github.ketraterm.protocol.TerminalGraphicsCommand
import io.github.ketraterm.protocol.TerminalGraphicsResult
import io.github.ketraterm.render.api.TerminalRenderImage

/**
 * Caller-serialized image admission and placement on the active terminal screen.
 * Images live outside text cells and appear through the render-frame image API.
 * Mutation, response draining and frame reads require the buffer's usual external
 * serialization; session hosts use their session boundary.
 */
public interface TerminalGraphics {
    /**
     * Invalidation token for incomplete transfers, changed by reset, screen switch
     * and destructive clearing. Compare for inequality, not ordering. This is
     * separate from the render frame's graphics generation.
     */
    public val graphicsTransferGeneration: Long

    /**
     * Applies a complete command using the Base64-decoded prefix `data[0 until length]`.
     * [command] and [data] are borrowed for this call. Raw RGB/RGBA pixels are copied
     * into immutable storage before returning; upload-only and query operations do
     * not place an image. Placement uses the current cursor and known cell-pixel geometry.
     *
     * [result] carries upstream transfer validation failure. An unsuccessful operation
     * does not replace retained images or move the cursor. [allowReplies] controls
     * response permission, not mutation; false suppresses every success/failure reply.
     * Eligible replies are queued for [TerminalResponseChannel.readResponseBytes].
     *
     * @param command borrowed, complete Kitty controls with their original units and omissions.
     * @param data borrowed Base64-decoded transfer storage; bytes after [length] are ignored.
     * @param length valid byte count in `0..data.size`; placement/deletion commands require zero.
     * @param result [TerminalGraphicsResult.OK] for an assembled transfer to validate,
     * or its upstream failure outcome, which prevents command mutation.
     * @param allowReplies whether otherwise eligible success and failure replies may be queued.
     * @throws IllegalArgumentException if [length] is outside `0..data.size`.
     */
    public fun applyGraphicsCommand(
        command: TerminalGraphicsCommand,
        data: ByteArray,
        length: Int,
        result: TerminalGraphicsResult,
        allowReplies: Boolean,
    )

    /**
     * Commits an externally decoded upload or query under the same admission,
     * placement and reply rules as [applyGraphicsCommand]. The decoder owns payload
     * validation; core still validates controls, image bounds and placement before
     * replacing any retained image. PNG dimensions come from [image].
     *
     * @param command borrowed original controls for an upload, upload-and-display or query.
     * @param image immutable normalized pixels; retained without copying on successful
     * upload. Published resources may wrap this object to track source retirement;
     * their identity stays stable for the admitted pixel version. Must be non-null
     * for [TerminalGraphicsResult.OK]; never retained for queries.
     * @param result completed decoder outcome; failure prevents mutation.
     * @param allowReplies whether eligible success and failure replies may be queued.
     */
    public fun applyDecodedGraphicsCommand(
        command: TerminalGraphicsCommand,
        image: TerminalRenderImage?,
        result: TerminalGraphicsResult,
        allowReplies: Boolean,
    )
}
