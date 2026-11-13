# PNGSuite decoder fixtures

The 24 `basn*.png` and `ibasn*.png` files are copied unchanged from
[libpng v1.6.50](https://github.com/pnggroup/libpng/tree/v1.6.50/contrib/pngsuite).
They cover all basic PNG sample formats and the available interlaced variants.
`pixels.tsv` pins each file's SHA-256 and the SHA-256 of row-major, big-endian
unassociated ARGB pixels.

Pixel hashes were independently recorded with the JetBrains JDK 25 PNG reader,
using native raster samples: grayscale channels are replicated, sub-byte samples
are scaled to 8 bits, 16-bit samples use their high byte, and palette entries use
the PNG palette. Color conversion and gamma correction are intentionally absent.
The production decoder was not used to produce these expected values. Tests need
no network or ImageIO to consume them.

The upstream [README](https://github.com/pnggroup/libpng/blob/v1.6.50/contrib/pngsuite/README)
grants redistribution:

> Copyright (c) Willem van Schaik, 1999, 2011, 2012
>
> Permission to use, copy, modify, and distribute these images for any
> purpose and without fee is hereby granted.
