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
package io.github.ketraterm.intellij.settings

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.OptionTag
import io.github.ketraterm.session.TerminalStartupCommand

/** Local project preferences for newly launched terminals; excluded from shared project configuration. */
@Service(Service.Level.PROJECT)
@State(name = "KetraTermProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class KetraTermProjectSettings : SerializablePersistentStateComponent<KetraTermProjectSettings.State>(State()) {
    /** Validates and replaces preferences without changing already running terminals. */
    fun replaceState(nextState: State) {
        TerminalStartupCommand.fromText(nextState.startupCommand)
        updateState { nextState }
    }

    /** @property startupCommand command line submitted once when each new shell is ready; blank disables it. */
    data class State(
        @field:OptionTag val startupCommand: String = "",
    )
}
