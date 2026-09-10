package cc.tomko.outify.ui.components.player

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle

/** Fixed maximum measurement prevents lyric emphasis from moving the scroll target. */
@Composable
internal fun EmphasizedLyricText(
    text: String,
    visualScale: Float,
    baseStyle: TextStyle,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val isActive = visualScale > 1f
    Text(
        text = text,
        color = if (isActive) activeColor else inactiveColor,
        style = baseStyle.copy(
            fontSize = baseStyle.fontSize * ActiveLyricScale,
            lineHeight = baseStyle.lineHeight * ActiveLyricScale,
        ),
        modifier = modifier.graphicsLayer {
            scaleX = visualScale / ActiveLyricScale
            scaleY = visualScale / ActiveLyricScale
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    )
}
