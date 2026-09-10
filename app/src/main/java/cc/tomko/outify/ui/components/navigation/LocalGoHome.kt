package cc.tomko.outify.ui.components.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Global "go home" action: dismisses any open popup/lyrics overlay, collapses the player
 * sheet, and selects the Home tab. `null` outside the tree that provides it.
 */
val LocalGoHome = staticCompositionLocalOf<(() -> Unit)?> { null }
