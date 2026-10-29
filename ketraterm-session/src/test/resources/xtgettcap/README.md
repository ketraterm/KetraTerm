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
