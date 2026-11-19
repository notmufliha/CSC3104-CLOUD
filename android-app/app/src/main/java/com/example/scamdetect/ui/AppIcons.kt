package com.example.scamdetect.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons the core Material set doesn't have, drawn from the Material Symbols paths (so the app doesn't need the large
 * material-icons-extended library).
 */
object AppIcons {
    /** Material "format_size": a large and a small T. The Text size row (iOS: SF Symbol textformat.size). */
    val TextSize: ImageVector by lazy {
        materialIcon(name = "Filled.FormatSize") {
            materialPath {
                moveTo(9f, 4f); verticalLineToRelative(3f); horizontalLineToRelative(5f); verticalLineToRelative(12f)
                horizontalLineToRelative(3f); verticalLineTo(7f); horizontalLineToRelative(5f); verticalLineTo(4f); close()
                moveTo(3f, 12f); horizontalLineToRelative(3f); verticalLineToRelative(7f); horizontalLineToRelative(3f)
                verticalLineToRelative(-7f); horizontalLineToRelative(3f); verticalLineTo(9f); horizontalLineTo(3f); close()
            }
        }
    }
}
