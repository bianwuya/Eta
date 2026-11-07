package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Exclude only the decoration above the input group, not IME/insets/attachments/queue rows. */
internal fun questionPanelBottomInset(measuredBarHeight: Dp, topDecorationHeight: Dp): Dp =
    (measuredBarHeight - topDecorationHeight).coerceAtLeast(0.dp) + 8.dp
