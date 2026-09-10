package cc.tomko.outify.ui.components.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.ui.components.bottomsheet.LyricsList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LyricEmphasisRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unsyncedSheetAndCardUseFullOpacityNormalColorAtBaseScale() {
        var cardNormalColor = Color.Unspecified
        compose.setContent {
            MaterialTheme {
                cardNormalColor = MaterialTheme.colorScheme.onPrimaryContainer
                Column {
                    LyricsList(
                        lyrics = listOf(LyricLine(0, "sheet line")),
                        currentPositionMs = 1000,
                        fontScale = 1f,
                        fontFamily = FontFamily.SansSerif,
                        bold = false,
                        isSynced = false,
                        activeLineColor = Color.Red,
                        inactiveTextColor = Color.Blue,
                        onLineClick = {},
                        modifier = Modifier.height(80.dp),
                    )
                    LyricsCard(
                        lines = listOf(LyricLine(0, "card line")),
                        activeIndex = 0,
                        isSynced = false,
                        fontScale = 1f,
                        onExpand = {},
                        onSeek = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        listOf("sheet line" to Color.Blue, "card line" to cardNormalColor).forEach { (text, normal) ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(normal, results.single().layoutInput.style.color)
            assertEquals(1f, results.single().layoutInput.style.color.alpha, 0f)
        }
        assertEquals(1f, lyricLineScale(0, 0, false), 0f)
    }

    @Test
    fun colorMovesToCurrentLineWithoutChangingMeasuredBoundsOrBoldPreference() {
        val activeIndex = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                Column {
                    repeat(2) { index ->
                        EmphasizedLyricText(
                            text = "line $index",
                            visualScale = lyricLineScale(index, activeIndex.value, true),
                            baseStyle = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            activeColor = Color.Red,
                            inactiveColor = Color.Gray,
                        )
                    }
                }
            }
        }
        fun layout(index: Int): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("line $index")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val before = layout(0)
        assertEquals(Color.Red, before.layoutInput.style.color)
        assertEquals(Color.Gray, layout(1).layoutInput.style.color)
        compose.runOnIdle { activeIndex.value = 1 }
        val after = layout(0)
        assertEquals(Color.Gray, after.layoutInput.style.color)
        assertEquals(Color.Red, layout(1).layoutInput.style.color)
        assertEquals(before.size, after.size)
        assertEquals(24.sp, after.layoutInput.style.fontSize)
        assertEquals(FontWeight.Bold, after.layoutInput.style.fontWeight)
    }
}
