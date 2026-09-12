package cc.tomko.outify.ui.screens.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSurfaceConsistencyTest {
    @Test
    fun dedicatedLyricsUsesSharedActionsAndFooterWithoutBranding() {
        val source = landscapeLyricsSource()

        assertTrue(source.contains("LyricsTrackActions("))
        assertTrue(source.contains("LyricsPlaybackFooter("))
        assertFalse(source.contains("SpotyBrand"))
        assertFalse(source.contains("LyricsShuffleButton("))
    }

    private fun landscapeLyricsSource(): String {
        val relativePath = "src/main/java/cc/tomko/outify/ui/screens/player/LandscapeLyricsScreen.kt"
        return sequenceOf(File(relativePath), File("app/$relativePath"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("LandscapeLyricsScreen.kt was not found from ${File(".").absolutePath}")
    }
}
