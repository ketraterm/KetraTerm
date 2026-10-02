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
package io.github.ketraterm.intellij.ui

import com.intellij.execution.filters.*
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.replaceService
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.ui.swing.api.*
import kotlinx.coroutines.*
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel

class IntellijTerminalHyperlinkContractTest : BasePlatformTestCase() {
    fun testDiscoveredUrlDoesNotAcquirePersistentConsoleNavigationDecoration() {
        val text = "https://example.com/resource\n"
        val result = UrlFilter(project).applyFilter(text, text.length)
        assertNotNull(result)
        val item = result!!.resultItems.single()
        val presentation = intellijHyperlinkPresentation(item)
        assertEquals(SwingHyperlinkActivation.DIRECT, intellijHyperlinkActivation(item))
        assertNotNull(presentation.normal)
        // A missing followed style makes the shared renderer retain the normal appearance after navigation.
        assertNull(presentation.followed)
    }

    fun testOsc8DecorationsPreserveTerminalColorsUntilNativeActivation() {
        val presentation = intellijOsc8HyperlinkPresentation()
        assertTrue(presentation.isVisible)
        assertEquals(TerminalRenderUnderline.DOTTED, presentation.normal?.underlineStyle)
        assertEquals(TerminalRenderUnderline.SINGLE, presentation.hovered?.underlineStyle)
        assertEquals(2, presentation.hovered?.underlineThickness)
        assertNull(presentation.normal?.foregroundArgb)
        assertNull(presentation.hovered?.foregroundArgb)
        assertNull(presentation.hovered?.underlineArgb)
        assertEquals(intellijImplicitHyperlinkPresentation().active, presentation.active)
        assertNull(presentation.followed)
    }

    fun testImplicitProviderLinksRetainSubtleHoverDecoration() {
        val item = Filter.ResultItem(0, 4, HyperlinkInfo {}).also { it.isInvisibleLink = true }
        val presentation = intellijImplicitHyperlinkPresentation()
        assertEquals(intellijHyperlinkPresentation(item), presentation)
        assertFalse(presentation.isVisible)
        assertEquals(TerminalRenderUnderline.NONE, presentation.normal?.underlineStyle)
        assertEquals(TerminalRenderUnderline.SINGLE, presentation.hovered?.underlineStyle)
        assertEquals(1, presentation.hovered?.underlineThickness)
        assertEquals(SwingHyperlinkActivation.MODIFIER, intellijHyperlinkActivation(item))
    }

    fun testImplicitThemeSnapshotsRemainImmutableAcrossThemeChanges() {
        val scheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        val first = TextAttributes(Color.BLUE, Color.WHITE, Color.RED, EffectType.LINE_UNDERSCORE, 0)
        scheme.setAttributes(CodeInsightColors.HYPERLINK_ATTRIBUTES, first)
        val before = intellijImplicitHyperlinkPresentation(scheme)
        scheme.setAttributes(
            CodeInsightColors.HYPERLINK_ATTRIBUTES,
            TextAttributes(Color.MAGENTA, null, Color.GREEN, EffectType.WAVE_UNDERSCORE, 0),
        )
        val after = intellijImplicitHyperlinkPresentation(scheme)
        assertEquals(Color.BLUE.rgb, before.active?.foregroundArgb)
        assertEquals(Color.RED.rgb, before.active?.underlineArgb)
        assertEquals(TerminalRenderUnderline.SINGLE, before.active?.underlineStyle)
        assertEquals(Color.MAGENTA.rgb, after.active?.foregroundArgb)
        assertEquals(Color.GREEN.rgb, after.active?.underlineArgb)
        assertEquals(TerminalRenderUnderline.CURLY, after.active?.underlineStyle)
    }

    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    fun testFileContentUpdatesPreserveConfigurationAndStructuralUpdatesInvalidateIt() {
        val file = myFixture.addFileToProject("paths/source.kt", "source").virtualFile
        val detector = IntellijTerminalHyperlinkDetector(project)
        val publisher = ApplicationManager.getApplication().messageBus.syncPublisher(VirtualFileManager.VFS_CHANGES)

        fun publish(events: List<VFileEvent>) {
            WriteAction.run<RuntimeException> { publisher.after(events) }
        }
        runBlocking {
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) { detector.configurationChanges.collect {} }
            try {
                yield()
                val original = detector.configurationGeneration
                publish(emptyList())
                publish(listOf(VFileContentChangeEvent(this, file, 0L, 1L)))
                assertEquals(original, detector.configurationGeneration)
                publish(listOf(VFileCreateEvent(this, file.parent, "created.kt", false, null, null, null)))
                assertEquals(original + 1, detector.configurationGeneration)
                publish(listOf(VFileDeleteEvent(this, file)))
                assertEquals(original + 2, detector.configurationGeneration)
            } finally {
                subscription.cancelAndJoin()
            }
            val disposed = detector.configurationGeneration
            publish(listOf(VFileDeleteEvent(this, file)))
            assertEquals(disposed, detector.configurationGeneration)
        }
    }

    fun testStatelessSingletonProviderRemainsUsableAfterReplayAndCancellationCleanup() {
        val singleton =
            object : Filter, DumbAware {
                override fun applyFilter(
                    line: String,
                    entireLength: Int,
                ): Filter.Result {
                    val start = entireLength - line.length
                    return Filter.Result(start, start + 6, HyperlinkInfo {})
                }
            }
        val provider = ConsoleFilterProvider { arrayOf(singleton) }
        ExtensionTestUtil.maskExtensions(ConsoleFilterProvider.FILTER_PROVIDERS, listOf(provider), testRootDisposable)
        val detector = IntellijTerminalHyperlinkDetector(project)
        val expected = SwingHyperlinkTextRange(SwingHyperlinkTextPosition(0, 0), SwingHyperlinkTextPosition(0, 6))
        assertEquals(expected, detect(detector, request("target\n")).single().sourceRange)
        assertEquals(expected, detect(detector, request("target\n", replay = 1)).single().sourceRange)
        detector.discardOrderedState()
        assertEquals(expected, detect(detector, request("target\n", replay = 2)).single().sourceRange)
    }

    fun testInterruptedProviderStateIsReconstructedBeforeReplay() {
        var creations = 0
        val consumed = ArrayList<Pair<Int, String>>()
        val provider =
            ConsoleFilterProvider {
                val instance = ++creations
                arrayOf(
                    object : Filter, DumbAware {
                        override fun applyFilter(
                            line: String,
                            entireLength: Int,
                        ): Filter.Result? {
                            consumed.add(instance to line)
                            if (instance == 1 && line == "second\n") throw CancellationException("Interrupted read")
                            return null
                        }
                    },
                )
            }
        ExtensionTestUtil.maskExtensions(ConsoleFilterProvider.FILTER_PROVIDERS, listOf(provider), testRootDisposable)
        val detector = IntellijTerminalHyperlinkDetector(project)
        val first =
            SwingHyperlinkDetectionRequest(
                listOf("first\n", "second\n"),
                longArrayOf(0, 1),
                context = SwingHyperlinkDetectionContext.ORDERED_CONTENT,
            )
        val failure = runCatching { detect(detector, first) }.exceptionOrNull()
        assertTrue(failure?.cause is java.util.concurrent.CancellationException)
        val replay =
            SwingHyperlinkDetectionRequest(
                listOf("first\n", "second\n"),
                longArrayOf(0, 1),
                context = SwingHyperlinkDetectionContext.ORDERED_CONTENT,
                analysisEpoch = 1,
            )
        assertEmpty(detect(detector, replay))
        assertEquals(2, creations)
        assertEquals(listOf(1 to "first\n", 1 to "second\n", 2 to "first\n", 2 to "second\n"), consumed)
    }

    fun testNavigationRetainsTheOriginalGestureAnchor() {
        var anchor: com.intellij.ui.awt.RelativePoint? = null
        val native =
            object : HyperlinkInfoBase() {
                override fun navigate(
                    project: Project,
                    hyperlinkLocationPoint: com.intellij.ui.awt.RelativePoint?,
                ) {
                    anchor = hyperlinkLocationPoint
                }
            }
        val component = JPanel()
        val event = MouseEvent(component, MouseEvent.MOUSE_RELEASED, 0L, 0, 15, 27, 1, false, MouseEvent.BUTTON1)
        assertTrue(IntellijTerminalHyperlinkAction(project, native).open(event))
        assertSame(component, anchor?.component)
        assertEquals(java.awt.Point(15, 27), anchor?.point)
    }

    fun testMalformedFileCoordinatesDoNotSilentlyNavigateToTheWrongLine() {
        val file = myFixture.addFileToProject("paths/source.kt", "line").virtualFile
        val detector = IntellijTerminalHyperlinkDetector(project, Path.of(file.parent.path))
        val links =
            detect(
                detector,
                request(
                    "source.kt:0 source.kt:999999999999999999999 source.kt:1:0 source.kt:1:2\n",
                    SwingHyperlinkDetectionContext.INDEPENDENT_LINE,
                ),
            )
        assertEquals(1, links.size)
        assertEquals(Path.of(file.path).toUri().toASCIIString(), links.single().uri)
    }

    fun testExistingDirectoriesAndFileUrisUseNativeDirectoryNavigation() {
        val directory = myFixture.addFileToProject("cwd/src/source.kt", "source").virtualFile.parent
        val detector = IntellijTerminalHyperlinkDetector(project, Path.of(directory.parent.path))
        val uri = Path.of(directory.path).toUri().toASCIIString()
        val navigated = ArrayList<VirtualFile>()
        val systemDirectories = ArrayList<Path>()
        val originalNavigation = PsiNavigationSupport.getInstance()
        val navigation =
            object : PsiNavigationSupport() {
                override fun getDescriptor(element: PsiElement): Navigatable? = originalNavigation.getDescriptor(element)

                override fun createNavigatable(
                    project: Project,
                    file: VirtualFile,
                    offset: Int,
                ): Navigatable = originalNavigation.createNavigatable(project, file, offset)

                override fun canNavigate(element: PsiElement): Boolean = originalNavigation.canNavigate(element)

                override fun navigateToDirectory(
                    directory: PsiDirectory,
                    requestFocus: Boolean,
                ) {
                    assertTrue(requestFocus)
                    navigated.add(directory.virtualFile)
                }

                override fun openDirectoryInSystemFileManager(path: Path) {
                    systemDirectories.add(path)
                }
            }
        ApplicationManager.getApplication().replaceService(PsiNavigationSupport::class.java, navigation, testRootDisposable)
        val paths = listOf(directory.path, "${directory.path}/", "./src", "src/", "src")
        val fileUris = listOf(uri.removeSuffix("/"), uri)

        fun assertNavigation(inProject: Boolean) {
            val actualInProject =
                ReadAction.computeBlocking<Boolean, RuntimeException> {
                    val manager = PsiManager.getInstance(project)
                    manager.isInProject(checkNotNull(manager.findDirectory(directory)))
                }
            assertEquals("Directory navigation depends on registered project content", inProject, actualInProject)
            for (text in paths + fileUris) {
                val link = detect(detector, request("$text\n", SwingHyperlinkDetectionContext.INDEPENDENT_LINE)).single()
                assertEquals(text, SwingHyperlinkTextPosition(0, 0), link.sourceRange.start)
                assertEquals(text, SwingHyperlinkTextPosition(0, text.length), link.sourceRange.end)
                if (text in paths) {
                    assertEquals(text, uri, link.uri)
                    assertFalse(text, link.presentation.isVisible)
                    assertEquals(text, SwingHyperlinkActivation.MODIFIER, link.activation)
                    assertEquals(text, TerminalRenderUnderline.NONE, link.presentation.normal?.underlineStyle)
                    assertEquals(text, TerminalRenderUnderline.SINGLE, link.presentation.hovered?.underlineStyle)
                } else {
                    assertEquals(text, link.uri)
                    assertTrue(text, link.presentation.isVisible)
                    assertEquals(text, SwingHyperlinkActivation.DIRECT, link.activation)
                }
                val ideCount = navigated.size
                val systemCount = systemDirectories.size
                assertTrue(text, link.action.open())
                if (inProject) {
                    assertEquals(text, listOf(directory), navigated.drop(ideCount))
                    assertEquals(text, systemCount, systemDirectories.size)
                } else {
                    assertEquals(text, listOf(Path.of(directory.path)), systemDirectories.drop(systemCount))
                    assertEquals(text, ideCount, navigated.size)
                }
            }
        }
        assertNavigation(inProject = false)
        PsiTestUtil.addContentRoot(myFixture.module, directory.parent)
        try {
            assertNavigation(inProject = true)
        } finally {
            PsiTestUtil.removeContentEntry(myFixture.module, directory.parent)
        }
        assertEquals(paths.size + fileUris.size, navigated.size)
        assertEquals(paths.size + fileUris.size, systemDirectories.size)
    }

    fun testBareChildFilesResolveButMissingChildrenAndPathNoiseDoNot() {
        val file = myFixture.addFileToProject("cwd/README", "source").virtualFile
        val detector = IntellijTerminalHyperlinkDetector(project, Path.of(file.parent.path))
        val links =
            detect(
                detector,
                request("README missing . .. / // \\ \\\\ \n", SwingHyperlinkDetectionContext.INDEPENDENT_LINE),
            )
        assertEquals(1, links.size)
        assertEquals(Path.of(file.path).toUri().toASCIIString(), links.single().uri)
        assertEquals(SwingHyperlinkTextPosition(0, 6), links.single().sourceRange.end)
    }

    fun testFileCoordinatesNavigateToTheRequestedLineAndColumn() {
        val file = myFixture.addFileToProject("cwd/source.txt", "first\nsecond\nthird").virtualFile
        val detector = IntellijTerminalHyperlinkDetector(project, Path.of(file.parent.path))
        for ((text, position) in listOf("source.txt:2:3" to LogicalPosition(1, 2), "source.txt(3,2)" to LogicalPosition(2, 1))) {
            val link = detect(detector, request("$text\n", SwingHyperlinkDetectionContext.INDEPENDENT_LINE)).single()
            assertTrue(text, link.action.open())
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            assertNotNull(text, editor)
            assertEquals(text, position, editor?.caretModel?.logicalPosition)
        }
    }

    fun testMixedLineRunsIndependentPathsAndEveryOrderedProvider() {
        val file = myFixture.addFileToProject("cwd/source.kt", "fun source() = Unit").virtualFile
        val applied = ArrayList<Int>()
        val providers =
            (0..1).map { index ->
                ConsoleFilterProvider {
                    arrayOf(
                        object : Filter, DumbAware {
                            override fun applyFilter(
                                line: String,
                                entireLength: Int,
                            ): Filter.Result {
                                applied.add(index)
                                val start = entireLength - line.length + line.indexOf("trace")
                                return Filter.Result(start, start + 5, HyperlinkInfo {})
                            }
                        },
                    )
                }
            }
        ExtensionTestUtil.maskExtensions(ConsoleFilterProvider.FILTER_PROVIDERS, providers, testRootDisposable)
        val detector = IntellijTerminalHyperlinkDetector(project, Path.of(file.parent.path))
        val fileUri = Path.of(file.path).toUri().toASCIIString()
        val text = "https://example.invalid/mixed $fileUri source.kt:2:3 trace\n"
        val independent = detect(detector, request(text, SwingHyperlinkDetectionContext.INDEPENDENT_LINE))
        val ordered = detect(detector, request(text))
        assertEquals(
            listOf("https://example.invalid/mixed", fileUri, fileUri),
            independent.sortedBy { it.sourceRange.start.offset }.map { it.uri },
        )
        assertEquals(listOf(0, 1), applied)
        assertEquals(listOf(2, 3), ordered.map { it.providerOrder })
        for (explicit in independent.take(2)) {
            assertTrue(explicit.presentation.isVisible)
            assertEquals(SwingHyperlinkActivation.DIRECT, explicit.activation)
            assertNull(explicit.presentation.hovered)
            assertNull(explicit.presentation.active)
        }
        val path = independent.last()
        assertFalse(path.presentation.isVisible)
        assertEquals(SwingHyperlinkActivation.MODIFIER, path.activation)
        assertNull(path.presentation.normal?.foregroundArgb)
        assertNull(path.presentation.hovered?.foregroundArgb)
        assertEquals(TerminalRenderUnderline.NONE, path.presentation.normal?.underlineStyle)
        assertEquals(TerminalRenderUnderline.SINGLE, path.presentation.hovered?.underlineStyle)
        val hoverAlpha = checkNotNull(path.presentation.hovered?.underlineArgb).ushr(24)
        assertTrue(hoverAlpha in 1..254)
        assertEquals(independent.first().presentation.normal, path.presentation.active)
    }

    fun testHistoricalDirectoryWinsOverLaunchDirectory() {
        val first = myFixture.addFileToProject("first/source.kt", "first").virtualFile
        val second = myFixture.addFileToProject("second/source.kt", "second").virtualFile
        val detector =
            IntellijTerminalHyperlinkDetector(project, Path.of(second.parent.path)) { id ->
                if (id == 71L) Path.of(first.parent.path).toUri().toASCIIString() else null
            }
        val request =
            SwingHyperlinkDetectionRequest(
                listOf("source.kt:1\n", "source.kt:1\n"),
                longArrayOf(0, 1),
                firstLineIds = longArrayOf(71, 72),
            )
        assertEquals(listOf(first, second).map { Path.of(it.path).toUri().toASCIIString() }, detect(detector, request).map { it.uri })
    }

    fun testOrderedStateContinuesAcrossBatchesAndEvictionAndReconstructsOnReplay() {
        var creations = 0
        val consumed = ArrayList<String>()
        val provider =
            ConsoleFilterProvider {
                creations++
                arrayOf(
                    object : Filter, DumbAware {
                        private var firstOffset = -1

                        override fun applyFilter(
                            line: String,
                            entireLength: Int,
                        ): Filter.Result? {
                            consumed.add(line)
                            if (line == "target\n") firstOffset = entireLength - line.length
                            return if (line == "producer\n" &&
                                firstOffset >= 0
                            ) {
                                Filter.Result(firstOffset, firstOffset + 6, HyperlinkInfo {})
                            } else {
                                null
                            }
                        }
                    },
                )
            }
        ExtensionTestUtil.maskExtensions(ConsoleFilterProvider.FILTER_PROVIDERS, listOf(provider), testRootDisposable)
        val detector = IntellijTerminalHyperlinkDetector(project)
        detect(detector, request("prefix\n", first = 0))
        detect(detector, request("target\n", first = 1))
        val result = detect(detector, request("producer\n", first = 2, retained = 1)).single()
        assertEquals(1, creations)
        assertEquals(SwingHyperlinkTextPosition(1, 0), result.sourceRange.start)
        assertEquals(SwingHyperlinkTextPosition(2, 9), result.consumedThrough)
        assertEquals(SwingHyperlinkTextPosition(0, 0), result.dependencyRange.start)
        detect(detector, request("edited\n", first = 1, replay = 1, retained = 1))
        assertEmpty(detect(detector, request("producer\n", first = 2, replay = 1, retained = 1)))
        assertEquals(2, creations)
        assertEquals(listOf("prefix\n", "target\n", "producer\n", "edited\n", "producer\n"), consumed)
    }

    fun testNativeMetadataAndCallbacksRemainAttachedToTheirAction() {
        var entered: Rectangle? = null
        var exited = 0
        var navigated = 0
        val popup = DefaultActionGroup()
        val info =
            object : HyperlinkInfo, HyperlinkWithHoverInfo, HyperlinkWithPopupMenuInfo {
                override fun navigate(project: Project) {
                    navigated++
                }

                override fun onMouseEntered(
                    component: JComponent,
                    linkBounds: Rectangle,
                ) {
                    entered = linkBounds
                }

                override fun onMouseExited() {
                    exited++
                }

                override fun getPopupMenuGroup(event: MouseEvent) = popup
            }
        val normal = TextAttributes(Color.BLUE, Color.WHITE, Color.RED, EffectType.WAVE_UNDERSCORE, 0)
        val followed = TextAttributes(Color.MAGENTA, null, null, EffectType.LINE_UNDERSCORE, 0)
        val hovered = TextAttributes(Color.GREEN, null, Color.YELLOW, EffectType.BOLD_DOTTED_LINE, 0)
        val item = Filter.ResultItem(0, 4, info, normal, followed, hovered)
        val style = intellijHyperlinkPresentation(item)
        assertEquals(Color.BLUE.rgb, style.normal?.foregroundArgb)
        assertEquals(Color.WHITE.rgb, style.normal?.backgroundArgb)
        assertEquals(TerminalRenderUnderline.CURLY, style.normal?.underlineStyle)
        assertEquals(Color.GREEN.rgb, style.hovered?.foregroundArgb)
        assertEquals(Color.MAGENTA.rgb, style.followed?.foregroundArgb)
        assertEquals(SwingHyperlinkActivation.DIRECT, intellijHyperlinkActivation(item))
        item.isInvisibleLink = true
        val implicit = intellijHyperlinkPresentation(item)
        assertFalse(implicit.isVisible)
        assertNull(implicit.normal?.foregroundArgb)
        assertEquals(TerminalRenderUnderline.DOTTED, implicit.hovered?.underlineStyle)
        assertEquals(Color.GREEN.rgb, implicit.hovered?.foregroundArgb)
        assertEquals(
            EditorColorsManager
                .getInstance()
                .globalScheme
                .getAttributes(CodeInsightColors.HYPERLINK_ATTRIBUTES)
                ?.foregroundColor
                ?.rgb,
            implicit.active?.foregroundArgb,
        )
        assertEquals(SwingHyperlinkActivation.MODIFIER, intellijHyperlinkActivation(item))
        val action = IntellijTerminalHyperlinkAction(project, info)
        val component = JPanel()
        action.mouseEntered(component, 10, 20, 40, 18)
        assertEquals(Rectangle(10, 20, 40, 18), entered)
        action.mouseExited()
        assertEquals(1, exited)
        assertSame(popup, action.popupGroup(MouseEvent(component, MouseEvent.MOUSE_RELEASED, 0L, 0, 12, 22, 1, true, MouseEvent.BUTTON3)))
        assertTrue(action.open())
        assertEquals(1, navigated)
    }

    private fun request(
        text: String,
        context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.ORDERED_CONTENT,
        first: Long = 0,
        replay: Long = 0,
        retained: Long = 0,
    ) = SwingHyperlinkDetectionRequest(
        listOf(text),
        longArrayOf(first),
        context = context,
        analysisEpoch = replay,
        firstRetainedRow = retained,
    )

    private fun detect(
        detector: IntellijTerminalHyperlinkDetector,
        request: SwingHyperlinkDetectionRequest,
    ): List<SwingHyperlink> {
        val result =
            AppExecutorUtil.getAppExecutorService().submit(
                Callable {
                    runBlocking { detector.detect(request) }
                },
            )
        return try {
            result.get(30, TimeUnit.SECONDS)
        } finally {
            result.cancel(true)
        }
    }
}
