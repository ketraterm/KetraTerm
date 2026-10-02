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
package io.github.ketraterm.testkit;

import kotlin.KotlinVersion;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/** Isolated process entry point; the consumer itself remains the retained baseline bytecode. */
public final class CompiledClientLauncher {
    static void main(String[] arguments) throws Throwable {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected Kotlin runtime version");
        var expectedVersion = arguments[0];
        if (!KotlinVersion.CURRENT.toString().equals(expectedVersion)) {
            throw new AssertionError("Loaded Kotlin " + KotlinVersion.CURRENT + ", expected " + expectedVersion);
        }
        var origin = Path.of(KotlinVersion.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!origin.getFileName().toString().equals("kotlin-stdlib-" + expectedVersion + ".jar")) {
            throw new AssertionError("Kotlin runtime was shadowed by " + origin);
        }
        try {
            Class.forName("consumer.KotlinConsumerKt").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
