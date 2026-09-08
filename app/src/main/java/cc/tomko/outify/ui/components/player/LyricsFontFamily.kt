package cc.tomko.outify.ui.components.player

import androidx.compose.ui.text.font.FontFamily

/**
 * Typeface options offered for the lyrics screens. Each maps to a generic family that Compose
 * always provides, so no font assets are bundled. [id] is the stable value stored in DataStore.
 */
enum class LyricsFontFamily(val id: String) {
    SANS("sans"),
    SERIF("serif"),
    MONO("mono");

    /** The Compose family this option renders with. */
    val fontFamily: FontFamily
        get() = when (this) {
            SANS -> FontFamily.SansSerif
            SERIF -> FontFamily.Serif
            MONO -> FontFamily.Monospace
        }

    companion object {
        /** Parses a stored [id] back to an option; unknown or legacy values fall back to [SANS]. */
        fun fromId(id: String?): LyricsFontFamily =
            entries.firstOrNull { it.id == id } ?: SANS
    }
}
