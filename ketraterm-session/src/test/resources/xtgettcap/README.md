# XTGETTCAP keyboard fixtures

`vim-queries.tsv` pins the active `key_names` list and request serialization
in Vim 9.2.1252, commit
[`90d3806e3986da398373a5720b57bf2df9d0b690`](https://github.com/vim/vim/blob/90d3806e3986da398373a5720b57bf2df9d0b690/src/term.c).
It contains all 37 enabled names in source order, including capabilities outside
KetraTerm's allowlist. Each request is exactly what `req_more_codes_from_term`
serializes; scheduling between Vim's batches is deliberately excluded.
The fixture was derived from source, not captured from a running Vim.

`keys.tsv` records independently reviewed expected legacy press bytes for the
supported terminfo/termcap aliases. Columns are the two names, normalized input
key, modifier, normal-mode hex bytes and application-cursor-mode hex bytes.
These values use the default cursor modifier level 2.
`shift-cursor-prefixes.tsv` records expected Shift cursor prefixes for levels
-1 through 3 in both cursor modes. Tests combine those fixed prefixes with each
key's final byte and compare the reply with actual encoder output. The four
Shift aliases are also exercised by the pinned Vim query stream.
`keypad-corners.tsv` pins the numeric keypad 7/9/1/3 identities and their
numeric/application bytes from the [DEC numeric keypad table](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h3-VT220-Style-Function-Keys).
The [terminfo definitions](https://invisible-island.net/ncurses/man/terminfo.5.html)
map `ka1/ka3/kc1/kc3` to `K1/K3/K4/K5`. Numeric bytes verify input encoding;
discovery fails in numeric mode to avoid advertising digits as function keys.
The Vim replay runs in both keypad modes, with replies allowed and denied.
F3 is SS3 R, not Kitty's CSI 13~. Backspace uses the host's default DEL here;
tests separately cover BS policy, explicit DECBKM and reset. Kotlin forwarding
wrappers retain their original query interception while forwarding host metadata.
Host tests cover query replacement and the JVM default-method fallback for
wrappers without the new hook.

Session tests replay the pinned requests byte by byte, check defined failures,
then compare each advertised value with actual input encoder output. They use
one injected coroutine test scheduler and no native terminal, external tools,
timeouts or wall-clock assertions. See the [reply contract](../../../../../docs/reference/protocol.md#xtgettcap)
for mode-dependent discovery.
