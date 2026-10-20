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
package io.github.ketraterm.protocol

/**
 * Portable OSC 22 pointer shapes with faithful predefined desktop cursors.
 * Wire names are CSS names; they are independent of terminal text-cursor shapes.
 */
public enum class TerminalPointerShape(
    /** Case-sensitive name accepted in a basic OSC 22 set request. */
    public val wireName: String,
) {
    /** Ordinary arrow. */
    DEFAULT("default"),

    /** Precision crosshair. */
    CROSSHAIR("crosshair"),

    /** Text insertion pointer. */
    TEXT("text"),

    /** Busy pointer. */
    WAIT("wait"),

    /** Southwest resize pointer. */
    SW_RESIZE("sw-resize"),

    /** Southeast resize pointer. */
    SE_RESIZE("se-resize"),

    /** Northwest resize pointer. */
    NW_RESIZE("nw-resize"),

    /** Northeast resize pointer. */
    NE_RESIZE("ne-resize"),

    /** North resize pointer. */
    N_RESIZE("n-resize"),

    /** South resize pointer. */
    S_RESIZE("s-resize"),

    /** West resize pointer. */
    W_RESIZE("w-resize"),

    /** East resize pointer. */
    E_RESIZE("e-resize"),

    /** Pointing hand. */
    POINTER("pointer"),

    /** Move pointer. */
    MOVE("move"),
}
