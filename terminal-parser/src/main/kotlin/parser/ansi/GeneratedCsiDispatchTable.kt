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
 * CSI signature routing table.
 *
 * Production rule:
 * - This shape is generator-compatible.
 * - Arrays are primitive and already sorted.
 * - Do not sort one array independently.
 * - Do not allocate Pair/List registry objects at runtime.
 *
 * The init block is only a cheap integrity guard.
 */
internal object GeneratedCsiDispatchTable {
    private val SIGNATURES: LongArray =
        longArrayOf(
            64L, // CSI @  ICH
            65L, // CSI A  CUU
            66L, // CSI B  CUD
            67L, // CSI C  CUF
            68L, // CSI D  CUB
            69L, // CSI E  CNL
            70L, // CSI F  CPL
            71L, // CSI G  CHA
            72L, // CSI H  CUP
            73L, // CSI I  CHT
            74L, // CSI J  ED
            75L, // CSI K  EL
            76L, // CSI L  IL
            77L, // CSI M  DL
            80L, // CSI P  DCH
            83L, // CSI S  SU
            84L, // CSI T  SD
            88L, // CSI X  ECH
            90L, // CSI Z  CBT
            99L, // CSI c  DA
            100L, // CSI d  VPA
            102L, // CSI f  HVP -> CUP
            103L, // CSI g  TBC
            104L, // CSI h  SM ANSI
            108L, // CSI l  RM ANSI
            109L, // CSI m  SGR
            110L, // CSI n  DSR
            114L, // CSI r  DECSTBM
            115L, // CSI s  DECSLRM
            116L, // CSI t  xterm window/title operation
            15715L, // CSI = c  DA3
            15971L, // CSI > c  DA2
            15974L, // CSI > f  XTFMTKEYS
            15981L, // CSI > m  XTMODKEYS
            16202L, // CSI ? J  DECSED
            16203L, // CSI ? K  DECSEL
            16232L, // CSI ? h  DECSET
            16236L, // CSI ? l  DECRST
            16238L, // CSI ? n  DEC DSR
            281474978807921L, // CSI SP q  DECSCUSR
            281474978873456L, // CSI ! p  DECSTR
            281474978938993L, // CSI " q  DECSCA
        )

    private val COMMANDS: IntArray =
        intArrayOf(
            CsiCommand.ICH,
            CsiCommand.CUU,
            CsiCommand.CUD,
            CsiCommand.CUF,
            CsiCommand.CUB,
            CsiCommand.CNL,
            CsiCommand.CPL,
            CsiCommand.CHA,
            CsiCommand.CUP,
            CsiCommand.CHT,
            CsiCommand.ED,
            CsiCommand.EL,
            CsiCommand.IL,
            CsiCommand.DL,
            CsiCommand.DCH,
            CsiCommand.SU,
            CsiCommand.SD,
            CsiCommand.ECH,
            CsiCommand.CBT,
            CsiCommand.DA_PRIMARY,
            CsiCommand.VPA,
            CsiCommand.CUP,
            CsiCommand.TBC,
            CsiCommand.SM_ANSI,
            CsiCommand.RM_ANSI,
            CsiCommand.SGR,
            CsiCommand.DSR,
            CsiCommand.DECSTBM,
            CsiCommand.DECSLRM,
            CsiCommand.WINDOW_OP,
            CsiCommand.DA_TERTIARY,
            CsiCommand.DA_SECONDARY,
            CsiCommand.XTFMTKEYS,
            CsiCommand.XTMODKEYS,
            CsiCommand.DECSED,
            CsiCommand.DECSEL,
            CsiCommand.SM_DEC,
            CsiCommand.RM_DEC,
            CsiCommand.DSR_DEC,
            CsiCommand.DECSCUSR,
            CsiCommand.DECSTR,
            CsiCommand.DECSCA,
        )

    init {
        check(SIGNATURES.size == COMMANDS.size) {
            "CSI signature and command tables must have equal length"
        }

        var i = 1
        while (i < SIGNATURES.size) {
            check(SIGNATURES[i - 1] < SIGNATURES[i]) {
                "CSI signatures must be strictly sorted at index $i"
            }
            i++
        }
    }

    @JvmStatic
    fun lookup(signature: Long): Int {
        var low = 0
        var high = SIGNATURES.size - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = SIGNATURES[mid]
            if (value < signature) {
                low = mid + 1
            } else if (value > signature) {
                high = mid - 1
            } else {
                return COMMANDS[mid]
            }
        }

        return CsiCommand.UNKNOWN
    }
}
