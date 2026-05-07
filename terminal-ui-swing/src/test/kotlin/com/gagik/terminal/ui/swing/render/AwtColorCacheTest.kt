package com.gagik.terminal.ui.swing.render

import kotlin.test.Test
import kotlin.test.assertSame

class AwtColorCacheTest {
    @Test
    fun reusesColorInstancesForRepeatedArgbValues() {
        val cache = AwtColorCache()

        assertSame(
            cache.color(0xFF010203.toInt()),
            cache.color(0xFF010203.toInt()),
        )
    }
}
