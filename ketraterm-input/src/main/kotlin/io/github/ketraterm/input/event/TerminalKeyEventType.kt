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
package io.github.ketraterm.input.event

/**
 * Physical lifecycle phase of a normalized keyboard event.
 *
 * Hosts must use [PRESS] for an initial physical press, [REPEAT] only when
 * the platform identifies an operating-system repeat, and [RELEASE] when the
 * same physical key is released. A host that cannot distinguish a phase must
 * not infer one; it should report the event as [PRESS] or omit it.
 */
enum class TerminalKeyEventType {
    /** Initial physical key press. */
    PRESS,

    /** Operating-system generated repeat while the physical key remains down. */
    REPEAT,

    /** Physical key release. */
    RELEASE,
}
