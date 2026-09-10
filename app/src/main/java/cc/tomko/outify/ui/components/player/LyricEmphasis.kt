package cc.tomko.outify.ui.components.player

import cc.tomko.outify.core.model.LyricLine

internal const val ActiveLyricScale = 1.2f

internal fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long, isSynced: Boolean): Int =
    if (isSynced) lines.indexOfLast { it.timestampMs <= positionMs } else -1

internal fun lyricLineScale(index: Int, activeIndex: Int, isSynced: Boolean): Float =
    if (isSynced && index >= 0 && index == activeIndex) ActiveLyricScale else 1f
