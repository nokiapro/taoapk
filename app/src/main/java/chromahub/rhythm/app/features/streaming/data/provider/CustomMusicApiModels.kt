/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import chromahub.rhythm.app.shared.data.model.LyricsData

data class CustomSiteInfo(
    val siteName: String = "XK Music",
    val description: String = "",
    val announcement: String = "",
    val version: String = "",
    val maintenanceMode: Boolean = false,
    val bannerUrl: String? = null
)

data class CustomUserProfile(
    val id: String = "",
    val username: String = "",
    val email: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val role: String = "user",
    val plan: String = "",
    val balance: Double? = null,
    val currency: String = "VND"
)

data class CustomUserSettings(
    val audioQuality: String = "HIGH",
    val pushNewMusic: Boolean = true,
    val privateSession: Boolean = false,
    val explicitFilter: Boolean = false,
    val language: String = "en"
)

data class CustomSongMeta(
    val song: ProviderSong,
    val price: Double = 0.0,
    val currency: String = "VND",
    val isPurchased: Boolean = true,
    val isPaidTrack: Boolean = false,
    val isLiked: Boolean = false,
    val isBlocked: Boolean = false,
    val playCount: Long = 0L,
    val lyrics: LyricsData? = null
)

data class CustomHomePayload(
    val recommendations: List<ProviderSong> = emptyList(),
    val newestSongs: List<ProviderSong> = emptyList(),
    val charts: List<ProviderSong> = emptyList(),
    val newReleases: List<ProviderAlbum> = emptyList(),
    val featuredPlaylists: List<ProviderPlaylist> = emptyList(),
    val artists: List<ProviderArtist> = emptyList()
)

data class CustomSearchPayload(
    val songs: List<ProviderSong> = emptyList(),
    val albums: List<ProviderAlbum> = emptyList(),
    val artists: List<ProviderArtist> = emptyList(),
    val playlists: List<ProviderPlaylist> = emptyList()
)

data class BlockedSongItem(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val reason: String? = null,
    val blockedAt: String? = null
)

data class PurchaseRecord(
    val id: String,
    val songId: String,
    val songTitle: String,
    val artist: String = "",
    val artworkUrl: String? = null,
    val amount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val finalAmount: Double = 0.0,
    val currency: String = "VND",
    val voucherCode: String? = null,
    val status: String = "completed",
    val purchasedAt: String? = null
)

data class VoucherInfo(
    val id: String,
    val code: String,
    val title: String,
    val description: String = "",
    val discountType: String = "percent",
    val discountValue: Double = 0.0,
    val minOrderAmount: Double = 0.0,
    val expiresAt: String? = null,
    val isActive: Boolean = true
)

data class VoucherValidationResult(
    val valid: Boolean,
    val code: String,
    val message: String = "",
    val originalPrice: Double = 0.0,
    val discountAmount: Double = 0.0,
    val finalPrice: Double = 0.0,
    val currency: String = "VND"
)

data class CustomAlbumDetail(
    val album: ProviderAlbum,
    val songs: List<ProviderSong>,
    val isLiked: Boolean = false
)

data class CustomArtistDetail(
    val artist: ProviderArtist,
    val topTracks: List<ProviderSong>,
    val albums: List<ProviderAlbum>
)

data class CustomPlaylistDetail(
    val playlist: ProviderPlaylist,
    val songs: List<ProviderSong>
)

data class CloudListeningEvent(
    val songId: String = "",
    val timestamp: Long = 0L,
    val durationMs: Long = 0L,
    val startTimestamp: Long = 0L,
    val endTimestamp: Long = 0L,
    val songTitle: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val genre: String? = null
)

data class CloudListeningStats(
    val totalDurationMs: Long = 0L,
    val totalPlayCount: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueSongs: Int = 0,
    val archiveDurationMs: Long = 0L,
    val archivePlayCount: Int = 0,
    val archiveUniqueArtists: Int = 0,
    val archiveUniqueSongs: Int = 0,
    val dailyListeningMs: Map<String, Long> = emptyMap(),
    val recentEvents: List<CloudListeningEvent> = emptyList(),
    val updatedAtMs: Long = 0L
)
