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
package io.github.ketraterm.ui.swing.suggestion

/**
 * Marks experimental suggestion providers, presentation, and interaction APIs.
 * Suggestions may be incomplete, rank poorly, or behave unexpectedly in edge cases.
 *
 * Consumers can acknowledge the warning with opt-in or propagate this marker from their own experimental APIs.
 * Source and binary contracts may also change before stabilization.
 */
@RequiresOptIn(
    message = "KetraTerm suggestions are experimental: they may be incomplete, rank poorly, or behave unexpectedly in edge cases.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class ExperimentalShellSuggestionsApi
