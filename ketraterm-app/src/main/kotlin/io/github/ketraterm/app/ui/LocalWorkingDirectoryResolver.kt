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
package io.github.ketraterm.app.ui

import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/** Resolves local OSC 7 file URIs for directory-aware launch actions. */
internal object LocalWorkingDirectoryResolver {
    fun resolve(uriValue: String?): Path? {
        if (uriValue == null) return null
        val uri = runCatching { URI(uriValue) }.getOrNull() ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true) || uri.query != null || uri.fragment != null) return null
        if (!isLocalAuthority(uri.host ?: uri.rawAuthority)) return null
        val pathText = uri.path?.takeIf { it.isNotEmpty() } ?: return null
        val path = runCatching { platformPath(pathText) }.getOrNull() ?: return null
        return path.normalize().takeIf(Files::isDirectory)
    }

    private fun isLocalAuthority(authority: String?): Boolean {
        if (authority.isNullOrBlank()) return true
        return authority.lowercase(Locale.ROOT) in localAuthorities
    }

    private fun platformPath(path: String): Path {
        if (!isWindows) return Path.of(path)
        if (path.length >= 3 && path[0] == '/' && path[1].isLetter() && path[2] == ':') {
            return Path.of(path.substring(1))
        }
        if (path.length >= 3 && path[0] == '/' && path[1].isLetter() && path[2] == '/') {
            return Path.of("${path[1].uppercaseChar()}:${path.substring(2)}")
        }
        return Path.of(path)
    }

    private val isWindows: Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase(Locale.ROOT)
            .contains("windows")

    private val localAuthorities: Set<String> =
        buildSet {
            add("localhost")
            add("127.0.0.1")
            add("[::1]")
            System
                .getenv("COMPUTERNAME")
                ?.takeIf(String::isNotBlank)
                ?.lowercase(Locale.ROOT)
                ?.let(::add)
            System
                .getenv("HOSTNAME")
                ?.takeIf(String::isNotBlank)
                ?.lowercase(Locale.ROOT)
                ?.let(::add)
            runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.lowercase(Locale.ROOT)
                ?.let(::add)
        }
}
