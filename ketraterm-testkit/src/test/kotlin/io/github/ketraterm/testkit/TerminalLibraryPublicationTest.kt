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
package io.github.ketraterm.testkit

import com.fasterxml.jackson.databind.json.JsonMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Tag("publication-verification")
class TerminalLibraryPublicationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `published repository contains exactly the supported libraries and constraint-only BOM`() {
        val libraries = System.getProperty("publication.libraries").split(',').toSet()
        val entryPoints = System.getProperty("publication.entryPoints").split(',').toSet()
        val dependencies = libraries + entryPoints
        val version = System.getProperty("publication.version")
        val signed = System.getProperty("publication.signed").toBoolean()
        val repository = Path.of(System.getProperty("publication.repository"), "io", "github", "ketraterm")
        assertEquals(
            dependencies + "ketraterm-bom",
            repository
                .listDirectoryEntries()
                .filter { it.isDirectory() }
                .map { it.name }
                .toSet(),
        )
        for (name in dependencies + "ketraterm-bom") {
            val directory = repository.resolve(name).resolve(version)
            val files = directory.listDirectoryEntries()
            val pomFile = files.single { it.name.endsWith(".pom") }
            val xml =
                DocumentBuilderFactory
                    .newInstance()
                    .apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                    }.newDocumentBuilder()
                    .parse(pomFile.toFile())
            val xpath =
                javax.xml.xpath.XPathFactory
                    .newInstance()
                    .newXPath()

            fun text(expression: String): String = xpath.evaluate(expression, xml)
            assertEquals("io.github.ketraterm", text("/project/groupId"), name)
            assertEquals(name, text("/project/artifactId"))
            assertEquals(version, text("/project/version"))
            for (field in listOf("name", "description", "url", "licenses/license/name", "developers/developer/name", "scm/connection")) {
                assertTrue(text("/project/$field").isNotBlank(), "$name has no $field")
            }
            val moduleFile = files.single { it.name.endsWith(".module") }
            val module = JsonMapper.builder().build().readTree(moduleFile.toFile())
            assertEquals("io.github.ketraterm", module.path("component").path("group").asText())
            assertEquals(name, module.path("component").path("module").asText())
            assertEquals(version, module.path("component").path("version").asText())
            val variants = module.path("variants")
            for (variant in variants) {
                for (publishedFile in variant.path("files")) {
                    val logicalName = publishedFile.path("name").asText()
                    val classifier = logicalName.removePrefix("$name-$version")
                    val artifact =
                        files.single {
                            it.name.endsWith(classifier) &&
                                (classifier != ".jar" || !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar"))
                        }
                    assertEquals(publishedFile.path("size").asLong(), artifact.fileSize(), logicalName)
                    assertEquals(publishedFile.path("sha256").asText(), digest(artifact, "SHA-256"), logicalName)
                    assertEquals(publishedFile.path("sha512").asText(), digest(artifact, "SHA-512"), logicalName)
                }
                for (dependency in variant.path("dependencies")) {
                    if (dependency.path("group").asText() == "io.github.ketraterm") {
                        assertTrue(dependency.path("module").asText() in libraries, "$name references a product-only module")
                        assertEquals(version, dependency.path("version").path("requires").asText())
                    }
                }
            }
            for (dependencyIndex in 1..text("count(/project/dependencies/dependency[groupId='io.github.ketraterm'])").toInt()) {
                val path = "/project/dependencies/dependency[groupId='io.github.ketraterm'][$dependencyIndex]"
                assertTrue(text("$path/artifactId") in libraries, "$name POM references a product-only module")
                assertEquals(version, text("$path/version"))
            }
            if (name == "ketraterm-bom") {
                assertEquals("pom", text("/project/packaging"))
                assertEquals("0", text("count(/project/dependencies/dependency)"))
                assertEquals(dependencies.size.toString(), text("count(/project/dependencyManagement/dependencies/dependency)"))
                for (library in dependencies) {
                    assertEquals(version, text("/project/dependencyManagement/dependencies/dependency[artifactId='$library']/version"))
                    if (library in
                        entryPoints
                    ) {
                        assertEquals("pom", text("/project/dependencyManagement/dependencies/dependency[artifactId='$library']/type"))
                    }
                }
                assertTrue(files.none { it.name.endsWith(".jar") })
                for (variant in variants) {
                    assertTrue(variant.path("dependencies").isMissingNode)
                    assertEquals(dependencies, variant.path("dependencyConstraints").map { it.path("module").asText() }.toSet())
                    for (constraint in variant.path("dependencyConstraints")) {
                        assertEquals(version, constraint.path("version").path("requires").asText())
                        assertTrue(constraint.path("version").path("strictly").isMissingNode)
                    }
                }
            } else if (name in entryPoints) {
                assertEquals("pom", text("/project/packaging"))
                assertTrue(files.none { it.name.endsWith(".jar") }, "Dependency entry points must not publish empty jars")
                val expected = if (name == "ketraterm-headless") "ketraterm-session" else "ketraterm-ui-swing"
                assertEquals("1", text("count(/project/dependencies/dependency)"))
                assertEquals(expected, text("/project/dependencies/dependency/artifactId"))
                for (variant in variants) {
                    assertEquals("library", variant.path("attributes").path("org.gradle.category").asText())
                    assertTrue(variant.path("files").isMissingNode)
                    assertEquals(listOf(expected), variant.path("dependencies").map { it.path("module").asText() })
                }
            } else {
                verifyJar(files.single { it.name.endsWith("-sources.jar") }, ".kt")
                verifyJar(files.single { it.name.endsWith("-javadoc.jar") }, ".html")
                verifyJar(
                    files.single { it.name.endsWith(".jar") && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar") },
                    ".class",
                )
            }
            val artifacts = files.filter { it.name.endsWith(".jar") || it.name.endsWith(".pom") || it.name.endsWith(".module") }
            for (artifact in artifacts) {
                verifyChecksum(artifact, "SHA-1", ".sha1")
                verifyChecksum(artifact, "MD5", ".md5")
                verifyChecksum(artifact, "SHA-256", ".sha256")
                verifyChecksum(artifact, "SHA-512", ".sha512")
                if (signed) assertTrue(artifact.resolveSibling("${artifact.name}.asc").exists(), "Missing signature for $artifact")
            }
        }
    }

    @Test
    fun `empty or incorrectly classified jars are rejected`() {
        val jar = temporaryDirectory.resolve("artifact.jar")
        ZipOutputStream(jar.toFile().outputStream()).use {
            it.putNextEntry(ZipEntry("placeholder.txt"))
            it.write("placeholder".toByteArray())
            it.closeEntry()
        }
        for (suffix in listOf(".kt", ".html", ".class")) {
            assertFailsWith<AssertionError> { verifyJar(jar, suffix) }
        }
    }

    @Test
    fun `tampered artifact checksum is rejected`() {
        val artifact = temporaryDirectory.resolve("artifact.pom")
        artifact.writeText("original")
        artifact.resolveSibling("artifact.pom.sha1").writeText(digest(artifact, "SHA-1"))
        verifyChecksum(artifact, "SHA-1", ".sha1")
        artifact.writeText("modified")
        assertFailsWith<AssertionError> { verifyChecksum(artifact, "SHA-1", ".sha1") }
    }

    private fun verifyJar(
        file: Path,
        suffix: String,
    ) {
        ZipFile(file.toFile()).use { jar ->
            assertTrue(
                jar.entries().asSequence().any { !it.isDirectory && it.name.endsWith(suffix) && it.size > 0 },
                "$file has no $suffix content",
            )
        }
    }

    private fun verifyChecksum(
        file: Path,
        algorithm: String,
        suffix: String,
    ) {
        assertEquals(
            file.resolveSibling("${file.name}$suffix").readText().trim(),
            digest(file, algorithm),
            "Invalid $algorithm checksum for $file",
        )
    }

    private fun digest(
        file: Path,
        algorithm: String,
    ): String = MessageDigest.getInstance(algorithm).digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
