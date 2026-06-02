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
package com.gagik.parser.ansi

/**
 * Internal command ids for routed CSI sequences.
 *
 * These ids are parser-dispatch vocabulary, not core API concepts.
 */
internal object CsiCommand {
    const val UNKNOWN: Int = 0

    const val CUU: Int = 1
    const val CUD: Int = 2
    const val CUF: Int = 3
    const val CUB: Int = 4
    const val CNL: Int = 5
    const val CPL: Int = 6
    const val CHA: Int = 7
    const val CUP: Int = 8
    const val VPA: Int = 9

    const val ED: Int = 10
    const val EL: Int = 11
    const val IL: Int = 12
    const val DL: Int = 13
    const val ICH: Int = 14
    const val DCH: Int = 15
    const val ECH: Int = 16
    const val SU: Int = 17
    const val SD: Int = 18

    const val SM_ANSI: Int = 19
    const val RM_ANSI: Int = 20
    const val SM_DEC: Int = 21
    const val RM_DEC: Int = 22

    const val DECSTR: Int = 23
    const val SGR: Int = 24
    const val CHT: Int = 25
    const val CBT: Int = 26
    const val TBC: Int = 27
    const val DECSTBM: Int = 28
    const val DECSLRM: Int = 29
    const val DECSED: Int = 30
    const val DECSEL: Int = 31
    const val DECSCA: Int = 32
    const val DA_PRIMARY: Int = 33
    const val DA_SECONDARY: Int = 34
    const val DA_TERTIARY: Int = 35
    const val DSR: Int = 36
    const val DSR_DEC: Int = 37
    const val WINDOW_OP: Int = 38
    const val DECSCUSR: Int = 39
    const val XTFMTKEYS: Int = 40
    const val XTMODKEYS: Int = 41
}
