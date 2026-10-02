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
package io.github.ketraterm.ui.swing.suggestion;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SwingShellSuggestionViewJavaTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void retainedViewportRejectsJavaMutation(
            String operation, Consumer<List<SwingShellSuggestion>> mutation) {
        var first = suggestion("first");
        var second = suggestion("second");
        var expected = List.of(first, second);
        var snapshot = SwingShellSuggestionViewSnapshot.create(expected, 1, 3, 5);

        assertThrows(UnsupportedOperationException.class,
                () -> mutation.accept(snapshot.getVisibleSuggestions()));
        assertEquals(expected, snapshot.getVisibleSuggestions());
        assertSame(second, snapshot.getSelectedSuggestion());
        assertEquals(4, snapshot.getAbsoluteSelectedIndex());
        assertFalse(snapshot.getHasSuggestionsAfter());
    }

    private static Stream<Arguments> mutations() {
        return Stream.of(
                Arguments.of("replace", (Consumer<List<SwingShellSuggestion>>) list -> list.set(1, suggestion("changed"))),
                Arguments.of("append", (Consumer<List<SwingShellSuggestion>>) list -> list.add(suggestion("extra"))),
                Arguments.of("remove", (Consumer<List<SwingShellSuggestion>>) list -> list.remove(0)),
                Arguments.of("clear", (Consumer<List<SwingShellSuggestion>>) List::clear));
    }

    private static SwingShellSuggestion suggestion(String text) {
        return new SwingShellSuggestion(text, 0, 0, "spec", "COMMAND");
    }
}
