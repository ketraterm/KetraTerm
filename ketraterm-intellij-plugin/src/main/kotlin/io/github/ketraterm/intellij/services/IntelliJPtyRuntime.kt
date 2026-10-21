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

import io.github.ketraterm.intellij.KetraTermBundle

/**
 * IntelliJ host adapter for local PTY startup.
 *
 * The reusable KetraTerm modules own PTY process creation and session wiring.
 * IntelliJ owns the Pty4J/JNA runtime through its platform classpath and native
 * distribution. The plugin build excludes Maven Pty4J/JNA artifacts from the
 * plugin package so the IDE process never loads a second native stack from the
 * plugin classloader.
 */
internal object IntelliJPtyRuntime {
    internal object BundledPty4jRuntime {
        /**
         * Fails fast when the IntelliJ Platform Pty4J module is not visible to
         * the plugin classloader.
         *
         * The class lookup is intentionally tiny and side-effect free: classpath
         * ownership is a build/plugin descriptor contract, while actual process
         * creation remains in `ketraterm-pty`.
         */
        fun requireAvailable() {
            requireAvailable(javaClass.classLoader)
        }

        internal fun requireAvailable(classLoader: ClassLoader) {
            try {
                Class.forName("com.pty4j.PtyProcessBuilder", false, classLoader)
            } catch (cause: ClassNotFoundException) {
                throw IllegalStateException(
                    KetraTermBundle.message("terminal.startup.runtimeUnavailable"),
                    cause,
                )
            }
        }
    }
}
