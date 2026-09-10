package cc.tomko.outify.ui.components.player

import cc.tomko.outify.core.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricEmphasisTest {
    private val lines = listOf(LyricLine(1000, "first"), LyricLine(2000, "second"), LyricLine(3000, "third"))

    @Test
    fun onlyCurrentLineGrowsAndPreviousLineReturnsToBase() {
        val first = currentLyricIndex(lines, 1000, true)
        val second = currentLyricIndex(lines, 2500, true)
        assertEquals(listOf(1.2f, 1f, 1f), lines.indices.map { lyricLineScale(it, first, true) })
        assertEquals(listOf(1f, 1.2f, 1f), lines.indices.map { lyricLineScale(it, second, true) })
        assertEquals(1.2f, lyricLineScale(2, currentLyricIndex(lines, 4000, true), true), 0f)
    }

    @Test
    fun unsyncedAndBeforeFirstTimestampHaveNoEmphasis() {
        assertEquals(-1, currentLyricIndex(lines, 999, true))
        assertEquals(-1, currentLyricIndex(lines, 2500, false))
        assertEquals(-1, currentLyricIndex(emptyList(), 2500, true))
        lines.indices.forEach {
            assertEquals(1f, lyricLineScale(it, -1, true), 0f)
            assertEquals(1f, lyricLineScale(it, it, false), 0f)
        }
    }
}
