package cc.tomko.outify.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.navigation.LocalGoHome

/**
 * Persistent brand mark ("Spoty" + "by Darius") rendered at the top-right of every screen.
 *
 * Single-line row sized at [BRAND_SCALE] times the default title/label styles so the mark reads
 * from a distance (car screens) while still fitting next to existing header actions. When
 * [showHome] is true and a [LocalGoHome] action is available, a Home icon button is drawn to
 * the left of the mark and the whole row also acts as a shortcut back to Home.
 */
@Composable
fun SpotyBrand(
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    showHome: Boolean = true,
) {
    val nameStyle = MaterialTheme.typography.titleMedium.scaled(BRAND_SCALE)
    val authorStyle = MaterialTheme.typography.labelSmall.scaled(BRAND_SCALE)
    val goHome = LocalGoHome.current
    val homeEnabled = showHome && goHome != null

    // One clickable node for the whole mark (icon + text): a nested IconButton would expose two
    // overlapping actions to accessibility services for the same shortcut.
    Row(
        modifier = if (homeEnabled) {
            modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = stringResource(R.string.go_home)) { goHome?.invoke() }
        } else modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (homeEnabled) {
            Icon(
                imageVector = Icons.Rounded.Home,
                contentDescription = stringResource(R.string.go_home),
                tint = contentColor,
                modifier = Modifier
                    .padding(8.dp)
                    .size(32.dp),
            )
        }
        Text(
            text = stringResource(R.string.brand_name),
            style = nameStyle,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(modifier = Modifier.width(8.dp))
        // On narrow headers the author line gives way first, the name never clips.
        Text(
            text = stringResource(R.string.brand_by_author),
            style = authorStyle,
            color = contentColor.copy(alpha = 0.7f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Size multiplier applied to both brand lines. */
private const val BRAND_SCALE = 2f

private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
)
