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
package io.github.ketraterm.ui.swing.input

import java.awt.event.KeyEvent

/**
 * Tracks physical keys claimed by UI actions until their matching release.
 *
 * Swing delivers presses, typed characters, and releases independently. A UI
 * action that hides its own surface on press must therefore retain ownership
 * until release, or the remaining events can leak into terminal input.
 */
internal class ClaimedSwingKeyLifecycle {
    private val claimedKeys = IntArray(MAX_SIMULTANEOUS_CLAIMS)
    private var claimedCount = 0
    private var suppressNextTypedEvent = false

    /** Returns whether [event] belongs to an already-claimed physical key. */
    fun ownsRepeatedPress(event: KeyEvent): Boolean {
        val key = physicalKey(event)
        for (index in 0 until claimedCount) {
            if (claimedKeys[index] == key) return true
        }
        suppressNextTypedEvent = false
        return false
    }

    /** Claims [event] through its matching release. */
    fun claim(event: KeyEvent) {
        val key = physicalKey(event)
        for (index in 0 until claimedCount) {
            if (claimedKeys[index] == key) return
        }
        if (claimedCount < claimedKeys.size) {
            claimedKeys[claimedCount++] = key
        }
        suppressNextTypedEvent = !event.isActionKey
    }

    /** Returns whether the next Swing typed event belongs to a claimed press. */
    fun ownsTypedEvent(): Boolean {
        if (!suppressNextTypedEvent) return false
        suppressNextTypedEvent = false
        return true
    }

    /** Releases and returns ownership of [event], if claimed. */
    fun release(event: KeyEvent): Boolean {
        val key = physicalKey(event)
        for (index in 0 until claimedCount) {
            if (claimedKeys[index] != key) continue
            claimedCount--
            claimedKeys[index] = claimedKeys[claimedCount]
            claimedKeys[claimedCount] = 0
            suppressNextTypedEvent = false
            return true
        }
        return false
    }

    /** Clears ownership when focus loss invalidates the pending lifecycle. */
    fun clear() {
        claimedKeys.fill(0, 0, claimedCount)
        claimedCount = 0
        suppressNextTypedEvent = false
    }

    private fun physicalKey(event: KeyEvent): Int = (event.keyCode shl KEY_LOCATION_BITS) or (event.keyLocation and KEY_LOCATION_MASK)

    private companion object {
        private const val MAX_SIMULTANEOUS_CLAIMS = 16
        private const val KEY_LOCATION_BITS = 3
        private const val KEY_LOCATION_MASK = (1 shl KEY_LOCATION_BITS) - 1
    }
}
