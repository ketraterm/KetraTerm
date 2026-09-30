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

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.ketraterm.ui.swing.api.SwingHyperlinkAction
import io.github.ketraterm.ui.swing.api.SwingHyperlinkDetectionRequest
import io.github.ketraterm.ui.swing.api.SwingHyperlinkDetectionSink
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Records real SDK detector work separately from Swing projection and painting.
 *
 * Every HYPERLINK_BASELINE record is JSON in Gradle's test-result stdout. Times
 * describe this platform fixture, not an interactive IDE, and are never pass/fail
 * thresholds. Inputs use reserved web domains and a real temporary project file;
 * no navigation action is invoked.
 */
class IntellijTerminalHyperlinkBaselineTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    fun testRecordsRealDetectorWorkAtSeveralLinkDensities() {
        val file =
            myFixture
                .addFileToProject(
                    "src/baseline/HyperlinkBaseline.java",
                    "package baseline;\npublic class HyperlinkBaseline {\n  public static void run() {}\n}\n",
                ).virtualFile
        val fileUri = Path.of(file.path).toUri().toASCIIString()
        val providers = ConsoleFilterProvider.FILTER_PROVIDERS.extensionList
        assertTrue("No IntelliJ console filter providers were loaded", providers.isNotEmpty())
        val probe = CountingProvider()
        // Observe entry to provider filtering without replacing any real SDK filter.
        ExtensionTestUtil.maskExtensions(
            ConsoleFilterProvider.FILTER_PROVIDERS,
            listOf(probe) + providers,
            testRootDisposable,
        )
        println(
            "HYPERLINK_BASELINE {\"kind\":\"environment\",\"sdk\":\"${ApplicationInfo.getInstance().build.asString()}\"," +
                "\"providerClasses\":[${providers.joinToString(",") { "\"${it.javaClass.name}\"" }}]," +
                "\"warmupRequests\":$WARMUP_REQUESTS,\"measuredRequests\":$MEASURED_REQUESTS}",
        )

        val detector = IntellijTerminalHyperlinkDetector(project)
        val completed =
            AppExecutorUtil.getAppExecutorService().submit(
                Callable {
                    for (lineCount in intArrayOf(32, 128, 512)) {
                        for (scenario in Scenario.entries) {
                            val lines = scenario.lines(lineCount, file.path, fileUri)
                            val request = detectionRequest(lines)
                            repeat(WARMUP_REQUESTS) {
                                detector.detect(request, CountingSink(lines))
                            }

                            val creationStart = probe.creations
                            val applicationStart = probe.lineApplications
                            val nanos = LongArray(MEASURED_REQUESTS)
                            val resultCounts = IntArray(MEASURED_REQUESTS)
                            val webCounts = IntArray(MEASURED_REQUESTS)
                            val fileUriCounts = IntArray(MEASURED_REQUESTS)
                            for (sample in nanos.indices) {
                                val sink = CountingSink(lines)
                                val start = System.nanoTime()
                                detector.detect(request, sink)
                                nanos[sample] = System.nanoTime() - start
                                resultCounts[sample] = sink.results
                                webCounts[sample] = sink.webUris
                                fileUriCounts[sample] = sink.fileUris
                                assertEquals("$scenario returned invalid ranges", 0, sink.invalidRanges)
                                assertEquals("$scenario lost web URLs", scenario.webUriCount(lineCount), sink.webUris)
                                assertEquals("$scenario lost file URIs", scenario.fileUriCount(lineCount), sink.fileUris)
                            }
                            println(
                                "HYPERLINK_BASELINE {\"kind\":\"detector\",\"scenario\":\"${scenario.name}\"," +
                                    "\"logicalLines\":$lineCount,\"inputChars\":${lines.sumOf(String::length)}," +
                                    "\"requestNanos\":${nanos.joinToString(prefix = "[", postfix = "]")}," +
                                    "\"results\":${resultCounts.joinToString(prefix = "[", postfix = "]")}," +
                                    "\"webUris\":${webCounts.joinToString(prefix = "[", postfix = "]")}," +
                                    "\"fileUris\":${fileUriCounts.joinToString(prefix = "[", postfix = "]")}," +
                                    "\"probeProviderCreations\":${probe.creations - creationStart}," +
                                    "\"probeLineApplications\":${probe.lineApplications - applicationStart}}",
                            )
                        }
                    }
                },
            )
        try {
            completed.get(60, TimeUnit.SECONDS)
        } finally {
            completed.cancel(true)
        }
    }

    fun testIndexRequiredProviderIsAvailableAgainOnTheNextSmartRequest() {
        val file = myFixture.addFileToProject("indexing/Source.txt", "baseline\n").virtualFile
        val token = "ketraterm-baseline-indexed-file"
        var applications = 0
        val provider =
            ConsoleFilterProvider { contextProject ->
                arrayOf(
                    Filter { line, entireLength ->
                        applications++
                        val start = line.indexOf(token)
                        if (start < 0) {
                            null
                        } else {
                            val offset = entireLength - line.length + start
                            Filter.Result(offset, offset + token.length, OpenFileHyperlinkInfo(contextProject, file, 0))
                        }
                    },
                )
            }
        ExtensionTestUtil.maskExtensions(
            ConsoleFilterProvider.FILTER_PROVIDERS,
            listOf(provider) + ConsoleFilterProvider.FILTER_PROVIDERS.extensionList,
            testRootDisposable,
        )
        val lines = arrayOf("$token\n", "https://example.invalid/indexing\n")
        val request = detectionRequest(lines)
        val detector = IntellijTerminalHyperlinkDetector(project)
        val observedCounts = ArrayList<Int>()

        fun detect(): CountingSink {
            val completed =
                AppExecutorUtil.getAppExecutorService().submit(
                    Callable { CountingSink(lines).also { detector.detect(request, it) } },
                )
            val sink =
                try {
                    completed.get(30, TimeUnit.SECONDS)
                } finally {
                    completed.cancel(true)
                }
            assertEquals(0, sink.invalidRanges)
            assertEquals(1, sink.webUris)
            observedCounts += sink.results
            return sink
        }

        assertFalse(DumbService.getInstance(project).isDumb)
        assertEquals(2, detect().results)
        val beforeDumb = applications
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(DumbService.getInstance(project).isDumb)
            assertEquals(1, detect().results)
            assertEquals("Index-requiring provider ran during indexing", beforeDumb, applications)
        }
        assertFalse(DumbService.getInstance(project).isDumb)
        assertEquals(2, detect().results)
        assertTrue("Index-requiring provider did not resume", applications > beforeDumb)
        println(
            "HYPERLINK_BASELINE {\"kind\":\"indexing\",\"states\":[\"smart\",\"dumb\",\"smart\"]," +
                "\"results\":${observedCounts.joinToString(prefix = "[", postfix = "]")}," +
                "\"resubmission\":\"explicit detector call; no Swing invalidation exercised\"}",
        )
    }

    /** Builds the cross-module immutable request without widening a production-internal constructor. */
    private fun detectionRequest(lines: Array<String>): SwingHyperlinkDetectionRequest {
        var offset = 0
        val starts = IntArray(lines.size)
        val ends = IntArray(lines.size)
        for (index in lines.indices) {
            starts[index] = offset
            offset += lines[index].length
            ends[index] = offset
        }
        return SwingHyperlinkDetectionRequest::class.java
            .getDeclaredConstructor(Array<String>::class.java, IntArray::class.java, IntArray::class.java)
            .newInstance(lines, starts, ends)
    }

    private class CountingProvider : ConsoleFilterProvider {
        var creations = 0
        var lineApplications = 0

        override fun getDefaultFilters(project: Project): Array<Filter> {
            creations++
            return arrayOf(
                object : Filter, DumbAware {
                    override fun applyFilter(
                        line: String,
                        entireLength: Int,
                    ): Filter.Result? {
                        lineApplications++
                        return null
                    }
                },
            )
        }
    }

    private class CountingSink(
        private val lines: Array<String>,
    ) : SwingHyperlinkDetectionSink {
        var results = 0
        var webUris = 0
        var fileUris = 0
        var invalidRanges = 0

        override fun addHyperlink(
            lineIndex: Int,
            startOffset: Int,
            endOffset: Int,
            action: SwingHyperlinkAction,
            validationStartOffset: Int,
            validationEndOffset: Int,
        ) {
            val line = lines.getOrNull(lineIndex)
            if (line == null || startOffset < 0 || endOffset <= startOffset || endOffset > line.length) {
                invalidRanges++
                return
            }
            results++
            if (line.startsWith("https://", startOffset)) webUris++
            if (line.startsWith("file:", startOffset)) fileUris++
        }
    }

    private enum class Scenario {
        URL_DENSE,
        MIXED_DENSE,
        FILE_REFERENCES,
        ;

        fun lines(
            count: Int,
            filePath: String,
            fileUri: String,
        ): Array<String> =
            Array(count) { row ->
                when (this) {
                    URL_DENSE -> (0 until 8).joinToString(" ") { "https://example.invalid/$row/$it?q=baseline" } + "\n"
                    MIXED_DENSE ->
                        if (row % 2 == 0) {
                            "https://example.invalid/$row $fileUri:3:1 $filePath:3:1\n"
                        } else {
                            "\tat baseline.HyperlinkBaseline.run(HyperlinkBaseline.java:3)\n"
                        }
                    FILE_REFERENCES ->
                        when (row % 3) {
                            0 -> "$fileUri:3:1\n"
                            1 -> "\tat baseline.HyperlinkBaseline.run(HyperlinkBaseline.java:3)\n"
                            else -> "$filePath:3:1\n"
                        }
                }
            }

        fun webUriCount(lines: Int): Int =
            when (this) {
                URL_DENSE -> lines * 8
                MIXED_DENSE -> (lines + 1) / 2
                FILE_REFERENCES -> 0
            }

        fun fileUriCount(lines: Int): Int =
            when (this) {
                URL_DENSE -> 0
                MIXED_DENSE -> (lines + 1) / 2
                FILE_REFERENCES -> (lines + 2) / 3
            }
    }

    private companion object {
        const val WARMUP_REQUESTS = 2
        const val MEASURED_REQUESTS = 5
    }
}
