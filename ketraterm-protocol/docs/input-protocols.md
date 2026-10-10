# Keyboard Input Protocol Vocabulary

Shared constants in `io.github.ketraterm.protocol.keyboard`. Parser recognizes
controls, core stores negotiated state, and input chooses reports. This guide
covers values and representations; the
[input contract](../../ketraterm-input/docs/terminal-input-contract.md#keyboard-contract)
owns encoding behavior.

## 1. xterm `modifyOtherKeys` Mode

`ModifyOtherKeysMode` defines the levels for ordinary keys:

| Constant | Value | Meaning |
| :--- | :--- | :--- |
| `DISABLED` | `0` | Use legacy ordinary-key encoding. |
| `MODE_1` | `1` | Encode modified keys with an ambiguous or missing legacy representation. |
| `MODE_2` | `2` | Encode modified ordinary and control-equivalent keys. |
| `MODE_3` | `3` | Encode ordinary keys even without modifiers. |

Explicit resource disable is represented separately as `-1`; it is not another
`ModifyOtherKeysMode` constant.

## 2. xterm `formatOtherKeys` Mode

`FormatOtherKeysMode` selects the report shape, independently of whether
`modifyOtherKeys` is enabled:

| Constant | Value | Report |
| :--- | :--- | :--- |
| `DEFAULT` | `0` | `CSI 27 ; modifier ; codepoint ~` |
| `CSI_U` | `1` | `CSI codepoint ; modifier u` |

An xterm CSI-u report does not imply Kitty keyboard negotiation or Kitty key
identities.

### Xterm Key Resources

Modifier and format controls share `XtermKeyResource` IDs:

| Constant | ID | Key family |
| :--- | :--- | :--- |
| `KEYBOARD` | `0` | Legacy/VT220 modifier admission mask. |
| `CURSOR_KEYS` | `1` | Cursor and editing keypad keys. |
| `FUNCTION_KEYS` | `2` | Numbered and miscellaneous function keys. |
| `KEYPAD_KEYS` | `3` | Numeric keypad. |
| `OTHER_KEYS` | `4` | Ordinary keys. |
| `MODIFIER_KEYS` | `6` | Physical modifier keys. |
| `SPECIAL_KEYS` | `7` | Remaining predefined keys. |

ID `5` is reserved for string actions and excluded by `isSupported`. Validation
helpers accept modifier values `-1..15` for ID `0`, `-1..3` for ID `4`, and
`-1..4` for other supported IDs. Format validation accepts only `0` or `1` for a
supported ID. A semantic validation helper accepting `-1` does not make it a
valid numeric parameter for every wire command.

See [xterm key resources](../../ketraterm-input/docs/terminal-input-contract.md#xterm-key-resources)
for defaults, set/reset/disable/query syntax, and per-family encoding rules.

## 3. Kitty Keyboard Progressive Enhancement Flags

`KittyKeyboardProgressiveFlag` uses these independent bits:

| Constant | Value | Meaning |
| :--- | :--- | :--- |
| `DISAMBIGUATE_ESCAPE_CODES` | `1` | Distinguish legacy escape-code collisions. |
| `REPORT_EVENT_TYPES` | `2` | Include press, repeat, or release metadata. |
| `REPORT_ALTERNATE_KEYS` | `4` | Include alternate key values for shortcut matching. |
| `REPORT_ALL_KEYS_AS_ESCAPE_CODES` | `8` | Report text-producing keys as escape sequences. |
| `REPORT_ASSOCIATED_TEXT` | `16` | Include associated text as a codepoint list. |

`ENCODER_SUPPORTED_MASK` is `31`; `DEFAULT_HOST_SUPPORTED_MASK` is `9` (bits `1`
and `8`). The encoder mask describes encoding capability, while the host mask
limits negotiated flags to metadata the integration can provide. A per-session
host mask must be a subset of the encoder mask and must reflect the adapter's
actual event information.

`KittyKeyboardFlagApplicationMode` defines the optional second parameter of
`CSI = flags ; mode u`: `REPLACE = 1`, `SET = 2`, and `CLEAR = 3`. These operation
values are separate from the flag bits.

## 4. Kitty Keyboard Event Types

`KittyKeyboardEventType` uses the event-type subparameter of the second CSI-u
field:

| Constant | Value |
| :--- | :--- |
| `PRESS` | `1` |
| `REPEAT` | `2` |
| `RELEASE` | `3` |

Press is the protocol default when that subparameter is absent. Admission of
repeat/release reports depends on the active flags and encoder event contract.

## 5. Kitty Keyboard Functional Key Codes

Printable keys use Unicode scalar values. Functional keys use protocol-defined
codes, including control-equivalent values and private-use values; they do not
start at a single generic offset.

`KittyKeyboardFunctionalKeyCode` includes:

| Constants | Values |
| :--- | :--- |
| `TAB`, `ENTER`, `ESCAPE`, `BACKSPACE` | `9`, `13`, `27`, `127` |
| `F13`, `F35` | `57376`, `57398` (contiguous range endpoints) |
| `CAPS_LOCK` | `57358` (first lock/system-key code) |
| `KP_0` through `KP_9` | `57399..57408` |
| `KP_DECIMAL` through `KP_SEPARATOR` | `57409..57416` |
| `KP_LEFT` | `57417` (first keypad-navigation code) |
| `KP_BEGIN` | `57427` |
| `MEDIA_PLAY` | `57428` (first media/volume code) |
| `LEFT_SHIFT` | `57441` (first physical modifier-key code) |

The constants establish wire identities. Input's normalized key vocabulary and
the host adapter determine which events can be supplied; do not infer adapter
coverage from the presence of a code.
