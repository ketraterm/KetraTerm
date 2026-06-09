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
package com.gagik.terminal.standalone

import javax.swing.UIManager
import javax.swing.UnsupportedLookAndFeelException

/**
 * Installs the standalone application's Swing look and feel.
 */
internal object StandaloneLookAndFeel {
    fun install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: ReflectiveOperationException) {
            // Keep the JDK default rather than failing terminal startup.
        } catch (_: UnsupportedLookAndFeelException) {
            // Keep the JDK default rather than failing terminal startup.
        }
    }
}
