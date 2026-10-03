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
package io.github.ketraterm.ui.swing.host;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SwingDialogRequestJavaTest {
    @Test
    void javaPresenterCannotReorderDialogDecisions() {
        assertStableOptions(list -> Collections.swap(list, 0, 1));
    }

    @Test
    void javaPresenterCannotAddAnUnvalidatedDecision() {
        assertStableOptions(list -> list.add("extra"));
    }

    @Test
    void javaPresenterCannotRemoveADialogDecision() {
        assertStableOptions(list -> list.remove(0));
    }

    @Test
    void javaPresenterCannotClearDialogDecisions() {
        assertStableOptions(List::clear);
    }

    private static void assertStableOptions(Consumer<List<String>> mutation) {
        var expected = List.of("Allow", "Deny");
        var request = new SwingDialogRequest(
                "Clipboard", "Read the clipboard?", SwingDialogRequest.Severity.WARNING, expected, 1);

        assertThrows(UnsupportedOperationException.class,
                () -> mutation.accept(request.getOptions()));
        assertEquals(expected, request.getOptions());
        assertEquals(0, request.getOptions().indexOf("Allow"));
        assertEquals(1, request.getOptions().indexOf("Deny"));
        assertEquals(1, request.getDefaultOption());
    }
}
