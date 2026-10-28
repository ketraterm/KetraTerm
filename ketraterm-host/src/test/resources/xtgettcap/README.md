# XTGETTCAP expansion fixtures

`parameters.tsv` records Python `curses.tparm` output using ncurses
6.6.20251231. Columns are capability name, template bytes in hex, integer
parameter, and expanded bytes in hex. The templates contain actual ESC bytes.
Tests compare advertised templates with these fixtures and feed the independently
expanded bytes through the parser/host/core pipeline.

Cases cover every underline style, packed RGB component and 24-bit boundaries,
and scrolling within margins. Runtime code and CI tests never invoke Python or
ncurses and contain no terminfo evaluator.

To verify the expansion data, run this Python from the repository root in an
environment with ncurses and the standard `xterm-256color` terminfo entry:

```python
import curses
from pathlib import Path

curses.setupterm(term="xterm-256color")
path = Path("ketraterm-host/src/test/resources/xtgettcap/parameters.tsv")
for line in path.read_text().splitlines():
    if not line or line.startswith("#"):
        continue
    name, template, parameter, expanded = line.split("\t")
    actual = curses.tparm(bytes.fromhex(template), int(parameter)).hex()
    assert actual == expanded, (name, parameter, actual, expanded)
```

Consumer references: fish documents its `indn` query in
[terminal compatibility](https://fishshell.com/docs/4.9/terminal-compatibility.html);
Vim's `key_names` includes `PS` and `PE` in
[`src/term.c`](https://github.com/vim/vim/blob/master/src/term.c).
These references establish the discovery use cases; ncurses is the expansion oracle.
