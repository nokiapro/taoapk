/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.presentation.components.common

import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.Icon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.shared.data.model.AppSettings

/**
 * Material 3 placeholder types
 */
enum class M3PlaceholderType {
    ALBUM,
    ARTIST,
    TRACK,
    PLAYLIST,
    GENERAL
}

private val defaultAlbumPlaceholderShape = RoundedCornerShape(8.dp)
private val defaultTrackPlaceholderShape = RoundedCornerShape(4.dp)

/**
 * A Material 3 placeholder for media content with expressive shape support
 */
@Composable
fun M3Placeholder(
    type: M3PlaceholderType,
    modifier: Modifier = Modifier,
    name: String? = null,
    shape: Shape? = null
) {
    // Use provided shape if available; avoid collecting AppSettings inside every list row
    val finalShape = shape ?: run {
        val context = LocalContext.current
        val appSettings = remember(context) { AppSettings.getInstance(context) }
        val expressiveShapesEnabled by appSettings.expressiveShapesEnabled.collectAsState()
        val defaultShape = when (type) {
            M3PlaceholderType.ALBUM -> defaultAlbumPlaceholderShape
            M3PlaceholderType.ARTIST -> CircleShape
            M3PlaceholderType.TRACK -> defaultTrackPlaceholderShape
            M3PlaceholderType.PLAYLIST -> defaultAlbumPlaceholderShape
            M3PlaceholderType.GENERAL -> defaultAlbumPlaceholderShape
        }
        if (expressiveShapesEnabled) {
            when (type) {
                M3PlaceholderType.ALBUM -> rememberExpressiveShapeFor(ExpressiveShapeTarget.ALBUM_ART, defaultShape)
                M3PlaceholderType.ARTIST -> rememberExpressiveShapeFor(ExpressiveShapeTarget.ARTIST_ART, defaultShape)
                M3PlaceholderType.TRACK -> rememberExpressiveShapeFor(ExpressiveShapeTarget.SONG_ART, defaultShape)
                M3PlaceholderType.PLAYLIST -> rememberExpressiveShapeFor(ExpressiveShapeTarget.PLAYLIST_ART, defaultShape)
                M3PlaceholderType.GENERAL -> rememberExpressiveShapeFor(ExpressiveShapeTarget.ALBUM_ART, defaultShape)
            }
        } else {
            defaultShape
        }
    }

    val icon = when (type) {
        M3PlaceholderType.ALBUM -> RhythmIcons.AlbumFilled
        M3PlaceholderType.ARTIST -> RhythmIcons.ArtistFilled
        M3PlaceholderType.TRACK -> RhythmIcons.MusicNote
        M3PlaceholderType.PLAYLIST -> RhythmIcons.Queue
        M3PlaceholderType.GENERAL -> MaterialSymbolIcon("audio_file", filled = true)
    }

    val containerColor = getColorForName(name, MaterialTheme.colorScheme.surfaceVariant)

    Surface(
        modifier = modifier.aspectRatio(1f),
        shape = finalShape,
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Layout(
            content = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { measurables, constraints ->
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else 48.dp.roundToPx()
            val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else 48.dp.roundToPx()
            val minDimPx = minOf(maxW, maxH)
            val iconSizePx = (minDimPx * 0.42f).toInt().coerceIn(28.dp.roundToPx(), 96.dp.roundToPx())
            val placeable = measurables[0].measure(Constraints.fixed(iconSizePx, iconSizePx))
            val layoutW = constraints.constrainWidth(iconSizePx)
            val layoutH = constraints.constrainHeight(iconSizePx)
            layout(layoutW, layoutH) {
                placeable.placeRelative(
                    x = (layoutW - placeable.width) / 2,
                    y = (layoutH - placeable.height) / 2
                )
            }
        }
    }
}

private fun androidx.compose.ui.unit.Constraints.constrainWidth(width: Int): Int {
    return if (hasBoundedWidth) maxWidth else width.coerceIn(minWidth, maxWidth)
}

private fun androidx.compose.ui.unit.Constraints.constrainHeight(height: Int): Int {
    return if (hasBoundedHeight) maxHeight else height.coerceIn(minHeight, maxHeight)
}

/**
 * Generate a consistent color based on the name without allocating temporary lists/Random objects
 */
@Composable
private fun getColorForName(name: String?, fallbackColor: Color): Color {
    if (name.isNullOrBlank()) {
        return fallbackColor
    }
    return when (Math.floorMod(name.hashCode(), 4)) {
        0 -> MaterialTheme.colorScheme.primaryContainer
        1 -> MaterialTheme.colorScheme.secondaryContainer
        2 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

/**
 * Album art placeholder
 */
@Composable
fun AlbumPlaceholder(
    modifier: Modifier = Modifier,
    name: String? = null,
    shape: Shape? = null
) {
    M3Placeholder(
        type = M3PlaceholderType.ALBUM,
        name = name,
        modifier = modifier,
        shape = shape
    )
}

/**
 * Artist image placeholder
 */
@Composable
fun ArtistPlaceholder(
    modifier: Modifier = Modifier,
    name: String? = null,
    shape: Shape? = null
) {
    M3Placeholder(
        type = M3PlaceholderType.ARTIST,
        name = name,
        modifier = modifier,
        shape = shape
    )
}

/**
 * Track placeholder
 */
@Composable
fun TrackPlaceholder(
    modifier: Modifier = Modifier,
    name: String? = null,
    shape: Shape? = null
) {
    M3Placeholder(
        type = M3PlaceholderType.TRACK,
        name = name,
        modifier = modifier,
        shape = shape
    )
}

/**
 * Playlist placeholder
 */
@Composable
fun PlaylistPlaceholder(
    modifier: Modifier = Modifier,
    name: String? = null,
    shape: Shape? = null
) {
    M3Placeholder(
        type = M3PlaceholderType.PLAYLIST,
        name = name,
        modifier = modifier,
        shape = shape
    )
}
