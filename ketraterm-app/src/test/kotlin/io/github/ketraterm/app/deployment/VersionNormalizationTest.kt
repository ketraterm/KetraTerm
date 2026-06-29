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
package io.github.ketraterm.app.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VersionNormalizationTest {
    @Test
    fun testValidAlphaVersions() {
        assertEquals("1.1.1", VersionNormalization.normalize("0.1.0-alpha1"))
        assertEquals("1.1.1", VersionNormalization.normalize("0.1.0-alpha01"))
        assertEquals("1.1.2", VersionNormalization.normalize("0.1.0-alpha2"))
        assertEquals("1.1.2", VersionNormalization.normalize("0.1.0-alpha02"))
        assertEquals("1.1.19", VersionNormalization.normalize("0.1.0-alpha19"))
    }

    @Test
    fun testValidBetaVersions() {
        assertEquals("1.1.21", VersionNormalization.normalize("0.1.0-beta1"))
        assertEquals("1.1.21", VersionNormalization.normalize("0.1.0-beta01"))
        assertEquals("1.1.22", VersionNormalization.normalize("0.1.0-beta2"))
        assertEquals("1.1.39", VersionNormalization.normalize("0.1.0-beta19"))
    }

    @Test
    fun testValidRcVersions() {
        assertEquals("1.1.41", VersionNormalization.normalize("0.1.0-rc1"))
        assertEquals("1.1.41", VersionNormalization.normalize("0.1.0-rc01"))
        assertEquals("1.1.42", VersionNormalization.normalize("0.1.0-rc2"))
        assertEquals("1.1.59", VersionNormalization.normalize("0.1.0-rc19"))
    }

    @Test
    fun testValidStableAndPatchVersions() {
        assertEquals("1.1.99", VersionNormalization.normalize("0.1.0"))
        assertEquals("1.1.101", VersionNormalization.normalize("0.1.1-alpha1"))
        assertEquals("1.1.122", VersionNormalization.normalize("0.1.1-beta2"))
        assertEquals("1.1.143", VersionNormalization.normalize("0.1.1-rc3"))
        assertEquals("1.1.199", VersionNormalization.normalize("0.1.1"))
        assertEquals("1.2.25", VersionNormalization.normalize("0.2.0-beta5"))
        assertEquals("2.0.99", VersionNormalization.normalize("1.0.0"))
        assertEquals("6.2.399", VersionNormalization.normalize("5.2.3"))
    }

    @Test
    fun testSnapshotSuffixStripping() {
        assertEquals("1.1.2", VersionNormalization.normalize("0.1.0-alpha02-SNAPSHOT"))
        assertEquals("1.1.99", VersionNormalization.normalize("0.1.0-SNAPSHOT"))
    }

    @Test
    fun testInvalidPreReleaseVersionsOutOfBounds() {
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1.0-alpha0") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1.0-alpha20") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1.0-beta0") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1.0-beta20") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1.0-rc0") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1.0-rc20") }
    }

    @Test
    fun testMalformedVersionStrings() {
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("abc") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1.a") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("0.1.0-unknown") }
        assertFailsWith<IllegalArgumentException> { VersionNormalization.normalize("") }
    }
}
