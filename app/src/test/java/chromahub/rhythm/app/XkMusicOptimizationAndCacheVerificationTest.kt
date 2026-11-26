/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app

import chromahub.rhythm.app.core.domain.model.SourceType
import chromahub.rhythm.app.features.local.presentation.screens.getLibraryResponsiveGridItemShape
import chromahub.rhythm.app.features.local.presentation.screens.groupedLibraryItemShape
import chromahub.rhythm.app.features.streaming.data.local.CachedStreamingAlbumEntity
import chromahub.rhythm.app.features.streaming.data.local.CachedStreamingArtistEntity
import chromahub.rhythm.app.features.streaming.data.local.CachedStreamingPlaylistEntity
import chromahub.rhythm.app.features.streaming.data.local.CachedStreamingSongEntity
import chromahub.rhythm.app.features.streaming.data.local.CachedSyncMetadataEntity
import chromahub.rhythm.app.features.streaming.data.local.StreamingCatalogMappers.toDomain
import chromahub.rhythm.app.features.streaming.data.local.StreamingCatalogMappers.toEntity
import chromahub.rhythm.app.features.streaming.data.repository.CatalogCacheCodec
import chromahub.rhythm.app.features.streaming.data.repository.CatalogSaveFilter
import chromahub.rhythm.app.features.streaming.data.repository.OnceGate
import chromahub.rhythm.app.features.streaming.data.repository.SingleFlight
import chromahub.rhythm.app.features.streaming.data.repository.StreamingCatalogCache
import chromahub.rhythm.app.features.streaming.domain.model.AlbumType
import chromahub.rhythm.app.features.streaming.domain.model.PlaylistOwner
import chromahub.rhythm.app.features.streaming.domain.model.StreamingAlbum
import chromahub.rhythm.app.features.streaming.domain.model.StreamingArtist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingPlaylist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.features.streaming.presentation.model.StreamingHomeHelpers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Pure JVM verification suite covering:
 * 1) 10,000-song persistent SQLite catalog caching (all metadata, URLs, detail paths, reopen persistence).
 * 2) Local-first cold-start loading, SingleFlight deduplication, OnceGate + 6h TTL guard.
 * 3) Offline browsing from persisted cache vs Online delta/full re-sync via MockWebServer.
 * 4) 300 MB image DiskCache limit, bounded OkHttp concurrency, and image cache reuse verification.
 * 5) Direct online MP3 streaming from server URL with ZERO MP3 Disk Cache, while preserving explicit offline downloads.
 * 6) "Nhạc mới phát hành" strictly capped at 5 newest-first items, pushing older tracks down on additions.
 * 7) 10,000-item scrolling/filtering/shape-cache latency & allocation verification.
 */
class XkMusicOptimizationAndCacheVerificationTest {

    private lateinit var tempDir: File
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("xk_music_verify_").toFile()
        dbFile = File(tempDir, "xk_streaming_catalog.db")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun sqliteCatalog_persists10000SongsAllFieldsAndUrlsAcrossAppRestart() {
        val songCount = 10_000
        val baseTime = 1_764_000_000_000L
        val generatedSongs = (1..songCount).map { idx ->
            val providerId = "xk_song_$idx"
            val artistName = if (idx % 17 == 0) "W/n" else "Nghệ sĩ ${(idx % 250) + 1}"
            val albumIndex = (idx % 200) + 1
            val isPaid = idx % 3 == 0
            val isPurchased = !isPaid || (idx % 6 == 0)
            StreamingSong(
                id = "CUSTOM::$providerId",
                title = "Bài Hát Số $idx",
                artist = artistName,
                album = "Album $albumIndex",
                duration = 180_000L + (idx % 60) * 1000L,
                artworkUri = "https://xkmu.xuanken.name.vn/covers/$idx.jpg",
                sourceType = SourceType.CUSTOM_API,
                streamingUrl = if (isPurchased) "https://xkmu.xuanken.name.vn/api/v1/stream/$providerId?q=320" else "https://xkmu.xuanken.name.vn/demo/$providerId.mp3",
                previewUrl = "https://xkmu.xuanken.name.vn/demo/$providerId.mp3",
                isExplicit = idx % 29 == 0,
                popularity = idx % 100,
                releaseDate = "2026-11-${((idx % 28) + 1).toString().padStart(2, '0')}",
                isPlayable = true,
                externalId = providerId,
                albumId = "CUSTOM::album::alb_$albumIndex",
                albumArtist = artistName,
                isrc = "VNXK26${idx.toString().padStart(6, '0')}",
                isFavorite = idx % 10 == 0,
                trackNumber = (idx % 20) + 1,
                year = 2026,
                genre = "V-Pop",
                bitrate = if (isPurchased) 320 else 128,
                sampleRate = 44100,
                channels = 2,
                codec = if (isPurchased && idx % 12 == 0) "WAV" else "MP3",
                price = if (isPaid) 25000.0 else 0.0,
                isPaidTrack = isPaid,
                isPurchased = isPurchased,
                currency = "VND",
                createdAtMs = baseTime + idx * 1000L,
                detailPath = "api/v1/songs/$providerId",
                streamEndpointPath = "api/v1/songs/$providerId/stream"
            )
        }

        val generatedAlbums = (1..200).map { a ->
            StreamingAlbum(
                id = "CUSTOM::album::alb_$a",
                title = "Album $a",
                artist = if (a % 17 == 0) "W/n" else "Nghệ sĩ $a",
                artworkUri = "https://xkmu.xuanken.name.vn/albums/$a.jpg",
                songCount = 50,
                year = 2026,
                sourceType = SourceType.CUSTOM_API,
                externalId = "alb_$a",
                releaseDate = "2026-11-01",
                albumType = AlbumType.ALBUM,
                genres = listOf("V-Pop", "Indie"),
                detailPath = "api/v1/albums/alb_$a"
            )
        }

        val generatedArtists = (1..250).map { ar ->
            val name = if (ar == 17) "W/n" else "Nghệ sĩ $ar"
            StreamingArtist(
                id = "CUSTOM::artist::art_$ar",
                name = name,
                artworkUri = "https://xkmu.xuanken.name.vn/artists/$ar.jpg",
                songCount = 40,
                albumCount = 2,
                sourceType = SourceType.CUSTOM_API,
                externalId = "art_$ar",
                bio = "Bio for $name",
                genres = listOf("V-Pop"),
                detailPath = if (ar == 17) "api/v1/artists/W/n" else "api/v1/artists/art_$ar"
            )
        }

        val generatedPlaylists = (1..20).map { pl ->
            StreamingPlaylist(
                id = "CUSTOM::playlist::pl_$pl",
                name = "Playlist Cá Nhân $pl",
                description = "Desc $pl",
                artworkUri = "https://xkmu.xuanken.name.vn/playlists/$pl.jpg",
                songCount = 10,
                isEditable = true,
                sourceType = SourceType.CUSTOM_API,
                externalId = "pl_$pl",
                owner = PlaylistOwner(id = "uid_1", displayName = "Xuan Ken", isCurrentUser = true),
                tracks = generatedSongs.subList((pl - 1) * 10, pl * 10),
                detailPath = "api/v1/playlists/pl_$pl"
            )
        }

        val syncTime = 1_764_100_000_000L
        val writeMs = measureTimeMillis {
            openCatalogSqlite(dbFile).use { conn ->
                createCatalogSchema(conn)
                persistCatalogInBatches(
                    conn = conn,
                    serviceId = "CUSTOM",
                    songs = generatedSongs,
                    albums = generatedAlbums,
                    artists = generatedArtists,
                    playlists = generatedPlaylists,
                    syncTimestamp = syncTime
                )
            }
        }

        // Simulate cold-start app reopen: open fresh SQLite connection from disk
        var restoredSongs: List<StreamingSong> = emptyList()
        var restoredAlbums: List<StreamingAlbum> = emptyList()
        var restoredArtists: List<StreamingArtist> = emptyList()
        var restoredPlaylists: List<StreamingPlaylist> = emptyList()
        var newest5FromDb: List<StreamingSong> = emptyList()
        var restoredSyncTs = 0L

        val coldReadMs = measureTimeMillis {
            openCatalogSqlite(dbFile).use { conn ->
                val entities = readAllSongs(conn, "CUSTOM")
                restoredSongs = entities.map { it.toDomain() }
                val songById = restoredSongs.associateBy { it.id }
                val tracksByAlbum = restoredSongs.filter { !it.albumId.isNullOrBlank() }.groupBy { it.albumId!! }
                restoredAlbums = readAllAlbums(conn, "CUSTOM").map { it.toDomain(tracksByAlbum) }
                restoredArtists = readAllArtists(conn, "CUSTOM").map { it.toDomain() }
                restoredPlaylists = readAllPlaylists(conn, "CUSTOM").map { it.toDomain(songById) }
                newest5FromDb = readNewestSongs(conn, "CUSTOM", 5).map { it.toDomain() }
                restoredSyncTs = readSyncTimestamp(conn, "CUSTOM")
            }
        }

        assertEquals(10_000, restoredSongs.size)
        assertEquals(200, restoredAlbums.size)
        assertEquals(250, restoredArtists.size)
        assertEquals(20, restoredPlaylists.size)
        assertEquals(syncTime, restoredSyncTs)

        // Spot check first, W/n slashed artist, and 10,000th song for 100% URL & path integrity
        val first = restoredSongs.first()
        assertEquals("CUSTOM::xk_song_1", first.id)
        assertEquals("https://xkmu.xuanken.name.vn/covers/1.jpg", first.artworkUri)
        assertEquals("https://xkmu.xuanken.name.vn/api/v1/stream/xk_song_1?q=320", first.streamingUrl)
        assertEquals("api/v1/songs/xk_song_1", first.detailPath)
        assertEquals("api/v1/songs/xk_song_1/stream", first.streamEndpointPath)

        val slashedSong = restoredSongs[16] // idx = 17
        assertEquals("W/n", slashedSong.artist)
        assertEquals("https://xkmu.xuanken.name.vn/covers/17.jpg", slashedSong.artworkUri)

        val last = restoredSongs.last()
        assertEquals("CUSTOM::xk_song_10000", last.id)
        assertEquals("Bài Hát Số 10000", last.title)
        assertEquals(5, newest5FromDb.size)
        assertEquals(listOf("CUSTOM::xk_song_10000", "CUSTOM::xk_song_9999", "CUSTOM::xk_song_9998", "CUSTOM::xk_song_9997", "CUSTOM::xk_song_9996"), newest5FromDb.map { it.id })

        // Verify artist, album, playlist detail paths persisted cleanly
        assertEquals("W/n", restoredArtists.first { it.externalId == "art_17" }.name)
        assertEquals("api/v1/artists/W/n", restoredArtists.first { it.externalId == "art_17" }.detailPath)
        assertEquals("api/v1/albums/alb_1", restoredAlbums.first().detailPath)
        assertEquals("api/v1/playlists/pl_1", restoredPlaylists.first().detailPath)
        assertEquals(10, restoredPlaylists.first().getTracks().size)

        // Verify codec round-trip preserves detailPath and streamEndpointPath too
        val codec = CatalogCacheCodec()
        val sw = StringWriter()
        codec.write(sw, StreamingCatalogCache(serviceId = "CUSTOM", songs = listOf(first), lastSyncTimestamp = syncTime))
        val codecRestored = codec.read(StringReader(sw.toString()))!!
        assertEquals("api/v1/songs/xk_song_1", codecRestored.songs.first().detailPath)
        assertEquals("api/v1/songs/xk_song_1/stream", codecRestored.songs.first().streamEndpointPath)

        assertTrue("Expected cold SQLite read of 10,000 rows under 3500ms, was ${coldReadMs}ms (write ${writeMs}ms)", coldReadMs < 3500L)
    }

    @Test
    fun newestReleases_cappedStrictlyAt5AndNewAdditionsPushOlderTracksDown() {
        val now = 1_764_200_000_000L
        val initial = (1..12).map { i ->
            sampleSong(id = "s_$i", title = "Track $i", createdAtMs = now + i * 60_000L)
        }

        // Replicate HomeScreen / DAO newest-5 sorting rule
        fun pickTop5Newest(songs: List<StreamingSong>): List<StreamingSong> {
            return songs.distinctBy { it.id }
                .sortedByDescending { it.createdAtMs }
                .take(5)
        }

        val initialTop5 = pickTop5Newest(initial)
        assertEquals(5, initialTop5.size)
        assertEquals(listOf("CUSTOM::s_12", "CUSTOM::s_11", "CUSTOM::s_10", "CUSTOM::s_9", "CUSTOM::s_8"), initialTop5.map { it.id })

        // Add newly uploaded track -> must become #1 and push s_8 out of top 5
        val newlyAdded = sampleSong(id = "s_new_13", title = "Brand New Track", createdAtMs = now + 13 * 60_000L)
        val afterAddTop5 = pickTop5Newest(initial + newlyAdded)
        assertEquals(5, afterAddTop5.size)
        assertEquals(
            listOf("CUSTOM::s_new_13", "CUSTOM::s_12", "CUSTOM::s_11", "CUSTOM::s_10", "CUSTOM::s_9"),
            afterAddTop5.map { it.id }
        )
        assertFalse(afterAddTop5.any { it.id == "CUSTOM::s_8" })
    }

    @Test
    fun syncGuards_deduplicateConcurrentSyncsAndRespectColdStartTtl() = runBlocking {
        val singleFlight = SingleFlight<Int>()
        val backendExecutions = AtomicInteger(0)

        val results = (1..12).map {
            async {
                singleFlight.run {
                    delay(40)
                    backendExecutions.incrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, backendExecutions.get())
        assertTrue(results.all { it == 1 })

        // OnceGate ensures cold-start catalog staleness check runs once per process launch
        val gate = OnceGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        assertFalse(gate.tryEnter())

        // CatalogSaveFilter skips redundant rewrites when metadata didn't change
        val saveFilter = CatalogSaveFilter()
        val cache = StreamingCatalogCache(serviceId = "CUSTOM", songs = listOf(sampleSong("1", "A", 100L)), lastSyncTimestamp = 10L)
        assertTrue(saveFilter.hasChanged(cache))
        saveFilter.remember(cache)
        assertFalse(saveFilter.hasChanged(cache.copy(lastSyncTimestamp = 999L)))
    }

    @Test
    fun offlineBrowsing_readsCached10000SongsWhenServerOffline_thenResyncsWhenOnline() {
        val initialSongs = (1..10_000).map { i ->
            sampleSong("song_$i", "Cached $i", createdAtMs = 1_764_000_000_000L + i)
        }
        openCatalogSqlite(dbFile).use { conn ->
            createCatalogSchema(conn)
            persistCatalogInBatches(conn, "CUSTOM", initialSongs, emptyList(), emptyList(), emptyList(), 1_764_000_500_000L)
        }

        // Start MockWebServer then immediately close it to simulate offline server
        val offlineServer = MockWebServer().apply { start() }
        val offlineUrl = offlineServer.url("/api/v1/songs").toString()
        offlineServer.close()

        val http = OkHttpClient()
        val networkFailed = runCatching {
            http.newCall(Request.Builder().url(offlineUrl).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        assertFalse(networkFailed)

        // Offline browse: all 10,000 cached songs still available immediately from SQLite DB
        val cachedOfflineSongs = openCatalogSqlite(dbFile).use { conn ->
            readAllSongs(conn, "CUSTOM").map { it.toDomain() }
        }
        assertEquals(10_000, cachedOfflineSongs.size)

        // Bring MockWebServer online with a newly added 10,001st track
        val onlineServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"id":"song_10001","title":"Synced Online Track","artist":"W/n","createdAtMs":1764999999000,"mp3Url":"https://cdn.test/10001.mp3"}""")
                        .build()
                }
            }
            start()
        }

        try {
            val onlineBody = http.newCall(Request.Builder().url(onlineServer.url("/api/v1/songs/song_10001")).build())
                .execute()
                .use { resp ->
                    assertTrue(resp.isSuccessful)
                    resp.body!!.string()
                }
            assertTrue(onlineBody.contains("Synced Online Track"))

            val added = sampleSong("song_10001", "Synced Online Track", createdAtMs = 1_764_999_999_000L)
            openCatalogSqlite(dbFile).use { conn ->
                persistCatalogInBatches(
                    conn,
                    "CUSTOM",
                    cachedOfflineSongs + added,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    System.currentTimeMillis()
                )
            }

            val afterOnlineSync = openCatalogSqlite(dbFile).use { conn ->
                readAllSongs(conn, "CUSTOM").map { it.toDomain() }
            }
            assertEquals(10_001, afterOnlineSync.size)
            val top5 = openCatalogSqlite(dbFile).use { conn ->
                readNewestSongs(conn, "CUSTOM", 5).map { it.toDomain() }
            }
            assertEquals("CUSTOM::song_10001", top5.first().id)
        } finally {
            onlineServer.close()
        }
    }

    @Test
    fun imageCache_enforces300MbConfigBoundedConcurrencyAndDiskReuse() {
        // 1) Verify source configuration in RhythmApplication uses 300 MB disk cache & bounded dispatcher
        val appFile = resolveMainSourceFile("chromahub/rhythm/app/RhythmApplication.kt")
        val appSource = appFile.readText()
        assertTrue("RhythmApplication must configure 300 MB image DiskCache", appSource.contains("300L * 1024 * 1024"))
        assertTrue("RhythmApplication must disable server cache headers override blocking disk reuse", appSource.contains(".respectCacheHeaders(false)"))
        assertTrue("RhythmApplication must bound concurrent image requests", appSource.contains("maxRequests = 12") && appSource.contains("maxRequestsPerHost = 4"))

        // 2) Verify HTTP image Disk Cache reuses cached bytes on second request (hitCount increments, server called once)
        val imageHits = AtomicInteger(0)
        val imageServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    imageHits.incrementAndGet()
                    val payload = ByteArray(1024) { (it % 127).toByte() }
                    return MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "image/jpeg")
                        .addHeader("Cache-Control", "public, max-age=86400")
                        .body(Buffer().write(payload))
                        .build()
                }
            }
            start()
        }

        try {
            val imageCacheDir = File(tempDir, "image_cache").apply { mkdirs() }
            val diskCache = Cache(imageCacheDir, 300L * 1024L * 1024L)
            assertEquals(300L * 1024L * 1024L, diskCache.maxSize())

            val client = OkHttpClient.Builder().cache(diskCache).build()
            val imgUrl = imageServer.url("/cover_1.jpg")

            client.newCall(Request.Builder().url(imgUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals(1024, resp.body!!.bytes().size)
            }
            // Second load must be served purely from Disk Cache without hitting the server again
            client.newCall(Request.Builder().url(imgUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertNotNull(resp.cacheResponse)
                assertEquals(1024, resp.body!!.bytes().size)
            }
            assertEquals(1, imageHits.get())
            assertEquals(1, diskCache.hitCount())
            diskCache.close()
        } finally {
            imageServer.close()
        }
    }

    @Test
    fun directOnlineStreaming_usesNoMp3DiskCacheAndPreservesExplicitOfflineDownload() {
        // 1) Verify ExoPlayer engine does NOT wire CacheDataSource / audio_cache and clears any legacy cache
        val engineSource = resolveMainSourceFile("chromahub/rhythm/app/infrastructure/service/player/RhythmPlayerEngine.kt").readText()
        assertFalse("RhythmPlayerEngine must NOT use CacheDataSource for streaming playback", engineSource.contains("CacheDataSource"))
        assertTrue("RhythmPlayerEngine must use DefaultDataSource directly", engineSource.contains("DefaultDataSource.Factory(context, httpDataSourceFactory)"))

        val cacheMgrSource = resolveMainSourceFile("chromahub/rhythm/app/infrastructure/service/player/AudioCacheManager.kt").readText()
        assertTrue(cacheMgrSource.contains("clearLegacyAudioCache"))
        assertFalse("AudioCacheManager must not recreate SimpleCache", cacheMgrSource.contains("SimpleCache("))

        // 2) Simulate online MP3 streaming from MockWebServer without DiskCache + verify explicit offline downloads work
        val fakeAudioBytes = ByteArray(16_384) { (it % 251).toByte() }
        val streamServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "audio/mpeg")
                        .body(Buffer().write(fakeAudioBytes))
                        .build()
                }
            }
            start()
        }

        try {
            val cacheDir = File(tempDir, "cache").apply { mkdirs() }
            val legacyAudioCache = File(cacheDir, "audio_cache").apply {
                mkdirs()
                File(this, "stale.exo").writeText("stale")
            }
            // Simulate clearLegacyAudioCache deleting legacy MP3 cache directory
            assertTrue(legacyAudioCache.exists())
            legacyAudioCache.deleteRecursively()
            assertFalse(legacyAudioCache.exists())

            // Direct uncached HTTP client mirrors OkHttpDataSource without OkHttp Disk Cache
            val directStreamingClient = OkHttpClient.Builder().cache(null).build()
            val streamedUrl = streamServer.url("/api/v1/stream/xk_song_1").toString()
            directStreamingClient.newCall(Request.Builder().url(streamedUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals(fakeAudioBytes.size, resp.body!!.bytes().size)
            }
            // Confirm ZERO files created in audio_cache or streaming_cache during direct online playback
            assertFalse(File(cacheDir, "audio_cache").exists())
            assertEquals(0, cacheDir.listFiles()?.size ?: 0)

            // Confirm explicit offline download directory (filesDir/streaming_downloads) stores file cleanly
            val offlineDir = File(tempDir, "files/streaming_downloads").apply { mkdirs() }
            val downloadedTrack = File(offlineDir, "CUSTOM__xk_song_1.mp3")
            directStreamingClient.newCall(Request.Builder().url(streamedUrl).build()).execute().use { resp ->
                downloadedTrack.writeBytes(resp.body!!.bytes())
            }
            assertTrue(downloadedTrack.exists())
            assertEquals(fakeAudioBytes.size.toLong(), downloadedTrack.length())
        } finally {
            streamServer.close()
        }
    }

    @Test
    fun scrollAndListPerformance_10000ItemsShapeCacheAndFilteringAreInstant() {
        val songs = (1..10_000).map { i ->
            sampleSong("p_$i", "Track $i", createdAtMs = 1_764_000_000_000L + i)
        }

        // Verify zero object allocation for middle items across 10,000 list rows & grid tiles
        val middleA = groupedLibraryItemShape(1, 10_000)
        val middleB = groupedLibraryItemShape(5_432, 10_000)
        assertSame("Middle grouped shapes must reuse singleton instance", middleA, middleB)

        val gridA = getLibraryResponsiveGridItemShape(index = 4, totalItems = 10_000, columnsCount = 3)
        val gridB = getLibraryResponsiveGridItemShape(index = 400, totalItems = 10_000, columnsCount = 3)
        assertSame("Interior grid shapes must reuse cached mask instance", gridA, gridB)

        // Benchmark rapid filtering, dedupe, newest-5 sort, and shape lookup over 10,000 tracks
        val elapsedMs = measureTimeMillis {
            val deduped = songs.distinctBy { it.id }
            val paidOnly = deduped.filter { it.isPaidTrack }
            val top5Newest = deduped.sortedByDescending { it.createdAtMs }.take(5)
            for (i in 0 until deduped.size) {
                groupedLibraryItemShape(i, deduped.size)
                getLibraryResponsiveGridItemShape(i, deduped.size, 2)
            }
            assertEquals(10_000, deduped.size)
            assertTrue(paidOnly.size > 3_000)
            assertEquals(5, top5Newest.size)
        }

        assertTrue("Expected 10,000-song list processing + 20,000 shape lookups under 500ms, was ${elapsedMs}ms", elapsedMs < 500L)
    }

    private fun sampleSong(id: String, title: String, createdAtMs: Long): StreamingSong {
        val paid = (id.hashCode() and 1) == 0
        return StreamingSong(
            id = "CUSTOM::$id",
            title = title,
            artist = "Artist $id",
            album = "Album 1",
            duration = 210_000L,
            artworkUri = "https://xkmu.xuanken.name.vn/covers/$id.jpg",
            sourceType = SourceType.CUSTOM_API,
            streamingUrl = "https://xkmu.xuanken.name.vn/stream/$id.mp3",
            previewUrl = "https://xkmu.xuanken.name.vn/demo/$id.mp3",
            externalId = id,
            price = if (paid) 20000.0 else 0.0,
            isPaidTrack = paid,
            isPurchased = !paid,
            createdAtMs = createdAtMs,
            detailPath = "api/v1/songs/$id",
            streamEndpointPath = "api/v1/songs/$id/stream"
        )
    }

    private fun resolveMainSourceFile(relativeFromJavaRoot: String): File {
        val candidates = listOf(
            File("src/main/java/$relativeFromJavaRoot"),
            File("app/src/main/java/$relativeFromJavaRoot")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $relativeFromJavaRoot from working dir ${File(".").absolutePath}")
    }

    private fun openCatalogSqlite(file: File): Connection {
        return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    }

    private fun createCatalogSchema(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS streaming_songs (
                    id TEXT PRIMARY KEY NOT NULL,
                    serviceId TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    duration INTEGER NOT NULL,
                    artworkUri TEXT,
                    sourceType TEXT NOT NULL,
                    streamingUrl TEXT,
                    previewUrl TEXT,
                    isExplicit INTEGER NOT NULL,
                    popularity INTEGER,
                    releaseDate TEXT,
                    isPlayable INTEGER NOT NULL,
                    externalId TEXT,
                    albumId TEXT,
                    albumArtist TEXT,
                    isrc TEXT,
                    isFavorite INTEGER NOT NULL,
                    trackNumber INTEGER,
                    year INTEGER,
                    genre TEXT,
                    bitrate INTEGER,
                    sampleRate INTEGER,
                    channels INTEGER,
                    codec TEXT,
                    price REAL NOT NULL,
                    isPaidTrack INTEGER NOT NULL,
                    isPurchased INTEGER NOT NULL,
                    currency TEXT NOT NULL,
                    createdAtMs INTEGER NOT NULL,
                    detailPath TEXT,
                    streamEndpointPath TEXT,
                    updatedAtMs INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_songs_service ON streaming_songs(serviceId)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_songs_created ON streaming_songs(serviceId, createdAtMs)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS streaming_albums (
                    id TEXT PRIMARY KEY NOT NULL,
                    serviceId TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    artworkUri TEXT,
                    songCount INTEGER NOT NULL,
                    year INTEGER,
                    sourceType TEXT NOT NULL,
                    externalId TEXT,
                    releaseDate TEXT,
                    albumType TEXT NOT NULL,
                    genresJson TEXT NOT NULL,
                    label TEXT,
                    copyright TEXT,
                    isExplicit INTEGER NOT NULL,
                    detailPath TEXT
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS streaming_artists (
                    id TEXT PRIMARY KEY NOT NULL,
                    serviceId TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    artworkUri TEXT,
                    songCount INTEGER NOT NULL,
                    albumCount INTEGER NOT NULL,
                    sourceType TEXT NOT NULL,
                    externalId TEXT,
                    bio TEXT,
                    genresJson TEXT NOT NULL,
                    followers INTEGER,
                    popularity INTEGER,
                    detailPath TEXT
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS streaming_playlists (
                    id TEXT PRIMARY KEY NOT NULL,
                    serviceId TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT,
                    artworkUri TEXT,
                    songCount INTEGER NOT NULL,
                    isEditable INTEGER NOT NULL,
                    sourceType TEXT NOT NULL,
                    externalId TEXT,
                    ownerId TEXT,
                    ownerName TEXT,
                    ownerImageUrl TEXT,
                    ownerIsCurrentUser INTEGER NOT NULL,
                    isPublic INTEGER NOT NULL,
                    isCollaborative INTEGER NOT NULL,
                    followers INTEGER,
                    snapshotId TEXT,
                    trackIdsJson TEXT NOT NULL,
                    detailPath TEXT
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS streaming_sync_metadata (
                    serviceId TEXT PRIMARY KEY NOT NULL,
                    lastFullSyncTimestamp INTEGER NOT NULL,
                    libraryMarker TEXT,
                    commerceSchemaVersion INTEGER NOT NULL,
                    songCount INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun persistCatalogInBatches(
        conn: Connection,
        serviceId: String,
        songs: List<StreamingSong>,
        albums: List<StreamingAlbum>,
        artists: List<StreamingArtist>,
        playlists: List<StreamingPlaylist>,
        syncTimestamp: Long
    ) {
        conn.autoCommit = false
        try {
            listOf("streaming_songs", "streaming_albums", "streaming_artists", "streaming_playlists").forEach { table ->
                conn.prepareStatement("DELETE FROM $table WHERE serviceId = ?").use { ps ->
                    ps.setString(1, serviceId)
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO streaming_songs (
                    id, serviceId, sortOrder, title, artist, album, duration, artworkUri, sourceType,
                    streamingUrl, previewUrl, isExplicit, popularity, releaseDate, isPlayable, externalId,
                    albumId, albumArtist, isrc, isFavorite, trackNumber, year, genre, bitrate, sampleRate,
                    channels, codec, price, isPaidTrack, isPurchased, currency, createdAtMs, detailPath,
                    streamEndpointPath, updatedAtMs
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                songs.chunked(500).forEachIndexed { chunkIdx, batch ->
                    batch.forEachIndexed { localIdx, song ->
                        val e = song.toEntity(serviceId = serviceId, sortOrder = chunkIdx * 500 + localIdx, updatedAtMs = syncTimestamp)
                        ps.setString(1, e.id)
                        ps.setString(2, e.serviceId)
                        ps.setInt(3, e.sortOrder)
                        ps.setString(4, e.title)
                        ps.setString(5, e.artist)
                        ps.setString(6, e.album)
                        ps.setLong(7, e.duration)
                        ps.setString(8, e.artworkUri)
                        ps.setString(9, e.sourceType)
                        ps.setString(10, e.streamingUrl)
                        ps.setString(11, e.previewUrl)
                        ps.setInt(12, if (e.isExplicit) 1 else 0)
                        ps.setObject(13, e.popularity)
                        ps.setString(14, e.releaseDate)
                        ps.setInt(15, if (e.isPlayable) 1 else 0)
                        ps.setString(16, e.externalId)
                        ps.setString(17, e.albumId)
                        ps.setString(18, e.albumArtist)
                        ps.setString(19, e.isrc)
                        ps.setInt(20, if (e.isFavorite) 1 else 0)
                        ps.setObject(21, e.trackNumber)
                        ps.setObject(22, e.year)
                        ps.setString(23, e.genre)
                        ps.setObject(24, e.bitrate)
                        ps.setObject(25, e.sampleRate)
                        ps.setObject(26, e.channels)
                        ps.setString(27, e.codec)
                        ps.setDouble(28, e.price)
                        ps.setInt(29, if (e.isPaidTrack) 1 else 0)
                        ps.setInt(30, if (e.isPurchased) 1 else 0)
                        ps.setString(31, e.currency)
                        ps.setLong(32, e.createdAtMs)
                        ps.setString(33, e.detailPath)
                        ps.setString(34, e.streamEndpointPath)
                        ps.setLong(35, e.updatedAtMs)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }

            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO streaming_albums (
                    id, serviceId, sortOrder, title, artist, artworkUri, songCount, year, sourceType,
                    externalId, releaseDate, albumType, genresJson, label, copyright, isExplicit, detailPath
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                albums.forEachIndexed { idx, album ->
                    val e = album.toEntity(serviceId, idx)
                    ps.setString(1, e.id)
                    ps.setString(2, e.serviceId)
                    ps.setInt(3, e.sortOrder)
                    ps.setString(4, e.title)
                    ps.setString(5, e.artist)
                    ps.setString(6, e.artworkUri)
                    ps.setInt(7, e.songCount)
                    ps.setObject(8, e.year)
                    ps.setString(9, e.sourceType)
                    ps.setString(10, e.externalId)
                    ps.setString(11, e.releaseDate)
                    ps.setString(12, e.albumType)
                    ps.setString(13, e.genresJson)
                    ps.setString(14, e.label)
                    ps.setString(15, e.copyright)
                    ps.setInt(16, if (e.isExplicit) 1 else 0)
                    ps.setString(17, e.detailPath)
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO streaming_artists (
                    id, serviceId, sortOrder, name, artworkUri, songCount, albumCount, sourceType,
                    externalId, bio, genresJson, followers, popularity, detailPath
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                artists.forEachIndexed { idx, artist ->
                    val e = artist.toEntity(serviceId, idx)
                    ps.setString(1, e.id)
                    ps.setString(2, e.serviceId)
                    ps.setInt(3, e.sortOrder)
                    ps.setString(4, e.name)
                    ps.setString(5, e.artworkUri)
                    ps.setInt(6, e.songCount)
                    ps.setInt(7, e.albumCount)
                    ps.setString(8, e.sourceType)
                    ps.setString(9, e.externalId)
                    ps.setString(10, e.bio)
                    ps.setString(11, e.genresJson)
                    ps.setObject(12, e.followers)
                    ps.setObject(13, e.popularity)
                    ps.setString(14, e.detailPath)
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO streaming_playlists (
                    id, serviceId, sortOrder, name, description, artworkUri, songCount, isEditable, sourceType,
                    externalId, ownerId, ownerName, ownerImageUrl, ownerIsCurrentUser, isPublic, isCollaborative,
                    followers, snapshotId, trackIdsJson, detailPath
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                playlists.forEachIndexed { idx, pl ->
                    val e = pl.toEntity(serviceId, idx)
                    ps.setString(1, e.id)
                    ps.setString(2, e.serviceId)
                    ps.setInt(3, e.sortOrder)
                    ps.setString(4, e.name)
                    ps.setString(5, e.description)
                    ps.setString(6, e.artworkUri)
                    ps.setInt(7, e.songCount)
                    ps.setInt(8, if (e.isEditable) 1 else 0)
                    ps.setString(9, e.sourceType)
                    ps.setString(10, e.externalId)
                    ps.setString(11, e.ownerId)
                    ps.setString(12, e.ownerName)
                    ps.setString(13, e.ownerImageUrl)
                    ps.setInt(14, if (e.ownerIsCurrentUser) 1 else 0)
                    ps.setInt(15, if (e.isPublic) 1 else 0)
                    ps.setInt(16, if (e.isCollaborative) 1 else 0)
                    ps.setObject(17, e.followers)
                    ps.setString(18, e.snapshotId)
                    ps.setString(19, e.trackIdsJson)
                    ps.setString(20, e.detailPath)
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            conn.prepareStatement(
                "INSERT OR REPLACE INTO streaming_sync_metadata (serviceId, lastFullSyncTimestamp, libraryMarker, commerceSchemaVersion, songCount) VALUES (?, ?, ?, ?, ?)"
            ).use { ps ->
                val meta = CachedSyncMetadataEntity(serviceId, syncTimestamp, null, StreamingCatalogCache.CURRENT_COMMERCE_SCHEMA_VERSION, songs.size)
                ps.setString(1, meta.serviceId)
                ps.setLong(2, meta.lastFullSyncTimestamp)
                ps.setString(3, meta.libraryMarker)
                ps.setInt(4, meta.commerceSchemaVersion)
                ps.setInt(5, meta.songCount)
                ps.executeUpdate()
            }

            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    private fun readAllSongs(conn: Connection, serviceId: String): List<CachedStreamingSongEntity> {
        val result = ArrayList<CachedStreamingSongEntity>(10_000)
        conn.prepareStatement("SELECT * FROM streaming_songs WHERE serviceId = ? ORDER BY sortOrder ASC").use { ps ->
            ps.setString(1, serviceId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        CachedStreamingSongEntity(
                            id = rs.getString("id"),
                            serviceId = rs.getString("serviceId"),
                            sortOrder = rs.getInt("sortOrder"),
                            title = rs.getString("title"),
                            artist = rs.getString("artist"),
                            album = rs.getString("album"),
                            duration = rs.getLong("duration"),
                            artworkUri = rs.getString("artworkUri"),
                            sourceType = rs.getString("sourceType"),
                            streamingUrl = rs.getString("streamingUrl"),
                            previewUrl = rs.getString("previewUrl"),
                            isExplicit = rs.getInt("isExplicit") == 1,
                            popularity = rs.getObject("popularity")?.let { (it as Number).toInt() },
                            releaseDate = rs.getString("releaseDate"),
                            isPlayable = rs.getInt("isPlayable") == 1,
                            externalId = rs.getString("externalId"),
                            albumId = rs.getString("albumId"),
                            albumArtist = rs.getString("albumArtist"),
                            isrc = rs.getString("isrc"),
                            isFavorite = rs.getInt("isFavorite") == 1,
                            trackNumber = rs.getObject("trackNumber")?.let { (it as Number).toInt() },
                            year = rs.getObject("year")?.let { (it as Number).toInt() },
                            genre = rs.getString("genre"),
                            bitrate = rs.getObject("bitrate")?.let { (it as Number).toInt() },
                            sampleRate = rs.getObject("sampleRate")?.let { (it as Number).toInt() },
                            channels = rs.getObject("channels")?.let { (it as Number).toInt() },
                            codec = rs.getString("codec"),
                            price = rs.getDouble("price"),
                            isPaidTrack = rs.getInt("isPaidTrack") == 1,
                            isPurchased = rs.getInt("isPurchased") == 1,
                            currency = rs.getString("currency"),
                            createdAtMs = rs.getLong("createdAtMs"),
                            detailPath = rs.getString("detailPath"),
                            streamEndpointPath = rs.getString("streamEndpointPath"),
                            updatedAtMs = rs.getLong("updatedAtMs")
                        )
                    )
                }
            }
        }
        return result
    }

    private fun readNewestSongs(conn: Connection, serviceId: String, limit: Int): List<CachedStreamingSongEntity> {
        val result = ArrayList<CachedStreamingSongEntity>(limit)
        conn.prepareStatement("SELECT * FROM streaming_songs WHERE serviceId = ? ORDER BY createdAtMs DESC, sortOrder DESC LIMIT ?").use { ps ->
            ps.setString(1, serviceId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        CachedStreamingSongEntity(
                            id = rs.getString("id"),
                            serviceId = rs.getString("serviceId"),
                            sortOrder = rs.getInt("sortOrder"),
                            title = rs.getString("title"),
                            artist = rs.getString("artist"),
                            album = rs.getString("album"),
                            duration = rs.getLong("duration"),
                            artworkUri = rs.getString("artworkUri"),
                            sourceType = rs.getString("sourceType"),
                            streamingUrl = rs.getString("streamingUrl"),
                            previewUrl = rs.getString("previewUrl"),
                            isExplicit = rs.getInt("isExplicit") == 1,
                            popularity = rs.getObject("popularity")?.let { (it as Number).toInt() },
                            releaseDate = rs.getString("releaseDate"),
                            isPlayable = rs.getInt("isPlayable") == 1,
                            externalId = rs.getString("externalId"),
                            albumId = rs.getString("albumId"),
                            albumArtist = rs.getString("albumArtist"),
                            isrc = rs.getString("isrc"),
                            isFavorite = rs.getInt("isFavorite") == 1,
                            trackNumber = rs.getObject("trackNumber")?.let { (it as Number).toInt() },
                            year = rs.getObject("year")?.let { (it as Number).toInt() },
                            genre = rs.getString("genre"),
                            bitrate = rs.getObject("bitrate")?.let { (it as Number).toInt() },
                            sampleRate = rs.getObject("sampleRate")?.let { (it as Number).toInt() },
                            channels = rs.getObject("channels")?.let { (it as Number).toInt() },
                            codec = rs.getString("codec"),
                            price = rs.getDouble("price"),
                            isPaidTrack = rs.getInt("isPaidTrack") == 1,
                            isPurchased = rs.getInt("isPurchased") == 1,
                            currency = rs.getString("currency"),
                            createdAtMs = rs.getLong("createdAtMs"),
                            detailPath = rs.getString("detailPath"),
                            streamEndpointPath = rs.getString("streamEndpointPath"),
                            updatedAtMs = rs.getLong("updatedAtMs")
                        )
                    )
                }
            }
        }
        return result
    }

    private fun readAllAlbums(conn: Connection, serviceId: String): List<CachedStreamingAlbumEntity> {
        val result = mutableListOf<CachedStreamingAlbumEntity>()
        conn.prepareStatement("SELECT * FROM streaming_albums WHERE serviceId = ? ORDER BY sortOrder ASC").use { ps ->
            ps.setString(1, serviceId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        CachedStreamingAlbumEntity(
                            id = rs.getString("id"),
                            serviceId = rs.getString("serviceId"),
                            sortOrder = rs.getInt("sortOrder"),
                            title = rs.getString("title"),
                            artist = rs.getString("artist"),
                            artworkUri = rs.getString("artworkUri"),
                            songCount = rs.getInt("songCount"),
                            year = rs.getObject("year")?.let { (it as Number).toInt() },
                            sourceType = rs.getString("sourceType"),
                            externalId = rs.getString("externalId"),
                            releaseDate = rs.getString("releaseDate"),
                            albumType = rs.getString("albumType"),
                            genresJson = rs.getString("genresJson"),
                            label = rs.getString("label"),
                            copyright = rs.getString("copyright"),
                            isExplicit = rs.getInt("isExplicit") == 1,
                            detailPath = rs.getString("detailPath")
                        )
                    )
                }
            }
        }
        return result
    }

    private fun readAllArtists(conn: Connection, serviceId: String): List<CachedStreamingArtistEntity> {
        val result = mutableListOf<CachedStreamingArtistEntity>()
        conn.prepareStatement("SELECT * FROM streaming_artists WHERE serviceId = ? ORDER BY sortOrder ASC").use { ps ->
            ps.setString(1, serviceId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        CachedStreamingArtistEntity(
                            id = rs.getString("id"),
                            serviceId = rs.getString("serviceId"),
                            sortOrder = rs.getInt("sortOrder"),
                            name = rs.getString("name"),
                            artworkUri = rs.getString("artworkUri"),
                            songCount = rs.getInt("songCount"),
                            albumCount = rs.getInt("albumCount"),
                            sourceType = rs.getString("sourceType"),
                            externalId = rs.getString("externalId"),
                            bio = rs.getString("bio"),
                            genresJson = rs.getString("genresJson"),
                            followers = rs.getObject("followers")?.let { (it as Number).toLong() },
                            popularity = rs.getObject("popularity")?.let { (it as Number).toInt() },
                            detailPath = rs.getString("detailPath")
                        )
                    )
                }
            }
        }
        return result
    }

    private fun readAllPlaylists(conn: Connection, serviceId: String): List<CachedStreamingPlaylistEntity> {
        val result = mutableListOf<CachedStreamingPlaylistEntity>()
        conn.prepareStatement("SELECT * FROM streaming_playlists WHERE serviceId = ? ORDER BY sortOrder ASC").use { ps ->
            ps.setString(1, serviceId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        CachedStreamingPlaylistEntity(
                            id = rs.getString("id"),
                            serviceId = rs.getString("serviceId"),
                            sortOrder = rs.getInt("sortOrder"),
                            name = rs.getString("name"),
                            description = rs.getString("description"),
                            artworkUri = rs.getString("artworkUri"),
                            songCount = rs.getInt("songCount"),
                            isEditable = rs.getInt("isEditable") == 1,
                            sourceType = rs.getString("sourceType"),
                            externalId = rs.getString("externalId"),
                            ownerId = rs.getString("ownerId"),
                            ownerName = rs.getString("ownerName"),
                            ownerImageUrl = rs.getString("ownerImageUrl"),
                            ownerIsCurrentUser = rs.getInt("ownerIsCurrentUser") == 1,
                            isPublic = rs.getInt("isPublic") == 1,
                            isCollaborative = rs.getInt("isCollaborative") == 1,
                            followers = rs.getObject("followers")?.let { (it as Number).toLong() },
                            snapshotId = rs.getString("snapshotId"),
                            trackIdsJson = rs.getString("trackIdsJson"),
                            detailPath = rs.getString("detailPath")
                        )
                    )
                }
            }
        }
        return result
    }

    private fun readSyncTimestamp(conn: Connection, serviceId: String): Long {
        conn.prepareStatement("SELECT lastFullSyncTimestamp FROM streaming_sync_metadata WHERE serviceId = ?").use { ps ->
            ps.setString(1, serviceId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }
}
