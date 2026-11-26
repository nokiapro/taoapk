/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.util

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.ArtistArtworkSource
import chromahub.rhythm.app.shared.presentation.components.common.AlbumPlaceholder
import chromahub.rhythm.app.shared.presentation.components.common.ArtistPlaceholder
import chromahub.rhythm.app.shared.presentation.components.common.ExpressiveShapeTarget
import chromahub.rhythm.app.shared.presentation.components.common.M3PlaceholderType
import chromahub.rhythm.app.shared.presentation.components.common.PlaylistPlaceholder
import chromahub.rhythm.app.shared.presentation.components.common.TrackPlaceholder
import chromahub.rhythm.app.shared.presentation.components.common.rememberExpressiveShapeFor

/**
 * Modern Material 3 style utilities for image handling using Compose and Coil
 */
object M3ImageUtils {

    /**
     * Display a media image with appropriate Material 3 style placeholder
     */
    @Composable
    fun M3MediaImage(
        data: Any?,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        shape: Shape? = null,
        type: M3PlaceholderType = M3PlaceholderType.GENERAL,
        name: String? = null,
        expressiveShape: Shape? = null,
        thumbnailSizePx: Int? = 320
    ) {
        val context = LocalContext.current
        val hasData = data != null && data.toString().isNotBlank()
        val resolvedShape = shape ?: RectangleShape

        if (!hasData) {
            Box(modifier = modifier) {
                when (type) {
                    M3PlaceholderType.ALBUM -> AlbumPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                    M3PlaceholderType.ARTIST -> ArtistPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                    M3PlaceholderType.TRACK -> TrackPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                    M3PlaceholderType.PLAYLIST -> PlaylistPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                    M3PlaceholderType.GENERAL -> AlbumPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                }
            }
            return
        }

        val imageRequest = remember(data, thumbnailSizePx, context) {
            ImageRequest.Builder(context)
                .data(data)
                .apply {
                    if (thumbnailSizePx != null && thumbnailSizePx > 0) {
                        size(thumbnailSizePx)
                    }
                }
                .scale(Scale.FILL)
                .precision(Precision.INEXACT)
                .crossfade(false)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }

        val painter = rememberAsyncImagePainter(model = imageRequest)
        val isSuccess = painter.state is AsyncImagePainter.State.Success

        Box(modifier = modifier) {
            Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = if (shape != null) Modifier.fillMaxSize().clip(shape) else Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            if (!isSuccess) {
                when (type) {
                    M3PlaceholderType.ALBUM -> AlbumPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                    M3PlaceholderType.ARTIST -> ArtistPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                    M3PlaceholderType.TRACK -> TrackPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                    M3PlaceholderType.PLAYLIST -> PlaylistPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                    M3PlaceholderType.GENERAL -> AlbumPlaceholder(name = name, modifier = Modifier.fillMaxSize(), shape = resolvedShape)
                }
            }
        }
    }
    
    /**
     * Album art with Material 3 placeholder and expressive shape support
     */
    @Composable
    fun AlbumArt(
        imageUrl: Any?,
        albumName: String?,
        modifier: Modifier = Modifier,
        shape: Shape? = null,
        applyExpressiveShape: Boolean = true
    ) {
        val expressiveShape = if (shape == null && applyExpressiveShape) {
            rememberExpressiveShapeFor(
                ExpressiveShapeTarget.ALBUM_ART,
                MaterialTheme.shapes.large
            )
        } else {
            null
        }
        val finalShape = shape ?: expressiveShape
        
        M3MediaImage(
            data = imageUrl,
            contentDescription = stringResource(R.string.album_artwork_description, albumName ?: ""),
            modifier = modifier,
            shape = finalShape,
            type = M3PlaceholderType.ALBUM,
            name = albumName,
            expressiveShape = expressiveShape
        )
    }
    
    /**
     * Artist image with Material 3 placeholder and expressive shape support
     */
    @Composable
    fun ArtistImage(
        imageUrl: Any?,
        artistName: String?,
        modifier: Modifier = Modifier,
        shape: Shape? = null,
        applyExpressiveShape: Boolean = true,
        artistArtworkEnabled: Boolean? = null
    ) {
        val enabled = artistArtworkEnabled ?: run {
            val context = LocalContext.current
            val artistArtworkSource by remember(context) { AppSettings.getInstance(context).artistArtworkSource }.collectAsState()
            artistArtworkSource != ArtistArtworkSource.DISABLED
        }
        val data = if (!enabled) null else imageUrl

        val expressiveShape = if (shape == null && applyExpressiveShape) {
            rememberExpressiveShapeFor(
                ExpressiveShapeTarget.ARTIST_ART,
                CircleShape
            )
        } else {
            null
        }
        val finalShape = shape ?: expressiveShape
        
        M3MediaImage(
            data = data,
            contentDescription = stringResource(R.string.artist_artwork_description, artistName ?: ""),
            modifier = modifier,
            shape = finalShape,
            type = M3PlaceholderType.ARTIST,
            name = artistName,
            expressiveShape = expressiveShape
        )
    }
    
    /**
     * Track image with Material 3 placeholder and expressive shape support
     */
    @Composable
    fun TrackImage(
        imageUrl: Any?,
        trackName: String?,
        modifier: Modifier = Modifier,
        shape: Shape? = null,
        applyExpressiveShape: Boolean = true
    ) {
        val expressiveShape = if (shape == null && applyExpressiveShape) {
            rememberExpressiveShapeFor(
                ExpressiveShapeTarget.SONG_ART,
                MaterialTheme.shapes.large
            )
        } else {
            null
        }
        val finalShape = shape ?: expressiveShape
        
        M3MediaImage(
            data = imageUrl,
            contentDescription = stringResource(R.string.track_artwork_description, trackName ?: ""),
            modifier = modifier,
            shape = finalShape,
            type = M3PlaceholderType.TRACK,
            name = trackName,
            expressiveShape = expressiveShape
        )
    }
    
    /**
     * Playlist image with Material 3 placeholder and expressive shape support
     */
    @Composable
    fun PlaylistImage(
        imageUrl: Any?,
        playlistName: String?,
        modifier: Modifier = Modifier,
        shape: Shape? = null,
        applyExpressiveShape: Boolean = true
    ) {
        val expressiveShape = if (shape == null && applyExpressiveShape) {
            rememberExpressiveShapeFor(
                ExpressiveShapeTarget.PLAYLIST_ART,
                MaterialTheme.shapes.large
            )
        } else {
            null
        }
        val finalShape = shape ?: expressiveShape
        
        M3MediaImage(
            data = imageUrl,
            contentDescription = stringResource(R.string.playlist_artwork_description, playlistName ?: ""),
            modifier = modifier,
            shape = finalShape,
            type = M3PlaceholderType.PLAYLIST,
            name = playlistName,
            expressiveShape = expressiveShape
        )
    }
} 
