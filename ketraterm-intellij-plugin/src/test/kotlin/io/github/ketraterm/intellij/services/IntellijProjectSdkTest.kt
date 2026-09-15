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

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.SimpleJavaSdkType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalShellEnvironment
import java.nio.file.Path

/** Project-model tests for the same JDK selection used by IntelliJ's terminal. */
class IntellijProjectSdkTest : BasePlatformTestCase() {
    private var sdkNumber = 0

    fun testMissingProjectSdkDoesNotInject() {
        runWriteAction {
            ProjectRootManager.getInstance(project).projectSdk = null
        }

        assertNull(projectSdkBinPath(project))
    }

    fun testNonJavaProjectSdkDoesNotInject() {
        val home = myFixture.tempDirFixture.findOrCreateDir("other-sdk")
        myFixture.tempDirFixture.findOrCreateDir("other-sdk/bin")
        installSdk(home.path, "Python SDK")

        assertNull(projectSdkBinPath(project))
    }

    fun testJavaSdkBinDirectoryUsesVfsPath() {
        val home = myFixture.tempDirFixture.findOrCreateDir("jdk")
        val bin = myFixture.tempDirFixture.findOrCreateDir("jdk/bin")
        installSdk(home.path)

        assertEquals(Path.of(bin.path), projectSdkBinPath(project))
    }

    fun testMissingBinDirectoryFallsBackToConfiguredHome() {
        val home = myFixture.tempDirFixture.findOrCreateDir("jdk-without-bin")
        installSdk(home.path)

        assertEquals(Path.of(home.path, "bin"), projectSdkBinPath(project))
    }

    fun testUnavailableHomeFallsBackToConfiguredPath() {
        val parent = myFixture.tempDirFixture.findOrCreateDir("sdk-parent")
        val home = Path.of(parent.path, "unavailable-jdk")
        installSdk(home.toString())

        assertEquals(home.resolve("bin"), projectSdkBinPath(project))
    }

    fun testSdkWithoutHomeDoesNotInject() {
        installSdk(null)

        assertNull(projectSdkBinPath(project))
    }

    fun testInvalidConfiguredHomeDoesNotPreventTerminalStartup() {
        installSdk("invalid\u0000jdk")

        assertNull(projectSdkBinPath(project))
    }

    fun testSdkChangeAffectsNextLookup() {
        val first = myFixture.tempDirFixture.findOrCreateDir("first-jdk")
        val second = myFixture.tempDirFixture.findOrCreateDir("second-jdk")
        installSdk(first.path)
        assertEquals(Path.of(first.path, "bin"), projectSdkBinPath(project))

        installSdk(second.path)

        assertEquals(Path.of(second.path, "bin"), projectSdkBinPath(project))
    }

    fun testProjectLaunchCustomizationUsesCurrentSdkAndRespectsDisabledSetting() {
        val first = myFixture.tempDirFixture.findOrCreateDir("first-launch-jdk")
        val second = myFixture.tempDirFixture.findOrCreateDir("second-launch-jdk")
        val profile =
            TerminalProfile(
                id = "local",
                displayName = "Local",
                command = listOf("custom-shell"),
                environment = mapOf("JAVA_HOME" to "explicit-jdk", "CUSTOM" to "value"),
            )
        installSdk(first.path)
        val firstLaunch = profile.withProjectSdkEnvironment(project, enabled = true)

        installSdk(second.path)
        val secondLaunch = profile.withProjectSdkEnvironment(project, enabled = true)

        assertEquals(Path.of(first.path).toString(), firstLaunch.shellEnvironment.variables["JAVA_HOME"])
        assertEquals(Path.of(first.path, "bin").toString(), firstLaunch.shellEnvironment.pathPrefix)
        assertEquals(Path.of(second.path).toString(), secondLaunch.shellEnvironment.variables["JAVA_HOME"])
        assertEquals(Path.of(second.path, "bin").toString(), secondLaunch.shellEnvironment.pathPrefix)
        assertEquals(profile.environment, secondLaunch.environment)
        assertEquals(TerminalShellEnvironment.Empty, profile.shellEnvironment)
        assertSame(profile, profile.withProjectSdkEnvironment(project, enabled = false))
    }

    private fun installSdk(
        homePath: String?,
        typeName: String = "JavaSDK",
    ) {
        runWriteAction {
            val table = ProjectJdkTable.getInstance()
            val sdk = table.createSdk("KetraTerm test SDK ${sdkNumber++}", TestSdkType(typeName))
            val modificator = sdk.sdkModificator
            modificator.homePath = homePath
            modificator.versionString = "test"
            modificator.commitChanges()
            table.addJdk(sdk, testRootDisposable)
            ProjectRootManager.getInstance(project).projectSdk = sdk
        }
    }

    private class TestSdkType(
        private val typeName: String,
    ) : SimpleJavaSdkType() {
        override fun getName(): String = typeName
    }
}
