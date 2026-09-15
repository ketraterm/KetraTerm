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

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.XCollection

/** Restart metadata only: shell output, environment variables and running processes are never saved. */
internal data class TerminalTabState(
    @field:OptionTag
    val profileId: String? = null,
    @field:OptionTag
    val customTitle: String? = null,
    @field:OptionTag
    val workingDirectory: String? = null,
)

/** Tab order and selection are published together so background IDE saves see a coherent snapshot. */
internal data class TerminalTabsState(
    @field:XCollection
    val tabs: List<TerminalTabState> = emptyList(),
    @field:OptionTag
    val selectedTabIndex: Int = -1,
)

/** Stores project-local tab snapshots without accessing terminal components during IDE saves. */
@Service(Service.Level.PROJECT)
@State(name = "KetraTermTerminalTabs", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class KetraTermTerminalTabsStorage : SerializablePersistentStateComponent<TerminalTabsState>(TerminalTabsState()) {
    fun replace(state: TerminalTabsState) {
        val snapshot = state.normalized()
        if (snapshot == this.state) return
        updateState { snapshot }
    }

    override fun loadState(state: TerminalTabsState) {
        super.loadState(state.normalized())
    }

    private fun TerminalTabsState.normalized(): TerminalTabsState =
        copy(
            tabs = java.util.List.copyOf(tabs),
            selectedTabIndex =
                when {
                    tabs.isEmpty() -> -1
                    selectedTabIndex in tabs.indices -> selectedTabIndex
                    else -> 0
                },
        )
}
