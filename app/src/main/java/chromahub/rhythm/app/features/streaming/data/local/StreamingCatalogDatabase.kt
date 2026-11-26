/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedStreamingSongEntity::class,
        CachedStreamingAlbumEntity::class,
        CachedStreamingArtistEntity::class,
        CachedStreamingPlaylistEntity::class,
        CachedSyncMetadataEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class StreamingCatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): StreamingCatalogDao

    companion object {
        private const val DATABASE_NAME = "xk_streaming_catalog.db"

        @Volatile
        private var INSTANCE: StreamingCatalogDatabase? = null

        fun getInstance(context: Context): StreamingCatalogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StreamingCatalogDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
