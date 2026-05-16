package com.gagik.terminal.ui.swing.render.cache

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.util.*

/**
 * Bounded renderer-local cache for shaped complex text layouts.
 *
 * Single code points and grapheme clusters use primitive-key LRUs so repeated
 * Unicode cells do not allocate lookup keys or transient strings on cache hits.
 *
 * **Thread Safety:** Not thread-safe. This cache must only be accessed
 * from the Swing Event Dispatch Thread (EDT).
*/
internal class TerminalComplexTextLayoutCache(
    codePointCapacity: Int = DEFAULT_CODE_POINT_CAPACITY,
    clusterCapacityPerStyle: Int = DEFAULT_CLUSTER_CAPACITY_PER_STYLE,
) {
    init {
        require(codePointCapacity > 0) {
            "codePointCapacity must be > 0, was $codePointCapacity"
        }
        require(clusterCapacityPerStyle > 0) {
            "clusterCapacityPerStyle must be > 0, was $clusterCapacityPerStyle"
        }
    }

    private val codePointLayouts = LongTextLayoutLru(codePointCapacity)
    private val clusterLayouts = Array(STYLE_COUNT) {
        ClusterTextLayoutLru(clusterCapacityPerStyle)
    }
    private var stringClusterScratch = IntArray(MAX_CLUSTER_LENGTH)
    private var fontRenderContext: FontRenderContext? = null
    private var fontGeneration: Int = -1

    /**
     * Clears cached layouts.
     */
    fun clear() {
        fontRenderContext = null
        codePointLayouts.clear()
        for (cache in clusterLayouts) {
            cache.clear()
        }
    }

    /**
     * Resolves a shaped text layout for a single Unicode code point using an allocation-free primitive key.
     *
     * Avoids heap allocation by converting the 32-bit integer code point and its corresponding
     * style mask into a single packed 64-bit primitive key. This layout bypasses standard string
     * instantiation during cache lookup passes.
     *
     * @param codePoint The 32-bit Unicode scalar value to render.
     * @param style The packed integer bitmask specifying the target font style.
     * @param fontRenderContext The active Java2D graphics context for metrics tracking.
     * @param fontCache The stateful font resolver for fallback execution.
     * @return An immutable, single-character [TextLayout].
     */
    fun codePointLayout(
        codePoint: Int,
        style: Int,
        fontRenderContext: FontRenderContext,
        fontCache: TerminalFontCache,
    ): TextLayout {
        fontCache.refreshSystemFallbackFonts()
        prepare(fontRenderContext, fontCache.generation)

        val normalizedStyle = style and STYLE_MASK
        val key = codePointKey(codePoint, normalizedStyle)
        val slot = codePointLayouts.findSlot(key)
        val cached = codePointLayouts.layoutAtSlot(slot)
        if (cached != null) return cached

        // Convert the code point directly to a transient char array on cache misses to minimize
        // object lifecycle footprints prior to layout shaping.
        val text = String(Character.toChars(codePoint))
        val layout = TextLayout(text, fontCache.fontForCodePoint(codePoint, normalizedStyle), fontRenderContext)
        codePointLayouts.putAtMissSlot(key, layout, slot)
        return layout
    }

    /**
     * Resolves a shaped text layout for a multi-code-unit grapheme cluster, enforcing size limits
     * to insulate the rendering pipeline from resource exhaustion.
     *
     * This method handles the complex text shaping pipeline. It applies a defensive length filter
     * to neutralize adversarial input sequences (e.g., pathological Zero-Width Joiner loops)
     * *prior* to cache interrogation. This design choice collapses unbounded variants into a
     * uniform tracking token, guaranteeing an upper bound on memory consumption and preventing
     * cache thrashing.
     *
     * @param style The packed integer bitmask specifying the target font style (Bold/Italic variants).
     * @param fontRenderContext The active Java2D graphics context specifying scaling and anti-aliasing configurations.
     * @param fontCache The stateful primary and fallback font resolver for typography resolution.
     * @return An immutable, shaped [TextLayout] strictly bound to terminal cell advances.
     */
    fun clusterLayout(
        codepoints: IntArray,
        offset: Int,
        length: Int,
        style: Int,
        fontRenderContext: FontRenderContext,
        fontCache: TerminalFontCache,
    ): TextLayout {
        require(length >= 0) { "length must be >= 0, was $length" }
        require(offset >= 0 && codepoints.size - offset >= length) {
            "cluster source has insufficient capacity: size=${codepoints.size}, offset=$offset, length=$length"
        }
        if (length == 0) {
            return codePointLayout(REPLACEMENT_CODE_POINT, style, fontRenderContext, fontCache)
        }
        if (length > MAX_CLUSTER_LENGTH) {
            return codePointLayout(REPLACEMENT_CODE_POINT, style, fontRenderContext, fontCache)
        }
        if (length == 1) {
            return codePointLayout(codepoints[offset], style, fontRenderContext, fontCache)
        }

        fontCache.refreshSystemFallbackFonts()
        prepare(fontRenderContext, fontCache.generation)

        val normalizedStyle = style and STYLE_MASK
        val styleLayouts = clusterLayouts[normalizedStyle]

        val hash = styleLayouts.contentHash(codepoints, offset, length)
        val slot = styleLayouts.findSlot(codepoints, offset, length, hash)
        val cached = styleLayouts.layoutAtSlot(slot)
        if (cached != null) return cached

        // TextLayout and Font.canDisplayUpTo require text objects. Construct
        // them only on cache misses; repeated paint passes compare primitive
        // codepoint slices directly.
        val text = String(codepoints, offset, length)
        val layout = TextLayout(text, fontCache.fontForText(text, normalizedStyle), fontRenderContext)
        styleLayouts.putAtMissSlot(codepoints, offset, length, hash, layout, slot)
        return layout
    }

    /**
     * Resolves a shaped layout from a string source.
     *
     * This overload exists for tests and compatibility helpers. The renderer's
     * hot path should pass primitive render-cache slices to the main overload.
     */
    fun clusterLayout(
        text: String,
        style: Int,
        fontRenderContext: FontRenderContext,
        fontCache: TerminalFontCache,
    ): TextLayout {
        val length = copyStringCodepoints(text)
        return clusterLayout(stringClusterScratch, 0, length, style, fontRenderContext, fontCache)
    }

    private fun prepare(nextFontRenderContext: FontRenderContext, nextFontGeneration: Int) {
        if (nextFontRenderContext == fontRenderContext && nextFontGeneration == fontGeneration) return

        fontRenderContext = nextFontRenderContext
        fontGeneration = nextFontGeneration
        codePointLayouts.clear()
        for (cache in clusterLayouts) {
            cache.clear()
        }
    }

    private fun copyStringCodepoints(text: String): Int {
        var required = 0
        var charIndex = 0
        while (charIndex < text.length && required <= MAX_CLUSTER_LENGTH) {
            val codePoint = Character.codePointAt(text, charIndex)
            if (required == stringClusterScratch.size) {
                stringClusterScratch = stringClusterScratch.copyOf(stringClusterScratch.size * 2)
            }
            stringClusterScratch[required] = codePoint
            required++
            charIndex += Character.charCount(codePoint)
        }
        return required
    }

    /**
     * ARCHITECTURAL WARNING: MANUAL MONOMORPHIZATION
     * * Do not attempt to DRY (Don't Repeat Yourself) this cache logic using
     * generic base classes (e.g., `<T>`).
     * * This terminal emulator relies on a strict zero-allocation render loop.
     * Because the JVM uses Type Erasure, passing primitives (Int, Long) to a
     * generic type parameter forces boxing (allocating `java.lang.Integer` on the heap).
     * * To maintain 60FPS without Garbage Collection stutter, this hash map logic
     * is manually duplicated to operate directly on contiguous primitive arrays
     * (`IntArray`, `LongArray`). Suppress IDE duplication warnings and leave
     * this math alone.
     */
    @Suppress("DuplicatedCode")
    private class LongTextLayoutLru(capacity: Int) {
        private val entryKeys = LongArray(capacity)
        private val entryLayouts = arrayOfNulls<TextLayout>(capacity)
        private val previous = IntArray(capacity) { EMPTY }
        private val next = IntArray(capacity) { EMPTY }
        private val hashKeys = LongArray(hashCapacity(capacity))
        private val hashEntries = IntArray(hashKeys.size) { EMPTY }
        private val hashMask = hashKeys.size - 1
        private var size = 0
        private var head = EMPTY
        private var tail = EMPTY

        fun findSlot(key: Long): Int {
            var slot = hashSlot(key)
            while (true) {
                val entry = hashEntries[slot]
                if (entry == EMPTY || hashKeys[slot] == key) return slot
                slot = (slot + 1) and hashMask
            }
        }

        fun layoutAtSlot(slot: Int): TextLayout? {
            val entry = hashEntries[slot]
            if (entry == EMPTY) return null

            moveToHead(entry)
            return entryLayouts[entry]
        }

        fun putAtMissSlot(key: Long, layout: TextLayout, missSlot: Int) {
            val entry = if (size < entryKeys.size) {
                size++
            } else {
                val evicted = tail
                removeHashEntry(entryKeys[evicted], evicted)
                unlink(evicted)
                evicted
            }

            entryKeys[entry] = key
            entryLayouts[entry] = layout
            linkHead(entry)
            insertHashEntryAtSlot(key, entry, missSlot)
        }

        fun clear() {
            Arrays.fill(entryLayouts, null)
            Arrays.fill(previous, EMPTY)
            Arrays.fill(next, EMPTY)
            Arrays.fill(hashEntries, EMPTY)
            size = 0
            head = EMPTY
            tail = EMPTY
        }

        private fun insertHashEntry(key: Long, entry: Int) {
            insertHashEntryAtSlot(key, entry, hashSlot(key))
        }

        private fun insertHashEntryAtSlot(key: Long, entry: Int, slotHint: Int) {
            var slot = slotHint
            while (hashEntries[slot] != EMPTY) {
                slot = (slot + 1) and hashMask
            }
            hashKeys[slot] = key
            hashEntries[slot] = entry
        }

        private fun removeHashEntry(key: Long, entry: Int) {
            var slot = hashSlot(key)
            while (true) {
                if (hashEntries[slot] == entry && hashKeys[slot] == key) {
                    hashEntries[slot] = EMPTY
                    reinsertHashCluster((slot + 1) and hashMask)
                    return
                }
                slot = (slot + 1) and hashMask
            }
        }

        private fun reinsertHashCluster(startSlot: Int) {
            var slot = startSlot
            while (hashEntries[slot] != EMPTY) {
                val key = hashKeys[slot]
                val entry = hashEntries[slot]
                hashEntries[slot] = EMPTY
                insertHashEntry(key, entry)
                slot = (slot + 1) and hashMask
            }
        }

        private fun moveToHead(entry: Int) {
            if (entry == head) return
            unlink(entry)
            linkHead(entry)
        }

        private fun unlink(entry: Int) {
            val previousEntry = previous[entry]
            val nextEntry = next[entry]
            if (previousEntry != EMPTY) {
                next[previousEntry] = nextEntry
            } else {
                head = nextEntry
            }
            if (nextEntry != EMPTY) {
                previous[nextEntry] = previousEntry
            } else {
                tail = previousEntry
            }
            previous[entry] = EMPTY
            next[entry] = EMPTY
        }

        private fun linkHead(entry: Int) {
            previous[entry] = EMPTY
            next[entry] = head
            if (head != EMPTY) {
                previous[head] = entry
            } else {
                tail = entry
            }
            head = entry
        }

        private fun hashSlot(key: Long): Int {
            var hash = key
            hash = hash xor (hash ushr 33)
            hash *= -49064778989728563L
            hash = hash xor (hash ushr 33)
            return hash.toInt() and hashMask
        }
    }

    @Suppress("DuplicatedCode")
    private class ClusterTextLayoutLru(capacity: Int) {
        private val entryCodepoints = arrayOfNulls<IntArray>(capacity)
        private val entryLengths = IntArray(capacity)
        private val entryHashes = IntArray(capacity)
        private val entryLayouts = arrayOfNulls<TextLayout>(capacity)
        private val previous = IntArray(capacity) { EMPTY }
        private val next = IntArray(capacity) { EMPTY }
        private val hashKeys = IntArray(hashCapacity(capacity))
        private val hashEntries = IntArray(hashKeys.size) { EMPTY }
        private val hashMask = hashKeys.size - 1
        private var size = 0
        private var head = EMPTY
        private var tail = EMPTY

        fun contentHash(codepoints: IntArray, offset: Int, length: Int): Int {
            var result = length
            var index = 0
            while (index < length) {
                result = 31 * result + codepoints[offset + index]
                index++
            }
            return result
        }

        fun findSlot(codepoints: IntArray, offset: Int, length: Int, hash: Int): Int {
            var slot = hashSlot(hash)
            while (true) {
                val entry = hashEntries[slot]
                if (entry == EMPTY) return slot
                if (hashKeys[slot] == hash && equalsEntry(entry, codepoints, offset, length)) {
                    return slot
                }
                slot = (slot + 1) and hashMask
            }
        }

        fun layoutAtSlot(slot: Int): TextLayout? {
            val entry = hashEntries[slot]
            if (entry == EMPTY) return null

            moveToHead(entry)
            return entryLayouts[entry]
        }

        fun putAtMissSlot(
            codepoints: IntArray,
            offset: Int,
            length: Int,
            hash: Int,
            layout: TextLayout,
            missSlot: Int,
        ) {
            val entry = if (size < entryCodepoints.size) {
                size++
            } else {
                val evicted = tail
                removeHashEntry(entryHashes[evicted], evicted)
                unlink(evicted)
                evicted
            }

            val stored = IntArray(length)
            System.arraycopy(codepoints, offset, stored, 0, length)
            entryCodepoints[entry] = stored
            entryLengths[entry] = length
            entryHashes[entry] = hash
            entryLayouts[entry] = layout
            linkHead(entry)
            insertHashEntryAtSlot(hash, entry, missSlot)
        }

        fun clear() {
            Arrays.fill(entryCodepoints, null)
            Arrays.fill(entryLayouts, null)
            Arrays.fill(previous, EMPTY)
            Arrays.fill(next, EMPTY)
            Arrays.fill(hashEntries, EMPTY)
            size = 0
            head = EMPTY
            tail = EMPTY
        }

        private fun equalsEntry(entry: Int, codepoints: IntArray, offset: Int, length: Int): Boolean {
            if (entryLengths[entry] != length) return false
            val stored = entryCodepoints[entry] ?: return false
            var index = 0
            while (index < length) {
                if (stored[index] != codepoints[offset + index]) return false
                index++
            }
            return true
        }

        private fun insertHashEntry(hash: Int, entry: Int) {
            insertHashEntryAtSlot(hash, entry, hashSlot(hash))
        }

        private fun insertHashEntryAtSlot(hash: Int, entry: Int, slotHint: Int) {
            var slot = slotHint
            while (hashEntries[slot] != EMPTY) {
                slot = (slot + 1) and hashMask
            }
            hashKeys[slot] = hash
            hashEntries[slot] = entry
        }

        private fun removeHashEntry(hash: Int, entry: Int) {
            var slot = hashSlot(hash)
            while (true) {
                if (hashEntries[slot] == entry && hashKeys[slot] == hash) {
                    hashEntries[slot] = EMPTY
                    reinsertHashCluster((slot + 1) and hashMask)
                    return
                }
                slot = (slot + 1) and hashMask
            }
        }

        private fun reinsertHashCluster(startSlot: Int) {
            var slot = startSlot
            while (hashEntries[slot] != EMPTY) {
                val hash = hashKeys[slot]
                val entry = hashEntries[slot]
                hashEntries[slot] = EMPTY
                insertHashEntry(hash, entry)
                slot = (slot + 1) and hashMask
            }
        }

        private fun moveToHead(entry: Int) {
            if (entry == head) return
            unlink(entry)
            linkHead(entry)
        }

        private fun unlink(entry: Int) {
            val previousEntry = previous[entry]
            val nextEntry = next[entry]
            if (previousEntry != EMPTY) {
                next[previousEntry] = nextEntry
            } else {
                head = nextEntry
            }
            if (nextEntry != EMPTY) {
                previous[nextEntry] = previousEntry
            } else {
                tail = previousEntry
            }
            previous[entry] = EMPTY
            next[entry] = EMPTY
        }

        private fun linkHead(entry: Int) {
            previous[entry] = EMPTY
            next[entry] = head
            if (head != EMPTY) {
                previous[head] = entry
            } else {
                tail = entry
            }
            head = entry
        }

        private fun hashSlot(hash: Int): Int {
            var mixed = hash
            mixed = mixed xor (mixed ushr 16)
            mixed *= -2048144789
            mixed = mixed xor (mixed ushr 13)
            return mixed and hashMask
        }

    }

    private companion object {
        private const val DEFAULT_CODE_POINT_CAPACITY = 4096
        private const val DEFAULT_CLUSTER_CAPACITY_PER_STYLE = 1024
        private const val STYLE_COUNT = 4
        private const val STYLE_MASK = Font.BOLD or Font.ITALIC
        private const val EMPTY = -1

        /**
         * The absolute upper bound for permitted UTF-16 code units within a single cluster.
         * Designed to safely accommodate complex multi-modifier emojis (e.g., standard family
         * configurations) while intercepting malicious deep-nested styling exploits.
         */
        private const val MAX_CLUSTER_LENGTH = 32

        private const val REPLACEMENT_CODE_POINT = 0xFFFD

        @JvmStatic
        private fun codePointKey(codePoint: Int, style: Int): Long {
            return (style.toLong() shl 32) or (codePoint.toLong() and 0xFFFF_FFFFL)
        }

        @JvmStatic
        private fun hashCapacity(entryCapacity: Int): Int {
            var capacity = 1
            while (capacity < entryCapacity * 2) {
                capacity = capacity shl 1
            }
            return capacity
        }
    }
}
