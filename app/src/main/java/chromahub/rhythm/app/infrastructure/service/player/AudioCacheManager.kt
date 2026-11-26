/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.service.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Standard singleton cache manager for Media3 ExoPlayer caching.
 * Caches streamed music tracks locally to reduce latency, save bandwidth, and prevent buffering.
 */
@OptIn(UnstableApi::class)
object AudioCacheManager {
    private var simpleCache: SimpleCache? = null

    /**
     * Releases any active SimpleCache instance and deletes previously cached streaming MP3 chunks.
     * Streaming playback runs directly from server URLs without persisting MP3s to Disk Cache.
     */
    @Synchronized
    fun clearLegacyAudioCache(context: Context) {
        try {
            simpleCache?.release()
            simpleCache = null
        } catch (_: Exception) {
        }
        try {
            val legacyDir = File(context.cacheDir, "audio_cache")
            if (legacyDir.exists()) {
                legacyDir.deleteRecursively()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun evict(key: String) {
        try {
            simpleCache?.removeResource(key)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun evict(uri: android.net.Uri) {
        evict(uri.toString())
    }
}
