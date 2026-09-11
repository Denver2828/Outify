package cc.tomko.outify.diagnostics

/** Only complete, typed snapshot lines are shared. Raw logs and arbitrary strings are omitted. */
class CloudReport private constructor(val text: String) {
    companion object {
        const val MAX_BYTES = 256 * 1024
        private const val N = "-?[0-9]{1,19}"
        private const val B = "(?:true|false)"
        private val allowed = listOf(
            "pcm: frames=$N lastSize=$N lastRate=$N lastChannels=$N",
            "writes: bytes=$N errors=$N partial=$N dropped=$N lastResult=$N",
            "recovery: rebuilds=$N zeroWrites=$N stalls=$N pendingBytes=$N droppedBytes=$N gaveUp=$B",
            "audioTrack: headPosition=$N underruns=$N",
            "STREAM_MUSIC volume=$N/$N fixedVolume=$B",
            "mode=$N musicActive=$B ringerMode=$N",
            "bluetoothA2dp=$B bluetoothSco=$B speakerphone=$B wiredHeadset=$B",
            "outputSampleRate=$N framesPerBuffer=$N",
        ).map(::Regex)

        fun prepare(raw: String): CloudReport? {
            if (raw.isBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
            var inSnapshot = false
            val lines = raw.lineSequence().filter { line ->
                if (line.startsWith("---") || line.startsWith("===")) {
                    inSnapshot = line == "--- Audio engine ---" || line == "--- AudioManager ---"
                    false
                } else inSnapshot && allowed.any { it.matches(line) }
            }.distinct().toList()
            if (lines.isEmpty()) return null
            val text = "Spoty cloud audio summary\n" + lines.joinToString("\n")
            return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }?.let(::CloudReport)
        }
    }
}
