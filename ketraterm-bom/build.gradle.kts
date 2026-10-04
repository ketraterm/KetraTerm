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

plugins {
    `java-platform`
}

@Suppress("UNCHECKED_CAST")
val publishedDependencyNames = rootProject.extra["publishedDependencyNames"] as Set<String>

@Suppress("UNCHECKED_CAST")
val publishedEntryPointNames = rootProject.extra["publishedEntryPointNames"] as Set<String>

dependencies {
    constraints {
        publishedDependencyNames.forEach { api(project(":$it")) }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        val entryPointNames = publishedEntryPointNames
        pom.withXml {
            // Maven dependency-management keys include the type; bundles have POM packaging.
            val management = (asNode().get("dependencyManagement") as groovy.util.NodeList).single() as groovy.util.Node
            val dependencies = (management.get("dependencies") as groovy.util.NodeList).single() as groovy.util.Node
            dependencies.children().filterIsInstance<groovy.util.Node>().forEach { dependency ->
                val artifact = (dependency.get("artifactId") as groovy.util.NodeList).text()
                if (artifact in entryPointNames) dependency.appendNode("type", "pom")
            }
        }
    }
}
