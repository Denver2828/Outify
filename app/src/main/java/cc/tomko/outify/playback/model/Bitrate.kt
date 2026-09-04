package cc.tomko.outify.playback.model

import androidx.annotation.StringRes
import cc.tomko.outify.R

enum class Bitrate {
    KBPS320,
    KBPS160,
    KBPS96,
}

@StringRes
fun Bitrate.labelRes(): Int = when (this) {
    Bitrate.KBPS320 -> R.string.sys_bitrate_very_high
    Bitrate.KBPS160 -> R.string.sys_bitrate_high
    Bitrate.KBPS96 -> R.string.sys_bitrate_normal
}

fun Bitrate.getSpeed() = when (this) {
    Bitrate.KBPS320 -> 320
    Bitrate.KBPS160 -> 160
    Bitrate.KBPS96 -> 96
}