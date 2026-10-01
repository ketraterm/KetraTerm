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
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.ui.swing.api.*
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel

class IntellijTerminalHyperlinkContractTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

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
                            if (instance == 1 && line == "second\n") throw kotlinx.coroutines.CancellationException("Interrupted read")
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
        val text = "https://example.invalid/mixed source.kt:2:3 trace\n"
        val independent = detect(detector, request(text, SwingHyperlinkDetectionContext.INDEPENDENT_LINE))
        val ordered = detect(detector, request(text))
        assertEquals(listOf("https://example.invalid/mixed", Path.of(file.path).toUri().toASCIIString()), independent.map { it.uri })
        assertEquals(listOf(0, 1), applied)
        assertEquals(listOf(2, 3), ordered.map { it.providerOrder })
        assertTrue(independent.all { it.presentation.isVisible && it.activation == SwingHyperlinkActivation.DIRECT })
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
        assertEquals(TerminalRenderUnderline.NONE, implicit.hovered?.underlineStyle)
        assertEquals(Color.GREEN.rgb, implicit.active?.foregroundArgb)
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
                    runBlocking { ArrayList<SwingHyperlink>().also { detector.detect(request, it::add) } }
                },
            )
        return try {
            result.get(30, TimeUnit.SECONDS)
        } finally {
            result.cancel(true)
        }
    }
}
