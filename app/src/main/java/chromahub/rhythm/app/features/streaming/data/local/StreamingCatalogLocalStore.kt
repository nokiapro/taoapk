/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.local

import android.content.Context
import chromahub.rhythm.app.features.streaming.data.local.StreamingCatalogMappers.toDomain
import chromahub.rhythm.app.features.streaming.data.local.StreamingCatalogMappers.toEntity
import chromahub.rhythm.app.features.streaming.data.repository.StreamingCatalogCache
import chromahub.rhythm.app.features.streaming.domain.model.StreamingAlbum
import chromahub.rhythm.app.features.streaming.domain.model.StreamingArtist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingPlaylist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import kotlinx.coroutines.runBlocking

interface StreamingCatalogLocalStore {
    suspend fun loadCatalog(serviceId: String): StreamingCatalogCache?
    suspend fun hasCachedCatalog(serviceId: String): Boolean
    suspend fun getSyncMetadata(serviceId: String): CachedSyncMetadataEntity?
    suspend fun getNewestSongs(serviceId: String, limit: Int): List<StreamingSong>
    suspend fun saveCatalog(cache: StreamingCatalogCache)
    suspend fun updateStreamingUrl(songId: String, streamingUrl: String)
    suspend fun updateFavorite(songId: String, isFavorite: Boolean)
    suspend fun updatePurchased(songId: String, isPurchased: Boolean, streamingUrl: String?)
    suspend fun clearService(serviceId: String)
}

class RoomStreamingCatalogStore(
    private val dao: StreamingCatalogDao
) : StreamingCatalogLocalStore {

    constructor(context: Context) : this(StreamingCatalogDatabase.getInstance(context).catalogDao())

    override suspend fun loadCatalog(serviceId: String): StreamingCatalogCache? {
        val songEntities = dao.getSongs(serviceId)
        if (songEntities.isEmpty()) return null

        val songs = songEntities.map { it.toDomain() }
        val songById = songs.associateBy { it.id }
        val tracksByAlbumId = songs.filter { !it.albumId.isNullOrBlank() }.groupBy { it.albumId!! }

        val albums: List<StreamingAlbum> = dao.getAlbums(serviceId).map { it.toDomain(tracksByAlbumId) }
        val artists: List<StreamingArtist> = dao.getArtists(serviceId).map { it.toDomain() }
        val playlists: List<StreamingPlaylist> = dao.getPlaylists(serviceId).map { it.toDomain(songById) }
        val likedIds = dao.getLikedSongIds(serviceId)
        val metadata = dao.getSyncMetadata(serviceId)

        return StreamingCatalogCache(
            serviceId = serviceId,
            songs = songs,
            albums = albums,
            artists = artists,
            playlists = playlists,
            likedSongIds = likedIds,
            lastSyncTimestamp = metadata?.lastFullSyncTimestamp ?: 0L,
            libraryMarker = metadata?.libraryMarker,
            commerceSchemaVersion = metadata?.commerceSchemaVersion
                ?: StreamingCatalogCache.CURRENT_COMMERCE_SCHEMA_VERSION
        )
    }

    override suspend fun hasCachedCatalog(serviceId: String): Boolean {
        return dao.getSongsCount(serviceId) > 0
    }

    override suspend fun getSyncMetadata(serviceId: String): CachedSyncMetadataEntity? {
        return dao.getSyncMetadata(serviceId)
    }

    override suspend fun getNewestSongs(serviceId: String, limit: Int): List<StreamingSong> {
        return dao.getNewestSongs(serviceId, limit.coerceAtLeast(1)).map { it.toDomain() }
    }

    override suspend fun saveCatalog(cache: StreamingCatalogCache) {
        val now = System.currentTimeMillis()
        val songEntities = cache.songs.mapIndexed { index, song ->
            song.toEntity(serviceId = cache.serviceId, sortOrder = index, updatedAtMs = now)
        }
        val albumEntities = cache.albums.mapIndexed { index, album ->
            album.toEntity(serviceId = cache.serviceId, sortOrder = index)
        }
        val artistEntities = cache.artists.mapIndexed { index, artist ->
            artist.toEntity(serviceId = cache.serviceId, sortOrder = index)
        }
        val playlistEntities = cache.playlists.mapIndexed { index, playlist ->
            playlist.toEntity(serviceId = cache.serviceId, sortOrder = index)
        }
        val metadata = CachedSyncMetadataEntity(
            serviceId = cache.serviceId,
            lastFullSyncTimestamp = cache.lastSyncTimestamp.takeIf { it > 0L } ?: now,
            libraryMarker = cache.libraryMarker,
            commerceSchemaVersion = cache.commerceSchemaVersion,
            songCount = cache.songs.size
        )
        dao.replaceFullCatalog(
            serviceId = cache.serviceId,
            songs = songEntities,
            albums = albumEntities,
            artists = artistEntities,
            playlists = playlistEntities,
            metadata = metadata
        )
    }

    override suspend fun updateStreamingUrl(songId: String, streamingUrl: String) {
        dao.updateStreamingUrl(songId = songId, streamingUrl = streamingUrl, updatedAtMs = System.currentTimeMillis())
    }

    override suspend fun updateFavorite(songId: String, isFavorite: Boolean) {
        dao.updateFavorite(songId = songId, isFavorite = isFavorite, updatedAtMs = System.currentTimeMillis())
    }

    override suspend fun updatePurchased(songId: String, isPurchased: Boolean, streamingUrl: String?) {
        dao.updatePurchased(
            songId = songId,
            isPurchased = isPurchased,
            streamingUrl = streamingUrl,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    override suspend fun clearService(serviceId: String) {
        dao.clearServiceCatalog(serviceId)
    }

    fun hasCachedCatalogBlocking(serviceId: String): Boolean = runBlocking {
        hasCachedCatalog(serviceId)
    }
}
