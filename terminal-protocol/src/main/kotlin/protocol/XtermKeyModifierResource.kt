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
package com.gagik.terminal.protocol

/**
 * Xterm XTMODKEYS resource ids used by `CSI > Pp ; Pv m`.
 *
 * Values mirror xterm's control-sequences table for "Set/reset key modifier
 * options (XTMODKEYS)":
 * <https://invisible-island.net/xterm/ctlseqs/ctlseqs.html>.
 * In that table, `Pp = 4` selects modifyOtherKeys.
 */
object XtermKeyModifierResource {
    /** modifyOtherKeys resource id. */
    const val MODIFY_OTHER_KEYS: Int = 4
}
