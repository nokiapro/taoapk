/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface StreamingCatalogDao {
    @Query("SELECT * FROM streaming_songs WHERE serviceId = :serviceId ORDER BY sortOrder ASC")
    suspend fun getSongs(serviceId: String): List<CachedStreamingSongEntity>

    @Query("SELECT * FROM streaming_songs WHERE serviceId = :serviceId ORDER BY createdAtMs DESC, sortOrder DESC LIMIT :limit")
    suspend fun getNewestSongs(serviceId: String, limit: Int): List<CachedStreamingSongEntity>

    @Query("SELECT COUNT(*) FROM streaming_songs WHERE serviceId = :serviceId")
    suspend fun getSongsCount(serviceId: String): Int

    @Query("SELECT id FROM streaming_songs WHERE serviceId = :serviceId AND isFavorite = 1")
    suspend fun getLikedSongIds(serviceId: String): List<String>

    @Query("SELECT * FROM streaming_albums WHERE serviceId = :serviceId ORDER BY sortOrder ASC")
    suspend fun getAlbums(serviceId: String): List<CachedStreamingAlbumEntity>

    @Query("SELECT * FROM streaming_artists WHERE serviceId = :serviceId ORDER BY sortOrder ASC")
    suspend fun getArtists(serviceId: String): List<CachedStreamingArtistEntity>

    @Query("SELECT * FROM streaming_playlists WHERE serviceId = :serviceId ORDER BY sortOrder ASC")
    suspend fun getPlaylists(serviceId: String): List<CachedStreamingPlaylistEntity>

    @Query("SELECT * FROM streaming_sync_metadata WHERE serviceId = :serviceId")
    suspend fun getSyncMetadata(serviceId: String): CachedSyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<CachedStreamingSongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<CachedStreamingAlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(artists: List<CachedStreamingArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<CachedStreamingPlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncMetadata(metadata: CachedSyncMetadataEntity)

    @Query("UPDATE streaming_songs SET streamingUrl = :streamingUrl, updatedAtMs = :updatedAtMs WHERE id = :songId")
    suspend fun updateStreamingUrl(songId: String, streamingUrl: String, updatedAtMs: Long)

    @Query("UPDATE streaming_songs SET isFavorite = :isFavorite, updatedAtMs = :updatedAtMs WHERE id = :songId")
    suspend fun updateFavorite(songId: String, isFavorite: Boolean, updatedAtMs: Long)

    @Query("UPDATE streaming_songs SET isPurchased = :isPurchased, streamingUrl = COALESCE(:streamingUrl, streamingUrl), updatedAtMs = :updatedAtMs WHERE id = :songId")
    suspend fun updatePurchased(songId: String, isPurchased: Boolean, streamingUrl: String?, updatedAtMs: Long)

    @Query("DELETE FROM streaming_songs WHERE serviceId = :serviceId")
    suspend fun deleteSongsForService(serviceId: String)

    @Query("DELETE FROM streaming_albums WHERE serviceId = :serviceId")
    suspend fun deleteAlbumsForService(serviceId: String)

    @Query("DELETE FROM streaming_artists WHERE serviceId = :serviceId")
    suspend fun deleteArtistsForService(serviceId: String)

    @Query("DELETE FROM streaming_playlists WHERE serviceId = :serviceId")
    suspend fun deletePlaylistsForService(serviceId: String)

    @Query("DELETE FROM streaming_sync_metadata WHERE serviceId = :serviceId")
    suspend fun deleteSyncMetadataForService(serviceId: String)

    @Transaction
    suspend fun replaceFullCatalog(
        serviceId: String,
        songs: List<CachedStreamingSongEntity>,
        albums: List<CachedStreamingAlbumEntity>,
        artists: List<CachedStreamingArtistEntity>,
        playlists: List<CachedStreamingPlaylistEntity>,
        metadata: CachedSyncMetadataEntity
    ) {
        deleteSongsForService(serviceId)
        deleteAlbumsForService(serviceId)
        deleteArtistsForService(serviceId)
        deletePlaylistsForService(serviceId)

        songs.chunked(SQLITE_BATCH_SIZE).forEach { batch -> insertSongs(batch) }
        albums.chunked(SQLITE_BATCH_SIZE).forEach { batch -> insertAlbums(batch) }
        artists.chunked(SQLITE_BATCH_SIZE).forEach { batch -> insertArtists(batch) }
        playlists.chunked(SQLITE_BATCH_SIZE).forEach { batch -> insertPlaylists(batch) }
        upsertSyncMetadata(metadata)
    }

    @Transaction
    suspend fun clearServiceCatalog(serviceId: String) {
        deleteSongsForService(serviceId)
        deleteAlbumsForService(serviceId)
        deleteArtistsForService(serviceId)
        deletePlaylistsForService(serviceId)
        deleteSyncMetadataForService(serviceId)
    }

    companion object {
        private const val SQLITE_BATCH_SIZE = 500
    }
}
