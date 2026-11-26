/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import chromahub.rhythm.app.core.domain.model.SourceType
import chromahub.rhythm.app.features.streaming.domain.model.AlbumType
import chromahub.rhythm.app.features.streaming.domain.model.PlaylistOwner
import chromahub.rhythm.app.features.streaming.domain.model.StreamingAlbum
import chromahub.rhythm.app.features.streaming.domain.model.StreamingArtist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingPlaylist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(
    tableName = "streaming_songs",
    indices = [
        Index(value = ["serviceId"]),
        Index(value = ["serviceId", "createdAtMs"]),
        Index(value = ["serviceId", "albumId"]),
        Index(value = ["serviceId", "artist"])
    ]
)
data class CachedStreamingSongEntity(
    @PrimaryKey val id: String,
    val serviceId: String,
    val sortOrder: Int,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val artworkUri: String?,
    val sourceType: String,
    val streamingUrl: String?,
    val previewUrl: String?,
    val isExplicit: Boolean,
    val popularity: Int?,
    val releaseDate: String?,
    val isPlayable: Boolean,
    val externalId: String?,
    val albumId: String?,
    val albumArtist: String?,
    val isrc: String?,
    val isFavorite: Boolean,
    val trackNumber: Int?,
    val year: Int?,
    val genre: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val codec: String?,
    val price: Double,
    val isPaidTrack: Boolean,
    val isPurchased: Boolean,
    val currency: String,
    val createdAtMs: Long,
    val detailPath: String?,
    val streamEndpointPath: String?,
    val updatedAtMs: Long
)

@Entity(
    tableName = "streaming_albums",
    indices = [Index(value = ["serviceId"])]
)
data class CachedStreamingAlbumEntity(
    @PrimaryKey val id: String,
    val serviceId: String,
    val sortOrder: Int,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val songCount: Int,
    val year: Int?,
    val sourceType: String,
    val externalId: String?,
    val releaseDate: String?,
    val albumType: String,
    val genresJson: String,
    val label: String?,
    val copyright: String?,
    val isExplicit: Boolean,
    val detailPath: String?
)

@Entity(
    tableName = "streaming_artists",
    indices = [Index(value = ["serviceId"])]
)
data class CachedStreamingArtistEntity(
    @PrimaryKey val id: String,
    val serviceId: String,
    val sortOrder: Int,
    val name: String,
    val artworkUri: String?,
    val songCount: Int,
    val albumCount: Int,
    val sourceType: String,
    val externalId: String?,
    val bio: String?,
    val genresJson: String,
    val followers: Long?,
    val popularity: Int?,
    val detailPath: String?
)

@Entity(
    tableName = "streaming_playlists",
    indices = [Index(value = ["serviceId"])]
)
data class CachedStreamingPlaylistEntity(
    @PrimaryKey val id: String,
    val serviceId: String,
    val sortOrder: Int,
    val name: String,
    val description: String?,
    val artworkUri: String?,
    val songCount: Int,
    val isEditable: Boolean,
    val sourceType: String,
    val externalId: String?,
    val ownerId: String?,
    val ownerName: String?,
    val ownerImageUrl: String?,
    val ownerIsCurrentUser: Boolean,
    val isPublic: Boolean,
    val isCollaborative: Boolean,
    val followers: Long?,
    val snapshotId: String?,
    val trackIdsJson: String,
    val detailPath: String?
)

@Entity(tableName = "streaming_sync_metadata")
data class CachedSyncMetadataEntity(
    @PrimaryKey val serviceId: String,
    val lastFullSyncTimestamp: Long,
    val libraryMarker: String?,
    val commerceSchemaVersion: Int,
    val songCount: Int
)

internal object StreamingCatalogMappers {
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    fun StreamingSong.toEntity(
        serviceId: String,
        sortOrder: Int,
        updatedAtMs: Long = System.currentTimeMillis()
    ): CachedStreamingSongEntity {
        val cleanProviderId = externalId?.takeIf { it.isNotBlank() } ?: id.substringAfterLast("::")
        return CachedStreamingSongEntity(
            id = id,
            serviceId = serviceId,
            sortOrder = sortOrder,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            artworkUri = artworkUri,
            sourceType = sourceType.name,
            streamingUrl = streamingUrl,
            previewUrl = previewUrl,
            isExplicit = isExplicit,
            popularity = popularity,
            releaseDate = releaseDate,
            isPlayable = isPlayable,
            externalId = cleanProviderId,
            albumId = albumId,
            albumArtist = albumArtist,
            isrc = isrc,
            isFavorite = isFavorite,
            trackNumber = trackNumber,
            year = year,
            genre = genre,
            bitrate = bitrate,
            sampleRate = sampleRate,
            channels = channels,
            codec = codec,
            price = price,
            isPaidTrack = isPaidTrack,
            isPurchased = isPurchased,
            currency = currency.ifBlank { "VND" },
            createdAtMs = createdAtMs,
            detailPath = detailPath ?: "api/v1/songs/$cleanProviderId",
            streamEndpointPath = streamEndpointPath ?: "api/v1/songs/$cleanProviderId/stream",
            updatedAtMs = updatedAtMs
        )
    }

    fun CachedStreamingSongEntity.toDomain(): StreamingSong {
        val resolvedSource = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.CUSTOM_API)
        val cleanProviderId = externalId?.takeIf { it.isNotBlank() } ?: id.substringAfterLast("::")
        val effectivePaid = isPaidTrack || price > 0.0
        val effectivePurchased = if (effectivePaid) isPurchased else true
        return StreamingSong(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            artworkUri = artworkUri,
            sourceType = resolvedSource,
            streamingUrl = streamingUrl,
            previewUrl = previewUrl,
            isExplicit = isExplicit,
            popularity = popularity,
            releaseDate = releaseDate,
            isPlayable = isPlayable,
            externalId = cleanProviderId,
            albumId = albumId,
            albumArtist = albumArtist,
            isrc = isrc,
            isFavorite = isFavorite,
            trackNumber = trackNumber,
            year = year,
            genre = genre,
            bitrate = bitrate,
            sampleRate = sampleRate,
            channels = channels,
            codec = codec,
            price = price,
            isPaidTrack = effectivePaid,
            isPurchased = effectivePurchased,
            currency = currency.ifBlank { "VND" },
            createdAtMs = createdAtMs,
            detailPath = detailPath ?: "api/v1/songs/$cleanProviderId",
            streamEndpointPath = streamEndpointPath ?: "api/v1/songs/$cleanProviderId/stream"
        )
    }

    fun StreamingAlbum.toEntity(serviceId: String, sortOrder: Int): CachedStreamingAlbumEntity {
        val cleanId = externalId?.takeIf { it.isNotBlank() } ?: id.substringAfterLast("::album::")
        return CachedStreamingAlbumEntity(
            id = id,
            serviceId = serviceId,
            sortOrder = sortOrder,
            title = title,
            artist = artist,
            artworkUri = artworkUri,
            songCount = songCount,
            year = year,
            sourceType = sourceType.name,
            externalId = externalId,
            releaseDate = releaseDate,
            albumType = albumType.name,
            genresJson = gson.toJson(genres),
            label = label,
            copyright = copyright,
            isExplicit = isExplicit,
            detailPath = detailPath ?: "api/v1/albums/${cleanId.replace("_slash_", "/")}"
        )
    }

    fun CachedStreamingAlbumEntity.toDomain(tracksByAlbumId: Map<String, List<StreamingSong>> = emptyMap()): StreamingAlbum {
        val resolvedSource = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.CUSTOM_API)
        val resolvedAlbumType = runCatching { AlbumType.valueOf(albumType) }.getOrDefault(AlbumType.ALBUM)
        val resolvedGenres: List<String> = runCatching {
            gson.fromJson<List<String>>(genresJson, stringListType)
        }.getOrNull().orEmpty()
        val albumTracks = tracksByAlbumId[id].orEmpty()
        return StreamingAlbum(
            id = id,
            title = title,
            artist = artist,
            artworkUri = artworkUri,
            songCount = maxOf(songCount, albumTracks.size),
            year = year,
            sourceType = resolvedSource,
            externalId = externalId,
            releaseDate = releaseDate,
            albumType = resolvedAlbumType,
            genres = resolvedGenres,
            label = label,
            copyright = copyright,
            isExplicit = isExplicit,
            tracks = albumTracks,
            detailPath = detailPath
        )
    }

    fun StreamingArtist.toEntity(serviceId: String, sortOrder: Int): CachedStreamingArtistEntity {
        val cleanId = externalId?.takeIf { it.isNotBlank() } ?: id.substringAfterLast("::artist::")
        return CachedStreamingArtistEntity(
            id = id,
            serviceId = serviceId,
            sortOrder = sortOrder,
            name = name,
            artworkUri = artworkUri,
            songCount = songCount,
            albumCount = albumCount,
            sourceType = sourceType.name,
            externalId = externalId,
            bio = bio,
            genresJson = gson.toJson(genres),
            followers = followers,
            popularity = popularity,
            detailPath = detailPath ?: "api/v1/artists/${cleanId.replace("_slash_", "/")}"
        )
    }

    fun CachedStreamingArtistEntity.toDomain(): StreamingArtist {
        val resolvedSource = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.CUSTOM_API)
        val resolvedGenres: List<String> = runCatching {
            gson.fromJson<List<String>>(genresJson, stringListType)
        }.getOrNull().orEmpty()
        return StreamingArtist(
            id = id,
            name = name,
            artworkUri = artworkUri,
            songCount = songCount,
            albumCount = albumCount,
            sourceType = resolvedSource,
            externalId = externalId,
            bio = bio,
            genres = resolvedGenres,
            followers = followers,
            popularity = popularity,
            detailPath = detailPath
        )
    }

    fun StreamingPlaylist.toEntity(serviceId: String, sortOrder: Int): CachedStreamingPlaylistEntity {
        val cleanId = externalId?.takeIf { it.isNotBlank() } ?: id.substringAfterLast("::playlist::")
        val trackIds = getTracks().map { it.id }
        return CachedStreamingPlaylistEntity(
            id = id,
            serviceId = serviceId,
            sortOrder = sortOrder,
            name = name,
            description = description,
            artworkUri = artworkUri,
            songCount = maxOf(songCount, trackIds.size),
            isEditable = isEditable,
            sourceType = sourceType.name,
            externalId = externalId,
            ownerId = owner?.id,
            ownerName = owner?.displayName,
            ownerImageUrl = owner?.imageUrl,
            ownerIsCurrentUser = owner?.isCurrentUser ?: false,
            isPublic = isPublic,
            isCollaborative = isCollaborative,
            followers = followers,
            snapshotId = snapshotId,
            trackIdsJson = gson.toJson(trackIds),
            detailPath = detailPath ?: "api/v1/playlists/$cleanId"
        )
    }

    fun CachedStreamingPlaylistEntity.toDomain(songById: Map<String, StreamingSong>): StreamingPlaylist {
        val resolvedSource = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.CUSTOM_API)
        val trackIds: List<String> = runCatching {
            gson.fromJson<List<String>>(trackIdsJson, stringListType)
        }.getOrNull().orEmpty()
        val resolvedTracks = trackIds.mapNotNull { songById[it] }
        val resolvedOwner = if (!ownerId.isNullOrBlank() || !ownerName.isNullOrBlank()) {
            PlaylistOwner(
                id = ownerId.orEmpty(),
                displayName = ownerName.orEmpty(),
                imageUrl = ownerImageUrl,
                isCurrentUser = ownerIsCurrentUser
            )
        } else {
            null
        }
        return StreamingPlaylist(
            id = id,
            name = name,
            description = description,
            artworkUri = artworkUri,
            songCount = maxOf(songCount, resolvedTracks.size),
            isEditable = isEditable,
            sourceType = resolvedSource,
            externalId = externalId,
            owner = resolvedOwner,
            isPublic = isPublic,
            isCollaborative = isCollaborative,
            followers = followers,
            snapshotId = snapshotId,
            tracks = resolvedTracks,
            detailPath = detailPath
        )
    }
}
