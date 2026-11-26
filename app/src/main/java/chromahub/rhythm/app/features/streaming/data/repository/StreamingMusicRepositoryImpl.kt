/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.repository

import android.content.Context
import android.util.Log
import chromahub.rhythm.app.core.domain.model.AlbumItem
import chromahub.rhythm.app.core.domain.model.ArtistItem
import chromahub.rhythm.app.core.domain.model.PlayableItem
import chromahub.rhythm.app.core.domain.model.PlaylistItem
import chromahub.rhythm.app.core.domain.model.SourceType
import chromahub.rhythm.app.core.utils.NetworkUtils
import chromahub.rhythm.app.features.streaming.data.provider.BlockedSongItem
import chromahub.rhythm.app.features.streaming.data.provider.CloudListeningStats
import chromahub.rhythm.app.features.streaming.data.provider.CustomSearchPayload
import chromahub.rhythm.app.features.streaming.data.provider.CustomSiteInfo
import chromahub.rhythm.app.features.streaming.data.provider.CustomSongMeta
import chromahub.rhythm.app.features.streaming.data.provider.CustomUserProfile
import chromahub.rhythm.app.features.streaming.data.provider.CustomUserSettings
import chromahub.rhythm.app.features.streaming.data.provider.ProviderAlbum
import chromahub.rhythm.app.features.streaming.data.provider.ProviderArtist
import chromahub.rhythm.app.features.streaming.data.provider.ProviderPlaylist
import chromahub.rhythm.app.features.streaming.data.provider.ProviderSong
import chromahub.rhythm.app.features.streaming.data.provider.PurchaseRecord
import chromahub.rhythm.app.features.streaming.data.provider.UserTrustManager
import chromahub.rhythm.app.features.streaming.data.provider.VoucherInfo
import chromahub.rhythm.app.features.streaming.data.provider.VoucherValidationResult
import chromahub.rhythm.app.features.streaming.data.provider.XuanKenFirebaseClient
import chromahub.rhythm.app.features.streaming.domain.model.BrowseCategory
import chromahub.rhythm.app.features.streaming.domain.model.StreamingAlbum
import chromahub.rhythm.app.features.streaming.domain.model.StreamingArtist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingPlaylist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingServiceId
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.features.streaming.domain.repository.StreamingMusicRepository
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.network.NetworkClient
import chromahub.rhythm.app.network.DeezerApiService
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import chromahub.rhythm.app.util.ArtistSeparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.drop

/**
 * Data model for persisting the streaming catalog to disk.
 */
data class StreamingCatalogCache(
    val serviceId: String,
    val songs: List<StreamingSong> = emptyList(),
    val albums: List<StreamingAlbum> = emptyList(),
    val artists: List<StreamingArtist> = emptyList(),
    val playlists: List<StreamingPlaylist> = emptyList(),
    val likedSongIds: List<String> = emptyList(),
    val lastSyncTimestamp: Long = 0L,
    /** Server library marker the songs were fetched under, or null if not known to be current. */
    val libraryMarker: String? = null,
    val commerceSchemaVersion: Int = CURRENT_COMMERCE_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_COMMERCE_SCHEMA_VERSION = 3
    }
}

/**
 * Provider-backed implementation used by Rhythm GO mode.
 */
class StreamingMusicRepositoryImpl(
    private val context: Context,
    private val localCatalogStore: chromahub.rhythm.app.features.streaming.data.local.StreamingCatalogLocalStore =
        chromahub.rhythm.app.features.streaming.data.local.RoomStreamingCatalogStore(context)
) : StreamingMusicRepository {

    data class ServiceConnectionInfo(
        val displayName: String,
        val serverUrl: String
    )

    private val appSettings = AppSettings.getInstance(context)

    private val firebaseClient = XuanKenFirebaseClient.getInstance(context)

    private val songsFlow = MutableStateFlow<List<PlayableItem>>(emptyList())
    private val albumsFlow = MutableStateFlow<List<AlbumItem>>(emptyList())
    private val providerAlbumsFlow = MutableStateFlow<List<StreamingAlbum>>(emptyList())  // Keep provider albums separate
    private val artistsFlow = MutableStateFlow<List<ArtistItem>>(emptyList())
    private val playlistsFlow = MutableStateFlow<List<PlaylistItem>>(emptyList())

    private val likedSongsFlow = MutableStateFlow<List<StreamingSong>>(emptyList())
    private val followedArtistsFlow = MutableStateFlow<List<StreamingArtist>>(emptyList())
    private val savedAlbumsFlow = MutableStateFlow<List<StreamingAlbum>>(emptyList())
    private val downloadedSongsFlow = MutableStateFlow<List<StreamingSong>>(emptyList())

    private val likedSongIds = linkedSetOf<String>()
    private val blockedSongIds = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val followedArtistIds = linkedSetOf<String>()
    private val savedAlbumIds = linkedSetOf<String>()
    private val followedPlaylistIds = linkedSetOf<String>()

    private val songCache = LinkedHashMap<String, StreamingSong>()
    private val starredSongCache = LinkedHashMap<String, StreamingSong>()
    @Volatile
    private var lastSearchPayload: Triple<String, Long, CustomSearchPayload>? = null
    // Read from Default/IO dispatchers (catalog grouping, Deezer enrichment) and written from
    // several coroutines, so it must be thread-safe.
    private val artistArtworkCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val providerAlbumCache = LinkedHashMap<String, StreamingAlbum>()

    private val downloadDirectory by lazy {
        java.io.File(context.filesDir, "streaming_downloads").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    // Deezer artist-image lookups: cached (memory + disk, with TTL) so each artist is looked up
    // once rather than on every catalog replace/merge; one enrichment pass runs at a time.
    private val deezerArtistImageCache by lazy {
        DeezerArtistImageCache(java.io.File(context.cacheDir, "deezer_artist_images.json"))
    }
    private val deezerEnrichmentMutex = Mutex()
    @Volatile
    private var artistEnrichmentJob: Job? = null

    private val downloadedSongsMap = LinkedHashMap<String, StreamingSong>()
    private val gson = com.google.gson.Gson()
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val catalogCacheWriter = CatalogCacheWriter()

    /**
     * A sync or start-up requests several saves within seconds (catalog, liked songs, playlists,
     * artist images); each rewrites the whole cache, tens of MB for a large library. Merge them.
     */
    private val catalogSaveCoalescer = SaveCoalescer(repositoryScope, delayMs = 3_000L)

    /** The catalog cache is loaded on IO, never where the repository is created (main thread). */
    private val initialCatalogLoad: BackgroundLoad

    /**
     * One catalog sync at a time: several triggers (start-up, network changes, screens) can
     * request one together, and a request that arrives during a sync joins it.
     */
    private val catalogSync = SingleFlight<List<StreamingSong>>()

    /** The cold-start library change check ([isCatalogOutdated]) runs once per process. */
    private val coldStartCatalogCheck = OnceGate()

    /** Skips rewriting the catalog cache when its content did not change. */
    private val catalogSaveFilter = CatalogSaveFilter()

    /**
     * Server library marker ([SubsonicApiClient.getLibraryMarker]) of the catalog in memory, or
     * null if that catalog is not known to match the server (partial fetch, scan running, etc.).
     */
    @Volatile
    private var catalogLibraryMarker: String? = null

    @Volatile
    private var loadedCacheNeedsCommerceRefresh: Boolean = false

    @Volatile
    private var lastLoadedSyncTimestamp: Long = 0L

    init {
        loadDownloadedSongsIndex()
        // Started here, once the fields the load uses are initialized.
        initialCatalogLoad = BackgroundLoad(repositoryScope) { loadCatalogCacheForActiveService() }
        repositoryScope.launch {
            initialCatalogLoad.await()
            // The current service was just loaded; reload only when it changes.
            appSettings.streamingService.drop(1).collect { serviceId ->
                loadCatalogCacheForActiveService(normalizeServiceId(serviceId))
            }
        }
    }

    override suspend fun awaitCatalogCacheLoaded() = initialCatalogLoad.await()

    private fun loadDownloadedSongsIndex() {
        try {
            val indexFile = java.io.File(downloadDirectory, "downloaded_songs.json")
            if (indexFile.exists()) {
                val json = indexFile.readText()
                val type = object : com.google.gson.reflect.TypeToken<List<StreamingSong>>() {}.type
                val list: List<StreamingSong> = gson.fromJson(json, type) ?: emptyList()
                downloadedSongsMap.clear()
                val reconciledList = mutableListOf<StreamingSong>()
                list.forEach { song ->
                    val file = getDownloadFile(song.id)
                    if (file.exists() && file.length() > 0) {
                        val safeSongId = song.id.replace(":", "_").replace("/", "_").replace("\\", "_")
                        val songArtFile = java.io.File(downloadDirectory, "${safeSongId}_art.jpg")
                        val safeAlbumKey = (song.albumId ?: "${song.artist}_${song.album}")
                            .replace(":", "_").replace("/", "_").replace("\\", "_").replace(" ", "_").lowercase()
                        val albumArtFile = java.io.File(downloadDirectory, "${safeAlbumKey}_album_art.jpg")

                        val resolvedArtworkUri = when {
                            albumArtFile.exists() && albumArtFile.length() > 0 -> Uri.fromFile(albumArtFile).toString()
                            songArtFile.exists() && songArtFile.length() > 0 -> Uri.fromFile(songArtFile).toString()
                            song.artworkUri?.startsWith("file:") == true -> song.artworkUri
                            else -> song.artworkUri
                        }

                        val localSong = song.copy(
                            streamingUrl = Uri.fromFile(file).toString(),
                            artworkUri = resolvedArtworkUri
                        )
                        downloadedSongsMap[song.id] = localSong
                        reconciledList.add(localSong)
                    }
                }
                downloadedSongsFlow.value = reconciledList
                if (reconciledList.size != list.size) {
                    saveDownloadedSongsIndex()
                }
            } else {
                downloadedSongsMap.clear()
                downloadedSongsFlow.value = emptyList()
            }
        } catch (e: Exception) {
            Log.e("StreamingMusicRepo", "Error loading downloaded songs index", e)
        }
    }

    private fun saveDownloadedSongsIndex() {
        val list = downloadedSongsMap.values.toList()
        downloadedSongsFlow.value = list
        try {
            val indexFile = java.io.File(downloadDirectory, "downloaded_songs.json")
            val json = gson.toJson(list)
            indexFile.writeText(json)
        } catch (e: Exception) {
            Log.e("StreamingMusicRepo", "Error saving downloaded songs index", e)
        }
    }

    private val audioExtensions = listOf(".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".wma", ".webm")

    fun getDownloadFile(songId: String, extension: String = ".mp3"): java.io.File {
        val safeName = songId.replace(":", "_").replace("/", "_").replace("\\", "_")
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        val exactFile = java.io.File(downloadDirectory, "$safeName$ext")
        if (exactFile.exists() && exactFile.length() > 0) return exactFile

        for (candidateExt in audioExtensions) {
            val candidate = java.io.File(downloadDirectory, "$safeName$candidateExt")
            if (candidate.exists() && candidate.length() > 0) {
                return candidate
            }
        }
        return exactFile
    }

    private fun extractExtension(contentDisposition: String?, contentType: String?): String {
        if (!contentDisposition.isNullOrBlank()) {
            val filenameMatch = Regex("""filename\*?=['"]?(?:UTF-\d['"]*)?([^'";\n]+)['"]?""", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
            val filename = filenameMatch?.groupValues?.get(1)?.trim()
            if (!filename.isNullOrBlank() && filename.contains(".")) {
                val ext = "." + filename.substringAfterLast(".").lowercase()
                if (ext in audioExtensions) return ext
            }
        }
        if (!contentType.isNullOrBlank()) {
            val mime = contentType.substringBefore(";").trim().lowercase()
            return when (mime) {
                "audio/flac", "audio/x-flac" -> ".flac"
                "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/x-aac" -> ".m4a"
                "audio/ogg", "audio/vorbis", "application/ogg" -> ".ogg"
                "audio/opus" -> ".opus"
                "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"
                "audio/webm" -> ".webm"
                "audio/mpeg", "audio/mp3" -> ".mp3"
                else -> ".mp3"
            }
        }
        return ".mp3"
    }

    private fun catalogCacheCodec(serviceId: String): CatalogCacheCodec {
        return CatalogCacheCodec()
    }

    fun getCatalogCacheFile(serviceId: String): java.io.File {
        return java.io.File(context.filesDir, "streaming_catalog_${serviceId}.json")
    }

    /** Progress of an interrupted full library fetch (see [ResumableLibraryFetch]). */
    private fun getLibraryFetchCheckpointDir(serviceId: String): java.io.File {
        return java.io.File(context.filesDir, "streaming_catalog_${serviceId}.partial")
    }

    private fun loadCatalogCacheForActiveService(targetServiceId: String? = null) {
        try {
            val serviceId = targetServiceId ?: activeServiceId()
            catalogLibraryMarker = null
            lastLoadedSyncTimestamp = 0L

            var cache: StreamingCatalogCache? = kotlinx.coroutines.runBlocking {
                localCatalogStore.loadCatalog(serviceId)
            }
            var migratedFromLegacyJson = false
            if (cache == null || cache.songs.isEmpty()) {
                val cacheFile = getCatalogCacheFile(serviceId)
                if (cacheFile.exists()) {
                    val legacy = catalogCacheCodec(serviceId).readFile(cacheFile)
                    if (legacy != null && legacy.serviceId == serviceId && legacy.songs.isNotEmpty()) {
                        cache = legacy
                        migratedFromLegacyJson = true
                    }
                }
            }
            val resolvedCache = cache ?: return
            if (resolvedCache.serviceId != serviceId || resolvedCache.songs.isEmpty()) return

            val purchasedIds = firebaseClient.getCachedPurchasedSongIds()
            val isAdmin = firebaseClient.isAdmin()
            loadedCacheNeedsCommerceRefresh =
                resolvedCache.commerceSchemaVersion < StreamingCatalogCache.CURRENT_COMMERCE_SCHEMA_VERSION
            lastLoadedSyncTimestamp = resolvedCache.lastSyncTimestamp

            val reconciledSongs = resolvedCache.songs.map { song ->
                val cleanId = decodeSongId(song.id)?.second ?: song.externalId ?: song.id.substringAfterLast("::")
                val effectivePaid = song.isPaidTrack || song.price > 0.0
                val effectivePurchased = !effectivePaid || cleanId in purchasedIds || isAdmin || song.isPurchased
                val effectiveLiked = song.isFavorite || firebaseClient.isSongLiked(cleanId)
                if (effectivePaid != song.isPaidTrack || effectivePurchased != song.isPurchased || effectiveLiked != song.isFavorite) {
                    song.copy(
                        isPaidTrack = effectivePaid,
                        isPurchased = effectivePurchased,
                        isFavorite = effectiveLiked
                    )
                } else {
                    song
                }
            }

            songCache.clear()
            reconciledSongs.forEach { song ->
                songCache[song.id] = song
            }
            songsFlow.value = reconciledSongs
            catalogLibraryMarker = resolvedCache.libraryMarker
            catalogSaveFilter.remember(resolvedCache)

            if (resolvedCache.albums.isNotEmpty()) {
                providerAlbumCache.clear()
                resolvedCache.albums.forEach { album ->
                    providerAlbumCache[album.id] = album
                }
                providerAlbumsFlow.value = resolvedCache.albums
                albumsFlow.value = resolvedCache.albums
            } else {
                albumsFlow.value = buildAlbumItems(serviceId, reconciledSongs)
            }

            if (resolvedCache.artists.isNotEmpty()) {
                artistsFlow.value = resolvedCache.artists
            } else {
                artistsFlow.value = buildArtistItems(serviceId, reconciledSongs)
            }

            if (resolvedCache.playlists.isNotEmpty()) {
                playlistsFlow.value = resolvedCache.playlists
            }

            if (resolvedCache.likedSongIds.isNotEmpty()) {
                likedSongIds.clear()
                likedSongIds.addAll(resolvedCache.likedSongIds)
            } else {
                likedSongIds.clear()
                reconciledSongs.filter { it.isFavorite }.forEach { likedSongIds.add(it.id) }
            }

            updateLikedSongsFlow()
            updateSavedAlbumsFlow()
            updateFollowedArtistsFlow()

            if (migratedFromLegacyJson) {
                repositoryScope.launch {
                    runCatching {
                        localCatalogStore.saveCatalog(resolvedCache.copy(songs = reconciledSongs))
                    }
                }
            }
            Log.d("StreamingMusicRepo", "Loaded cached catalog from Room for $serviceId with ${reconciledSongs.size} songs")
        } catch (e: Exception) {
            Log.e("StreamingMusicRepo", "Error loading streaming catalog cache", e)
        }
    }

    private fun saveCatalogCache(serviceId: String = activeServiceId()) {
        if (appSettings.offlineMode.value) return
        catalogSaveCoalescer.request(serviceId) {
            try {
                val currentSongs = songsFlow.value.filterIsInstance<StreamingSong>()
                if (currentSongs.isEmpty()) return@request

                val currentAlbums = (providerAlbumsFlow.value.ifEmpty { albumsFlow.value })
                    .filterIsInstance<StreamingAlbum>()
                val currentArtists = artistsFlow.value.filterIsInstance<StreamingArtist>()
                val currentPlaylists = playlistsFlow.value.filterIsInstance<StreamingPlaylist>()
                val currentLikedIds = likedSongIds.toList()
                val now = System.currentTimeMillis()

                val cache = StreamingCatalogCache(
                    serviceId = serviceId,
                    songs = currentSongs,
                    albums = currentAlbums,
                    artists = currentArtists,
                    playlists = currentPlaylists,
                    likedSongIds = currentLikedIds,
                    lastSyncTimestamp = now,
                    libraryMarker = catalogLibraryMarker
                )
                if (!catalogSaveFilter.hasChanged(cache)) return@request

                localCatalogStore.saveCatalog(cache)
                lastLoadedSyncTimestamp = now
                catalogSaveFilter.remember(cache)
                Log.d("StreamingMusicRepo", "Saved streaming catalog cache to Room for $serviceId (${currentSongs.size} songs)")
            } catch (e: Exception) {
                Log.e("StreamingMusicRepo", "Error saving streaming catalog cache for $serviceId", e)
            }
        }
    }

    override fun hasCachedCatalog(serviceId: String?): Boolean {
        val targetService = serviceId ?: activeServiceId()
        if (songsFlow.value.isNotEmpty() && activeServiceId() == targetService) {
            return true
        }
        if (kotlinx.coroutines.runBlocking { localCatalogStore.hasCachedCatalog(targetService) }) {
            return true
        }
        val cacheFile = getCatalogCacheFile(targetService)
        return cacheFile.exists() && cacheFile.length() > 0
    }

    override val currentService: SourceType
        get() = serviceToSourceType(activeServiceId())

    suspend fun connect(
        serviceId: String,
        serverUrl: String,
        username: String,
        password: String,
        saveCredentials: Boolean = true
    ): ServiceConnectionInfo {
        val normalizedService = normalizeServiceId(serviceId)
        val result = firebaseClient.login(username, password, saveCredentials)
        val connection = result.getOrElse { throw it }
        ResumableLibraryFetch.discard(getLibraryFetchCheckpointDir(normalizedService))
        reconcileSongCommerceInMemory()
        return ServiceConnectionInfo(
            displayName = connection.displayName,
            serverUrl = connection.serverUrl
        )
    }

    private fun reconcileSongCommerceInMemory() {
        val currentSongs = songsFlow.value.filterIsInstance<StreamingSong>()
        if (currentSongs.isEmpty()) return
        val purchasedIds = firebaseClient.getCachedPurchasedSongIds()
        val isAdmin = firebaseClient.isAdmin()
        val updated = currentSongs.map { song ->
            val cleanId = decodeSongId(song.id)?.second ?: song.externalId ?: song.id.substringAfterLast("::")
            val effectivePaid = song.isPaidTrack || song.price > 0.0
            val effectivePurchased = !effectivePaid || cleanId in purchasedIds || isAdmin
            val effectiveLiked = firebaseClient.isSongLiked(cleanId) || song.isFavorite
            song.copy(
                isPaidTrack = effectivePaid,
                isPurchased = effectivePurchased,
                isFavorite = effectiveLiked
            )
        }
        updated.forEach { songCache[it.id] = it }
        songsFlow.value = updated
        saveCatalogCache(activeServiceId())
    }

    suspend fun register(
        serviceId: String,
        email: String,
        password: String,
        displayName: String,
        saveCredentials: Boolean = true
    ): ServiceConnectionInfo {
        val normalizedService = normalizeServiceId(serviceId)
        val result = firebaseClient.register(email, password, displayName, saveCredentials)
        val connection = result.getOrElse { throw it }
        ResumableLibraryFetch.discard(getLibraryFetchCheckpointDir(normalizedService))
        return ServiceConnectionInfo(
            displayName = connection.displayName,
            serverUrl = connection.serverUrl
        )
    }

    suspend fun disconnect(serviceId: String) {
        val normalized = normalizeServiceId(serviceId)
        firebaseClient.logout()
        lastSearchPayload = null
        blockedSongIds.clear()

        try {
            catalogCacheWriter.delete(getCatalogCacheFile(normalized))
            repositoryScope.launch { runCatching { localCatalogStore.clearService(normalized) } }
            catalogSaveFilter.remember(null)
            ResumableLibraryFetch.discard(getLibraryFetchCheckpointDir(normalized))
        } catch (e: Exception) {
            Log.e("StreamingMusicRepo", "Error deleting catalog cache on disconnect", e)
        }

        if (activeServiceId() == normalized) {
            clearInMemoryCatalog()
        }
    }

    fun isServiceConnected(serviceId: String): Boolean {
        return normalizeServiceId(serviceId) == StreamingServiceId.CUSTOM_API && firebaseClient.isConnected()
    }

    override suspend fun isAuthenticated(): Boolean {
        return isServiceConnected(activeServiceId())
    }

    override suspend fun authenticate(): Boolean {
        return firebaseClient.ping().isSuccess
    }

    override suspend fun logout() {
        disconnect(activeServiceId())
    }

    private suspend fun getCachedOrFetchSearch(query: String): CustomSearchPayload? {
        val trimmed = query.trim()
        val cached = lastSearchPayload
        val now = System.currentTimeMillis()
        if (cached != null && cached.first == trimmed && now - cached.second < 5_000L) {
            return cached.third
        }
        val fetched = firebaseClient.search(trimmed).getOrNull() ?: return null
        lastSearchPayload = Triple(trimmed, now, fetched)
        return fetched
    }

    private fun isBlocked(providerOrEncodedSongId: String): Boolean {
        val clean = providerOrEncodedSongId.substringAfterLast("::")
        return blockedSongIds.contains(clean) || blockedSongIds.contains(providerOrEncodedSongId)
    }

    override suspend fun getRecommendations(limit: Int): List<StreamingSong> {
        if (appSettings.offlineMode.value) {
            return downloadedSongsMap.values.shuffled().take(limit.coerceAtLeast(1))
        }
        val serviceId = activeServiceId()
        if (isServiceConnected(serviceId)) {
            val publicSongs = firebaseClient.getPublicSongs().getOrElse { emptyList() }
            val mapped = publicSongs
                .filterNot { isBlocked(it.providerId) }
                .map { mapProviderSong(serviceId, it) }
                .take(limit.coerceAtLeast(1))
            if (mapped.isNotEmpty()) {
                mapped.forEach { songCache[it.id] = it }
                return mapped
            }
        }
        val activePrefix = "$serviceId::"
        return songCache.values
            .asSequence()
            .filter { it.id.startsWith(activePrefix) && !isBlocked(it.id) }
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    private fun sortProviderSongsNewestFirst(songs: List<ProviderSong>): List<ProviderSong> {
        if (songs.isEmpty()) return emptyList()
        return songs
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ProviderSong>> { it.value.createdAtMs }
                    .thenByDescending { it.index }
            )
            .map { it.value }
    }

    private fun sortStreamingSongsNewestFirst(songs: List<StreamingSong>): List<StreamingSong> {
        if (songs.isEmpty()) return emptyList()
        return songs
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<StreamingSong>> { it.value.createdAtMs }
                    .thenByDescending { it.index }
            )
            .map { it.value }
    }

    override suspend fun refreshNewestSongs(limit: Int): List<StreamingSong> {
        return getNewestSongsInternal(limit = limit, forceRefresh = true)
    }

    override suspend fun getNewestSongs(limit: Int): List<StreamingSong> {
        return getNewestSongsInternal(limit = limit, forceRefresh = false)
    }

    private suspend fun getNewestSongsInternal(limit: Int, forceRefresh: Boolean): List<StreamingSong> {
        val safeLimit = limit.coerceAtLeast(1)
        if (appSettings.offlineMode.value) {
            return sortStreamingSongsNewestFirst(downloadedSongsMap.values.toList()).take(safeLimit)
        }
        val serviceId = activeServiceId()
        val canFetchRemote = NetworkUtils.isNetworkAvailable(context) && isServiceConnected(serviceId)
        if (canFetchRemote) {
            val rawPublic = firebaseClient.getPublicSongs(forceRefresh = forceRefresh, sort = "newest").getOrElse { emptyList() }
            val rawHomeNewest = if (forceRefresh || rawPublic.isEmpty()) {
                firebaseClient.getHomeBundle().getOrNull()?.newestSongs.orEmpty()
            } else {
                emptyList()
            }
            val merged = (rawHomeNewest + rawPublic).distinctBy { it.providerId }
            if (merged.isNotEmpty()) {
                val sorted = sortProviderSongsNewestFirst(merged)
                val mapped = sorted
                    .filterNot { isBlocked(it.providerId) }
                    .map { mapProviderSong(serviceId, it) }
                    .take(safeLimit)
                mapped.forEach { songCache[it.id] = it }
                return mapped
            }
        }
        val fromRoom = runCatching { localCatalogStore.getNewestSongs(serviceId, safeLimit) }.getOrDefault(emptyList())
        if (fromRoom.isNotEmpty()) {
            return sortStreamingSongsNewestFirst(fromRoom.filterNot { isBlocked(it.id) }).take(safeLimit)
        }
        val activePrefix = "$serviceId::"
        val inMemory = songsFlow.value.filterIsInstance<StreamingSong>()
            .filter { it.id.startsWith(activePrefix) && !isBlocked(it.id) }
        return sortStreamingSongsNewestFirst(inMemory).take(safeLimit)
    }

    override suspend fun getNewReleases(limit: Int): List<StreamingAlbum> {
        if (appSettings.offlineMode.value) {
            return deriveAlbumsFromSongs(downloadedSongsMap.values.toList(), limit)
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            return emptyList()
        }
        val providerAlbums = firebaseClient.getAlbums().getOrElse { emptyList() }
        return providerAlbums
            .take(limit.coerceAtLeast(1))
            .mapNotNull { providerAlbum ->
                try {
                    mapProviderAlbum(serviceId, providerAlbum)
                } catch (_: Exception) {
                    null
                }
            }
    }

    override suspend fun getFeaturedPlaylists(limit: Int): List<StreamingPlaylist> {
        val current = playlistsFlow.value.filterIsInstance<StreamingPlaylist>()
        if (current.isNotEmpty()) {
            return current.take(limit.coerceAtLeast(1))
        }
        if (appSettings.offlineMode.value || !isServiceConnected(activeServiceId())) return emptyList()
        val serviceId = activeServiceId()
        return firebaseClient.getPlaylists().getOrElse { emptyList() }
            .take(limit.coerceAtLeast(1))
            .map { mapProviderPlaylist(serviceId, it) }
    }

    override suspend fun getBrowseCategories(): List<BrowseCategory> {
        return listOf(
            BrowseCategory(id = "recent", name = "Newest"),
            BrowseCategory(id = "charts", name = "Charts"),
            BrowseCategory(id = "favorites", name = "Favorites"),
            BrowseCategory(id = "library", name = "Library")
        )
    }

    override suspend fun getCategoryPlaylists(categoryId: String, limit: Int): List<StreamingPlaylist> {
        val normalizedCategory = categoryId.trim().lowercase()
        val safeLimit = limit.coerceAtLeast(1)
        val playlists = playlistsFlow.value.filterIsInstance<StreamingPlaylist>()

        return when (normalizedCategory) {
            "recent", "library", "charts" -> playlists.take(safeLimit)
            "favorites" -> playlists.filter { it.id in followedPlaylistIds }.take(safeLimit)
            else -> playlists.filter {
                it.name.contains(categoryId, ignoreCase = true) ||
                    (it.description?.contains(categoryId, ignoreCase = true) == true)
            }.take(safeLimit).ifEmpty { playlists.take(safeLimit) }
        }
    }

    override suspend fun getTopCharts(limit: Int): List<StreamingSong> {
        if (appSettings.offlineMode.value) {
            return downloadedSongsMap.values.take(limit.coerceAtLeast(1))
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) return emptyList()
        val charts = firebaseClient.getCharts().getOrElse {
            firebaseClient.getPublicSongs().getOrElse { emptyList() }
        }
        val mapped = charts
            .filterNot { isBlocked(it.providerId) }
            .map { mapProviderSong(serviceId, it) }
            .take(limit.coerceAtLeast(1))
        mapped.forEach { songCache[it.id] = it }
        return mapped
    }

    override fun getLikedSongs(): Flow<List<StreamingSong>> = likedSongsFlow.asStateFlow()

    override suspend fun likeSong(songId: String): Boolean {
        if (appSettings.offlineMode.value) return false
        val decoded = decodeSongId(songId)
        if (decoded != null && isServiceConnected(decoded.first)) {
            val success = firebaseClient.toggleSongLike(decoded.second, true).isSuccess
            if (!success) return false
        }
        likedSongIds.add(songId)
        songCache[songId]?.let { songCache[songId] = it.copy(isFavorite = true) }
        updateLikedSongsFlow()
        repositoryScope.launch { runCatching { localCatalogStore.updateFavorite(songId, true) } }
        return true
    }

    override suspend fun unlikeSong(songId: String): Boolean {
        if (appSettings.offlineMode.value) return false
        val decoded = decodeSongId(songId)
        if (decoded != null && isServiceConnected(decoded.first)) {
            val success = firebaseClient.toggleSongLike(decoded.second, false).isSuccess
            if (!success) return false
        }
        val removed = likedSongIds.remove(songId)
        songCache[songId]?.let { songCache[songId] = it.copy(isFavorite = false) }
        updateLikedSongsFlow()
        repositoryScope.launch { runCatching { localCatalogStore.updateFavorite(songId, false) } }
        return removed
    }

    override suspend fun isSongLiked(songId: String): Boolean = likedSongIds.contains(songId)

    override suspend fun followArtist(artistId: String): Boolean {
        followedArtistIds.add(artistId)
        updateFollowedArtistsFlow()
        return true
    }

    override suspend fun unfollowArtist(artistId: String): Boolean {
        val removed = followedArtistIds.remove(artistId)
        updateFollowedArtistsFlow()
        return removed
    }

    override suspend fun isArtistFollowed(artistId: String): Boolean = followedArtistIds.contains(artistId)

    override fun getFollowedArtists(): Flow<List<StreamingArtist>> = followedArtistsFlow.asStateFlow()

    override suspend fun saveAlbum(albumId: String): Boolean {
        if (appSettings.offlineMode.value) return false
        val decoded = decodeAlbumId(albumId)
        if (decoded != null && isServiceConnected(decoded.serviceId)) {
            var providerId = decoded.providerAlbumId
            if (providerId.isNullOrBlank()) {
                val matchedAlbum = albumsFlow.value.firstOrNull { it.id == albumId } as? StreamingAlbum
                providerId = matchedAlbum?.externalId
            }
            if (!providerId.isNullOrBlank()) {
                val success = firebaseClient.toggleAlbumLike(providerId, true).isSuccess
                if (!success) return false
            }
        }

        savedAlbumIds.add(albumId)
        updateSavedAlbumsFlow()
        return true
    }

    override suspend fun unsaveAlbum(albumId: String): Boolean {
        if (appSettings.offlineMode.value) return false
        val decoded = decodeAlbumId(albumId)
        if (decoded != null && isServiceConnected(decoded.serviceId)) {
            var providerId = decoded.providerAlbumId
            if (providerId.isNullOrBlank()) {
                val matchedAlbum = albumsFlow.value.firstOrNull { it.id == albumId } as? StreamingAlbum
                providerId = matchedAlbum?.externalId
            }
            if (!providerId.isNullOrBlank()) {
                val success = firebaseClient.toggleAlbumLike(providerId, false).isSuccess
                if (!success) return false
            }
        }

        val removed = savedAlbumIds.remove(albumId)
        updateSavedAlbumsFlow()
        return removed
    }

    override fun getSavedAlbums(): Flow<List<StreamingAlbum>> = savedAlbumsFlow.asStateFlow()

    override suspend fun followPlaylist(playlistId: String): Boolean {
        if (appSettings.offlineMode.value) return false
        val playlist = getPlaylistById(playlistId) ?: return false
        followedPlaylistIds.add(playlist.id)
        return true
    }

    override suspend fun unfollowPlaylist(playlistId: String): Boolean {
        if (appSettings.offlineMode.value) return false
        return deletePlaylist(playlistId)
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String): Boolean {
        if (appSettings.offlineMode.value) return false
        val decodedPlaylist = decodePlaylistId(playlistId) ?: return false
        val serviceId = decodedPlaylist.first
        val providerPlaylistId = decodedPlaylist.second

        if (!isServiceConnected(serviceId)) {
            return false
        }

        val trimmedName = newName.trim()
        if (providerPlaylistId.isBlank() || trimmedName.isBlank()) {
            return false
        }

        return firebaseClient.updatePlaylist(providerPlaylistId, name = trimmedName).isSuccess
            .also { success ->
                if (success) {
                    syncPlaylists()
                }
            }
    }

    override suspend fun deletePlaylist(playlistId: String): Boolean {
        if (appSettings.offlineMode.value) return false
        val decodedPlaylist = decodePlaylistId(playlistId) ?: return false
        val serviceId = decodedPlaylist.first
        val providerPlaylistId = decodedPlaylist.second

        if (!isServiceConnected(serviceId)) {
            return false
        }

        return firebaseClient.deletePlaylist(providerPlaylistId).isSuccess
            .also { success ->
                if (success) {
                    syncPlaylists()
                }
            }
    }

    override suspend fun createPlaylist(
        name: String,
        description: String?,
        isPublic: Boolean
    ): StreamingPlaylist? {
        if (appSettings.offlineMode.value) return null
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            return null
        }

        val createdPlaylist = firebaseClient.createPlaylist(
            name = name,
            description = description,
            isPublic = isPublic
        ).getOrNull() ?: return null

        val mappedPlaylist = mapProviderPlaylist(serviceId, createdPlaylist)
        playlistsFlow.value = (playlistsFlow.value.filterNot { it.id == mappedPlaylist.id } + mappedPlaylist)
            .sortedBy { it.name.lowercase() }
        return mappedPlaylist
    }

    override suspend fun addSongsToPlaylist(playlistId: String, songIds: List<String>): Boolean {
        if (appSettings.offlineMode.value) return false
        val decodedPlaylist = decodePlaylistId(playlistId) ?: return false
        val serviceId = decodedPlaylist.first
        val providerPlaylistId = decodedPlaylist.second
        val providerSongIds = songIds.mapNotNull { decodeSongId(it)?.second ?: it }.filter { it.isNotBlank() }

        if (providerSongIds.isEmpty() || !isServiceConnected(serviceId)) {
            return false
        }

        val success = firebaseClient.updatePlaylist(
            playlistId = providerPlaylistId,
            addSongIds = providerSongIds
        ).isSuccess

        if (success) {
            syncPlaylists()
        }
        return success
    }

    override suspend fun removeSongsFromPlaylist(playlistId: String, songIds: List<String>): Boolean {
        if (appSettings.offlineMode.value) return false
        val decodedPlaylist = decodePlaylistId(playlistId) ?: return false
        val serviceId = decodedPlaylist.first
        val providerPlaylistId = decodedPlaylist.second
        val providerSongIds = songIds.mapNotNull { decodeSongId(it)?.second ?: it }.filter { it.isNotBlank() }

        if (providerSongIds.isEmpty() || !isServiceConnected(serviceId)) {
            return false
        }

        val success = firebaseClient.updatePlaylist(
            playlistId = providerPlaylistId,
            removeSongIds = providerSongIds
        ).isSuccess

        if (success) {
            syncPlaylists()
        }
        return success
    }

    override suspend fun getStreamingUrl(songId: String): String? {
        val (serviceId, providerId) = decodeSongId(songId) ?: return null

        val localFile = getDownloadFile(songId)
        if (localFile.exists() && localFile.length() > 0) {
            return Uri.fromFile(localFile).toString()
        }

        if (appSettings.offlineMode.value) {
            return songCache[songId]?.streamingUrl
        }

        val allowCellular = appSettings.allowCellularStreaming.value
        if (!NetworkUtils.canStream(context, allowCellular)) {
            return null
        }

        val existing = songCache[songId]
        val existingUrl = existing?.streamingUrl?.takeIf { it.isNotBlank() }
        if (existing != null && existingUrl != null &&
            (existingUrl.startsWith("http://", ignoreCase = true) || existingUrl.startsWith("https://", ignoreCase = true)) &&
            ((!existing.isPaidTrack && existing.price <= 0.0 && !chromahub.rhythm.app.features.streaming.data.provider.XkResponseParser.looksLikeDemoUrl(existingUrl)) ||
                (existing.isPaidTrack && !existing.isPurchased))
        ) {
            return existingUrl
        }

        if (!isServiceConnected(serviceId)) {
            return existingUrl
        }

        val resolved = firebaseClient.getStreamingUrl(providerId)
        if (!resolved.isNullOrBlank()) {
            if (existing != null) {
                songCache[songId] = existing.copy(streamingUrl = resolved)
            }
            repositoryScope.launch {
                runCatching { localCatalogStore.updateStreamingUrl(songId, resolved) }
            }
        }
        return resolved ?: existingUrl
    }

    override suspend fun reportPlaybackStart(songId: String): Boolean {
        if (appSettings.offlineMode.value) return false
        val (serviceId, providerId) = decodeSongId(songId) ?: return false
        if (!isServiceConnected(serviceId)) return false
        firebaseClient.recordSongPlay(providerId)
        return true
    }

    override suspend fun reportPlaybackStop(songId: String, positionMs: Long): Boolean {
        return true
    }

    override suspend fun reportPlaybackProgress(songId: String, positionMs: Long, isPaused: Boolean): Boolean {
        return true
    }

    fun invalidateStreamingUrlCache(songId: String) {
        songCache.remove(songId)
    }

    fun invalidateAllStreamingUrlCache() {
        songCache.clear()
    }

    override suspend fun getRelatedTracks(songId: String, limit: Int): List<StreamingSong> {
        if (appSettings.offlineMode.value) {
            val song = downloadedSongsMap[songId] ?: return emptyList()
            return downloadedSongsMap.values.filter {
                it.artist.equals(song.artist, ignoreCase = true) && it.id != song.id
            }.take(limit)
        }
        val decodedSong = decodeSongId(songId) ?: return emptyList()
        val serviceId = decodedSong.first
        val currentSong = songCache[songId]
        val catalogSongs = firebaseClient.getPublicSongs().getOrElse { emptyList() }.map { mapProviderSong(serviceId, it) }
        val sameArtist = if (currentSong != null) {
            cachedSongsForArtist(currentSong.artist).filter { it.id != songId }
        } else {
            emptyList()
        }
        return (sameArtist + catalogSongs)
            .distinctBy { it.id }
            .filter { it.id != songId && !isBlocked(it.id) }
            .take(limit.coerceAtLeast(1))
    }

    override suspend fun getArtistTopTracks(artistId: String, limit: Int): List<StreamingSong> {
        val artistName = extractArtistNameFromId(artistId)
        if (artistName.isBlank()) {
            return emptyList()
        }

        val isOnline = NetworkUtils.isNetworkAvailable(context) && !appSettings.offlineMode.value
        if (isOnline) {
            val serviceId = artistId.substringBefore("::", activeServiceId())
            if (isServiceConnected(serviceId)) {
                try {
                    val providerArtistId = resolveProviderArtistId(serviceId, artistName) ?: artistName
                    val detail = firebaseClient.getArtistDetail(providerArtistId).getOrNull()
                    val tracks = detail?.topTracks.orEmpty()
                        .filterNot { isBlocked(it.providerId) }
                        .map { mapProviderSong(serviceId, it) }
                        .take(limit.coerceAtLeast(1))
                    if (tracks.isNotEmpty()) {
                        tracks.forEach { songCache[it.id] = it }
                        return tracks
                    }
                } catch (e: Exception) {
                    Log.w("StreamingMusicRepo", "Network getArtistTopTracks failed, falling back to cache", e)
                }
            }
        }

        return cachedSongsForArtist(artistName).take(limit.coerceAtLeast(1))
    }

    override suspend fun getAlbumSongs(albumId: String): List<StreamingSong> {
        val decodedId = decodeAlbumId(albumId) ?: return emptyList()
        var resolvedProviderAlbumId = decodedId.providerAlbumId
        val isOnline = NetworkUtils.isNetworkAvailable(context) && !appSettings.offlineMode.value

        if (resolvedProviderAlbumId == null && isOnline && decodedId.title.isNotBlank()) {
            val searchResults = firebaseClient.search(decodedId.title).getOrNull()?.albums.orEmpty()
            val matchedAlbum = searchResults.firstOrNull {
                it.title.equals(decodedId.title, ignoreCase = true) &&
                    (decodedId.artist.isBlank() || it.artist.equals(decodedId.artist, ignoreCase = true))
            } ?: searchResults.firstOrNull()
            if (matchedAlbum != null) {
                resolvedProviderAlbumId = matchedAlbum.providerId
            }
        }

        if (isOnline && resolvedProviderAlbumId != null) {
            try {
                val detail = firebaseClient.getAlbumDetail(resolvedProviderAlbumId).getOrNull()
                if (detail != null && detail.songs.isNotEmpty()) {
                    val mapped = detail.songs
                        .filterNot { isBlocked(it.providerId) }
                        .map { mapProviderSong(decodedId.serviceId, it) }
                    mapped.forEach { songCache[it.id] = it }
                    return mapped
                }
            } catch (e: Exception) {
                Log.w("StreamingMusicRepo", "Network getAlbumSongs failed for album $albumId", e)
            }
        }

        val matchesByAlbumId = downloadedSongsMap.values.filter { it.albumId == albumId }
        if (matchesByAlbumId.isNotEmpty()) return matchesByAlbumId

        val albumTitle = decodedId.title
        val albumArtist = decodedId.artist
        if (albumTitle.isNotBlank()) {
            val cachedByTitle = songCache.values.filter {
                it.album.equals(albumTitle, ignoreCase = true) &&
                    (albumArtist.isBlank() || it.artist.equals(albumArtist, ignoreCase = true))
            }
            if (cachedByTitle.isNotEmpty()) return cachedByTitle
            return downloadedSongsMap.values.filter {
                it.album.equals(albumTitle, ignoreCase = true) &&
                    (it.albumArtist.equals(albumArtist, ignoreCase = true) || it.artist.equals(albumArtist, ignoreCase = true))
            }
        }

        return emptyList()
    }

    override suspend fun getArtistAlbums(artistId: String): List<StreamingAlbum> {
        val artistName = extractArtistNameFromId(artistId)
        if (artistName.isBlank()) return emptyList()

        val isOnline = NetworkUtils.isNetworkAvailable(context) && !appSettings.offlineMode.value
        if (isOnline) {
            val serviceId = activeServiceId()
            if (isServiceConnected(serviceId)) {
                try {
                    val providerArtistId = resolveProviderArtistId(serviceId, artistName) ?: artistName
                    val detail = firebaseClient.getArtistDetail(providerArtistId).getOrNull()
                    val albums = detail?.albums.orEmpty().map { mapProviderAlbum(serviceId, it) }
                    if (albums.isNotEmpty()) {
                        return albums
                    }
                } catch (e: Exception) {
                    Log.w("StreamingMusicRepo", "Network getArtistAlbums failed, falling back to cache", e)
                }
            }
        }

        val artistSongs = cachedSongsForArtist(artistName).ifEmpty {
            downloadedSongsMap.values.filter { it.artist.equals(artistName, ignoreCase = true) }
        }
        return deriveAlbumsFromSongs(artistSongs, 100)
    }
    
    private fun restoreArtistToken(token: String): String {
        return token.replace("_slash_", "/").replace("_", " ").trim()
    }

    private fun extractArtistNameFromId(artistId: String): String {
        if (artistId.contains("::")) {
            val parts = artistId.split("::")
            if (parts.size >= 3 && parts[1] == "artist") {
                return restoreArtistToken(parts[2])
            }
        }
        if (artistId.contains(":artist:")) {
            val afterArtist = artistId.substringAfter(":artist:")
            return restoreArtistToken(afterArtist)
        }
        return restoreArtistToken(artistId)
    }

    private suspend fun resolveProviderArtistId(serviceId: String, artistName: String): String? {
        val searchMatches = firebaseClient.search(artistName).getOrNull()?.artists.orEmpty()
        val exactMatch = searchMatches.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
        if (exactMatch != null) return exactMatch.providerId
        val allArtists = firebaseClient.getArtists().getOrNull().orEmpty()
        val exactFromAll = allArtists.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
        return (exactFromAll ?: searchMatches.firstOrNull())?.providerId
    }

    override suspend fun getRelatedArtists(artistId: String, limit: Int): List<StreamingArtist> {
        if (appSettings.offlineMode.value) return emptyList()
        val artistName = extractArtistNameFromId(artistId)
        val serviceId = artistId.substringBefore("::", activeServiceId())
        val all = firebaseClient.getArtists().getOrElse { emptyList() }
            .filter { !it.name.equals(artistName, ignoreCase = true) }
            .take(limit.coerceAtLeast(1))
        return mapProviderArtists(serviceId, all)
    }

    override suspend fun downloadSong(song: StreamingSong): Boolean {
        songCache[song.id] = song
        return downloadSong(song.id)
    }

    override suspend fun downloadSong(songId: String): Boolean = withContext(Dispatchers.IO) {
        if (isDownloaded(songId)) return@withContext true

        val (serviceId, providerId) = decodeSongId(songId) ?: return@withContext false

        val targetUrl = firebaseClient.getStreamingUrl(providerId) ?: return@withContext false

        var song = songCache[songId]
            ?: songsFlow.value.filterIsInstance<StreamingSong>().firstOrNull { it.id == songId }
            ?: likedSongsFlow.value.firstOrNull { it.id == songId }
            ?: downloadedSongsMap[songId]

        if (song == null) {
            song = (getSongById(songId) as? StreamingSong)
        }
        val finalSong = song ?: return@withContext false

        val client = UserTrustManager.buildUserTrustingHttpClientBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chromahub.rhythm.app.network.XkMusicApiService.buildAuthenticatedRequest(
                        chain.request(),
                        acceptJson = false
                    )
                )
            }
            .build()

        fun buildRequest(url: String): okhttp3.Request {
            return okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "XKMusic/${chromahub.rhythm.app.BuildConfig.VERSION_NAME} (Android)")
                .build()
        }

        var downloadedFile: java.io.File? = null
        try {
            val response = client.newCall(buildRequest(targetUrl)).execute()

            if (!response.isSuccessful) {
                response.close()
                return@withContext false
            }

            val ext = extractExtension(response.header("Content-Disposition"), response.header("Content-Type"))
            val file = getDownloadFile(songId, ext)
            downloadedFile = file

            response.use { resp ->
                val body = resp.body
                body.byteStream().use { inputStream ->
                    file.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            var localArtworkPath: String? = null
            val artworkUri = finalSong.artworkUri

            val safeSongId = songId.replace(":", "_").replace("/", "_").replace("\\", "_")
            val safeAlbumKey = (finalSong.albumId ?: "${finalSong.artist}_${finalSong.album}")
                .replace(":", "_").replace("/", "_").replace("\\", "_").replace(" ", "_").lowercase()
            val albumArtFile = java.io.File(downloadDirectory, "${safeAlbumKey}_album_art.jpg")
            val songArtFile = java.io.File(downloadDirectory, "${safeSongId}_art.jpg")

            if (albumArtFile.exists() && albumArtFile.length() > 0) {
                localArtworkPath = Uri.fromFile(albumArtFile).toString()
            } else if (songArtFile.exists() && songArtFile.length() > 0) {
                localArtworkPath = Uri.fromFile(songArtFile).toString()
            } else if (!artworkUri.isNullOrBlank() && (artworkUri.startsWith("http://") || artworkUri.startsWith("https://"))) {
                val artRequest = okhttp3.Request.Builder()
                    .url(artworkUri)
                    .header("User-Agent", "XKMusic/${chromahub.rhythm.app.BuildConfig.VERSION_NAME} (Android)")
                    .build()
                try {
                    client.newCall(artRequest).execute().use { responseArt ->
                        if (responseArt.isSuccessful) {
                            responseArt.body.byteStream().use { artInput ->
                                albumArtFile.outputStream().use { artOutput ->
                                    artInput.copyTo(artOutput)
                                }
                            }
                            if (albumArtFile.exists() && albumArtFile.length() > 0) {
                                localArtworkPath = Uri.fromFile(albumArtFile).toString()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StreamingMusicRepo", "Error downloading artwork for song $songId", e)
                }
            }

            if (localArtworkPath == null) {
                val existingLocalArt = downloadedSongsMap.values.firstOrNull {
                    (it.albumId == finalSong.albumId || it.album.equals(finalSong.album, ignoreCase = true)) &&
                    it.artworkUri?.startsWith("file:") == true
                }?.artworkUri
                if (existingLocalArt != null) {
                    localArtworkPath = existingLocalArt
                }
            }

            val localSongUrl = Uri.fromFile(file).toString()
            val downloadedSong = finalSong.copy(
                streamingUrl = localSongUrl,
                artworkUri = localArtworkPath ?: finalSong.artworkUri
            )

            downloadedSongsMap[songId] = downloadedSong
            saveDownloadedSongsIndex()
            true
        } catch (e: Exception) {
            Log.e("StreamingMusicRepo", "Error downloading song $songId", e)
            downloadedFile?.let { if (it.exists()) it.delete() }
            false
        }
    }

    override suspend fun removeDownload(songId: String): Boolean = withContext(Dispatchers.IO) {
        val file = getDownloadFile(songId)
        val deletedFile = if (file.exists()) file.delete() else true

        val safeSongId = songId.replace(":", "_").replace("/", "_").replace("\\", "_")
        for (candidateExt in audioExtensions) {
            val candidate = java.io.File(downloadDirectory, "$safeSongId$candidateExt")
            if (candidate.exists()) {
                candidate.delete()
            }
        }
        
        // Delete song specific artwork if exists
        val songArtFile = java.io.File(downloadDirectory, "${safeSongId}_art.jpg")
        if (songArtFile.exists()) {
            songArtFile.delete()
        }
        
        val removedSong = downloadedSongsMap.remove(songId)
        if (removedSong != null) {
            saveDownloadedSongsIndex()

            val safeAlbumKey = (removedSong.albumId ?: "${removedSong.artist}_${removedSong.album}")
                .replace(":", "_").replace("/", "_").replace("\\", "_").replace(" ", "_").lowercase()
            val anyRemainingInAlbum = downloadedSongsMap.values.any { other ->
                val otherAlbumKey = (other.albumId ?: "${other.artist}_${other.album}")
                    .replace(":", "_").replace("/", "_").replace("\\", "_").replace(" ", "_").lowercase()
                otherAlbumKey == safeAlbumKey
            }
            if (!anyRemainingInAlbum) {
                val albumArtFile = java.io.File(downloadDirectory, "${safeAlbumKey}_album_art.jpg")
                if (albumArtFile.exists()) {
                    albumArtFile.delete()
                }
            }
        }
        
        deletedFile || (removedSong != null)
    }

    override suspend fun isDownloaded(songId: String): Boolean = withContext(Dispatchers.IO) {
        val file = getDownloadFile(songId)
        (file.exists() && file.length() > 0) || downloadedSongsMap.containsKey(songId)
    }

    override fun getDownloadedSongs(): Flow<List<StreamingSong>> = downloadedSongsFlow.asStateFlow()

    override suspend fun getRandomSongs(limit: Int): List<StreamingSong> {
        if (appSettings.offlineMode.value) {
            return downloadedSongsMap.values.shuffled().take(limit.coerceAtLeast(1))
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            return emptyList()
        }

        val songs = firebaseClient.getPublicSongs()
            .getOrElse { emptyList() }
            .filterNot { isBlocked(it.providerId) }
        return mapProviderSongs(serviceId, songs).shuffled().take(limit.coerceAtLeast(1))
    }

    override suspend fun getAlbumList(type: String, limit: Int): List<StreamingAlbum> {
        if (!appSettings.offlineMode.value) {
            val serviceId = activeServiceId()
            if (isServiceConnected(serviceId)) {
                try {
                    val providerAlbums = firebaseClient.getAlbums().getOrNull()
                    if (providerAlbums != null) {
                        val albums = providerAlbums.take(limit).map { mapProviderAlbum(serviceId, it) }
                        return albums
                    }
                } catch (e: Exception) {
                    Log.w("StreamingMusicRepo", "Network getAlbumList failed, falling back to cache", e)
                }
            }
        }
        return deriveAlbumsFromSongs(downloadedSongsMap.values.toList(), limit)
    }

    override suspend fun searchSongs(query: String): List<PlayableItem> {
        if (appSettings.offlineMode.value) {
            val results = downloadedSongsMap.values.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
            }
            mergeCatalog(results)
            return results
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            clearInMemoryCatalog()
            return emptyList()
        }

        val providerSongs = getCachedOrFetchSearch(query)?.songs.orEmpty()
            .filterNot { isBlocked(it.providerId) }
        val mappedSongs = mapProviderSongs(serviceId, providerSongs)
        mergeCatalog(mappedSongs)
        return mappedSongs
    }

    override suspend fun searchAlbums(query: String): List<AlbumItem> {
        if (appSettings.offlineMode.value) {
            return deriveAlbumsFromSongs(downloadedSongsMap.values.toList(), SEARCH_LIMIT).filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
            }
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            return emptyList()
        }
        return getCachedOrFetchSearch(query)?.albums.orEmpty().map {
            mapProviderAlbum(serviceId, it)
        }
    }

    override suspend fun searchArtists(query: String): List<ArtistItem> {
        if (appSettings.offlineMode.value) {
            val songs = searchSongs(query).filterIsInstance<StreamingSong>()
            return buildDerivedArtistItems(activeServiceId(), songs)
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            return emptyList()
        }

        val providerArtists = getCachedOrFetchSearch(query)?.artists.orEmpty()
        if (providerArtists.isNotEmpty()) {
            return mapProviderArtists(serviceId, providerArtists)
        }

        val songs = searchSongs(query).filterIsInstance<StreamingSong>()
        return buildDerivedArtistItems(serviceId, songs)
    }

    override suspend fun searchPlaylists(query: String): List<PlaylistItem> {
        if (appSettings.offlineMode.value) {
            return emptyList()
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            return emptyList()
        }

        val providerPlaylists = getCachedOrFetchSearch(query)?.playlists.orEmpty()
        return providerPlaylists.map { providerPlaylist ->
            val tracks = fetchPlaylistTracks(serviceId, providerPlaylist.providerId)
            mapProviderPlaylist(serviceId, providerPlaylist, tracks)
        }
    }

    override fun getSongs(): Flow<List<PlayableItem>> = songsFlow.asStateFlow()

    override fun getAlbums(): Flow<List<AlbumItem>> = albumsFlow.asStateFlow()

    override fun getArtists(): Flow<List<ArtistItem>> = artistsFlow.asStateFlow()

    override fun getPlaylists(): Flow<List<PlaylistItem>> = playlistsFlow.asStateFlow()

    override suspend fun getSongById(id: String): PlayableItem? {
        songCache[id]?.let { return it }
        starredSongCache[id]?.let { return it }
        val cleanId = decodeSongId(id)?.second ?: return null
        val meta = firebaseClient.getSongDetail(cleanId).getOrNull() ?: return null
        val mapped = mapProviderSong(activeServiceId(), meta.song)
        songCache[mapped.id] = mapped
        return mapped
    }

    override suspend fun syncCatalog(
        limit: Int,
        onProgress: ((current: Int, total: Int, songsCount: Int) -> Unit)?
    ): List<StreamingSong> {
        initialCatalogLoad.await()
        return catalogSync.run { syncCatalogLocked(limit, onProgress) }
    }

    override suspend fun isCatalogOutdated(): Boolean {
        if (!coldStartCatalogCheck.tryEnter()) return false
        if (loadedCacheNeedsCommerceRefresh) {
            loadedCacheNeedsCommerceRefresh = false
            return true
        }
        val serviceId = activeServiceId()
        val metadata = runCatching { localCatalogStore.getSyncMetadata(serviceId) }.getOrNull()
        val lastSync = metadata?.lastFullSyncTimestamp ?: lastLoadedSyncTimestamp
        if (metadata != null && metadata.songCount <= 0) return true
        if (lastSync <= 0L) return songsFlow.value.isEmpty()
        return (System.currentTimeMillis() - lastSync) > CATALOG_TTL_MS
    }

    private suspend fun syncCatalogLocked(
        limit: Int,
        onProgress: ((current: Int, total: Int, songsCount: Int) -> Unit)?
    ): List<StreamingSong> {
        if (appSettings.offlineMode.value) {
            val downloadedList = downloadedSongsMap.values.toList()
            replaceCatalog(downloadedList)
            return downloadedList
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            val existing = songsFlow.value.filterIsInstance<StreamingSong>()
            return existing
        }

        // Refresh blocked IDs first so blocked tracks are omitted from synced catalog
        firebaseClient.getBlockedSongs().getOrNull()?.let { blocks ->
            blockedSongIds.clear()
            blocks.forEach { blockedSongIds.add(it.songId) }
        }

        syncArtists()

        val allPublic = firebaseClient.getPublicSongs(forceRefresh = true).getOrElse { emptyList() }
        onProgress?.invoke(1, 1, allPublic.size)

        val distinctSongs = allPublic
            .distinctBy { it.providerId }
            .filterNot { isBlocked(it.providerId) }
            .take(limit)

        syncLikedSongIdsFromProviderSongs(serviceId, distinctSongs)
        val mappedSongs = mapProviderSongs(serviceId, distinctSongs)
        replaceCatalog(mappedSongs, libraryMarker = null)

        val providerAlbums = firebaseClient.getAlbums().getOrElse { emptyList() }
            .map { mapProviderAlbum(serviceId, it) }
        if (providerAlbums.isNotEmpty()) {
            providerAlbumCache.clear()
            providerAlbums.forEach { providerAlbumCache[it.id] = it }
            providerAlbumsFlow.value = providerAlbums
            albumsFlow.value = providerAlbums
        }

        syncPlaylists()
        saveCatalogCache(serviceId)
        return mappedSongs
    }

    private fun syncLikedSongIdsFromProviderSongs(serviceId: String, providerSongs: List<ProviderSong>) {
        val servicePrefix = "$serviceId::"
        likedSongIds.removeIf { it.startsWith(servicePrefix) }
        providerSongs
            .asSequence()
            .filter { it.isFavorite }
            .map { encodeSongId(serviceId, it.providerId) }
            .forEach { likedSongIds.add(it) }
    }

    override suspend fun syncPlaylists(): List<StreamingPlaylist> {
        if (appSettings.offlineMode.value) {
            playlistsFlow.value = emptyList()
            return emptyList()
        }
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) {
            return playlistsFlow.value.filterIsInstance<StreamingPlaylist>()
        }

        val result = firebaseClient.getPlaylists()
        val playlists = coroutineScope {
            result.getOrElse { emptyList() }
                .map { providerPlaylist ->
                    async {
                        val tracks = fetchPlaylistTracks(serviceId, providerPlaylist.providerId)
                        mapProviderPlaylist(serviceId, providerPlaylist, tracks)
                    }
                }.awaitAll()
        }

        playlistsFlow.value = playlists
        saveCatalogCache(serviceId)
        return playlists
    }

    override suspend fun getAlbumById(id: String): AlbumItem? {
        albumsFlow.value.firstOrNull { it.id == id }?.let { return it }

        val decodedId = decodeAlbumId(id) ?: return null
        var providerAlbumId = decodedId.providerAlbumId
        val isOnline = NetworkUtils.isNetworkAvailable(context) && !appSettings.offlineMode.value
        if (isOnline) {
            if (providerAlbumId == null && decodedId.title.isNotBlank()) {
                val searchResults = firebaseClient.search(decodedId.title).getOrNull()?.albums.orEmpty()
                val matchedAlbum = searchResults.firstOrNull {
                    it.title.equals(decodedId.title, ignoreCase = true) &&
                        (decodedId.artist.isBlank() || it.artist.equals(decodedId.artist, ignoreCase = true))
                } ?: searchResults.firstOrNull()
                if (matchedAlbum != null) {
                    providerAlbumId = matchedAlbum.providerId
                }
            }

            if (providerAlbumId != null) {
                try {
                    val detail = firebaseClient.getAlbumDetail(providerAlbumId).getOrNull()
                    if (detail != null) {
                        val songs = detail.songs
                            .filterNot { isBlocked(it.providerId) }
                            .map { mapProviderSong(decodedId.serviceId, it) }
                        if (detail.isLiked) {
                            savedAlbumIds.add(id)
                            updateSavedAlbumsFlow()
                        }
                        return mapProviderAlbum(decodedId.serviceId, detail.album).copy(
                            songCount = maxOf(detail.album.songCount, songs.size),
                            tracks = songs
                        )
                    }
                } catch (e: Exception) {
                    Log.w("StreamingMusicRepo", "Network getAlbumById failed, falling back to cache", e)
                }
            }
        }

        val derived = deriveAlbumsFromSongs(downloadedSongsMap.values.toList(), 100)
        derived.firstOrNull { it.id == id }?.let { return it }

        return derived.firstOrNull { derivedAlbum ->
            val derivedDecoded = decodeAlbumId(derivedAlbum.id)
            derivedDecoded != null &&
                derivedDecoded.title.equals(decodedId.title, ignoreCase = true) &&
                derivedDecoded.artist.equals(decodedId.artist, ignoreCase = true)
        }
    }

    override suspend fun getArtistById(id: String): ArtistItem? {
        artistsFlow.value.firstOrNull { it.id == id }?.let { return it }

        val artistName = extractArtistNameFromId(id)
        val isOnline = NetworkUtils.isNetworkAvailable(context) && !appSettings.offlineMode.value
        if (!isOnline) {
            if (artistName.isBlank()) return null
            val derivedArtists = buildArtistItems(activeServiceId(), downloadedSongsMap.values.toList())
            return derivedArtists.firstOrNull { it.id == id }
                ?: derivedArtists.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
        }

        if (artistName.isBlank()) return null
        val serviceId = id.substringBefore("::", activeServiceId())
        val providerId = resolveProviderArtistId(serviceId, artistName) ?: artistName
        val detail = firebaseClient.getArtistDetail(providerId).getOrNull()
        if (detail != null) {
            return mapProviderArtist(serviceId, detail.artist)
        }
        return null
    }

    override suspend fun getPlaylistById(id: String): PlaylistItem? {
        return playlistsFlow.value.firstOrNull { it.id == id }
    }

    override suspend fun getSongsForAlbum(albumId: String): List<PlayableItem> {
        return getAlbumSongs(albumId)
    }

    override suspend fun getLyrics(songId: String, artist: String?, title: String?): LyricsData? {
        if (appSettings.offlineMode.value) return null
        val decoded = decodeSongId(songId)
        val serviceId = decoded?.first ?: activeServiceId()
        val providerSongId = decoded?.second ?: songId
        if (!isServiceConnected(serviceId)) return null
        return firebaseClient.getSongDetail(providerSongId).getOrNull()?.lyrics
    }

    /**
     * @param libraryMarker server library marker [songs] were fetched under, if known to be the
     *        complete, current library (see [SubsonicApiClient.getLibraryMarker]).
     */
    private suspend fun replaceCatalog(songs: List<StreamingSong>, libraryMarker: String? = null) {
        val serviceId = activeServiceId()
        catalogLibraryMarker = libraryMarker
        // Only populate albumsFlow with derived albums if no provider albums are cached
        val deriveAlbums = providerAlbumCache.isEmpty()
        // Grouping thousands of songs into albums/artists is CPU work; keep it off the caller's
        // (main) dispatcher. Shared caches are still mutated on the caller's thread below.
        val (derivedAlbums, rawArtists) = withContext(Dispatchers.Default) {
            (if (deriveAlbums) buildAlbumItems(serviceId, songs) else null) to
                buildArtistItems(serviceId, songs)
        }

        songCache.clear()
        songs.forEach { song ->
            songCache[song.id] = song
        }

        songsFlow.value = songs
        // Re-check after the suspension: provider albums may have been cached meanwhile, and
        // derived albums must not overwrite them.
        if (derivedAlbums != null && providerAlbumCache.isEmpty()) {
            albumsFlow.value = derivedAlbums
        }
        if (artistsFlow.value.isEmpty() || rawArtists.size >= artistsFlow.value.size) {
            artistsFlow.value = rawArtists
        }

        updateLikedSongsFlow()
        updateSavedAlbumsFlow()
        updateFollowedArtistsFlow()

        saveCatalogCache(serviceId)

        // Asynchronously enrich with Deezer images in background to avoid blocking.
        // A newer catalog change supersedes a running pass (finished lookups stay cached).
        artistEnrichmentJob?.cancel()
        artistEnrichmentJob = repositoryScope.launch {
            try {
                val enriched = enrichArtistsWithDeezerImages(rawArtists)
                if (artistsFlow.value != enriched) {
                    artistsFlow.value = enriched
                    updateFollowedArtistsFlow()
                    saveCatalogCache(serviceId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StreamingMusicRepo", "Background Deezer artist enrichment failed", e)
            }
        }
    }

    private suspend fun mergeCatalog(songs: List<StreamingSong>) {
        if (songs.isEmpty()) {
            return
        }

        val serviceId = activeServiceId()
        songs.forEach { song ->
            songCache[song.id] = song
        }
        trimSongCache()

        val currentSongs = songsFlow.value
        // Merging a search result into a catalog of thousands of songs re-groups every album and
        // artist; do that off the caller's (main) dispatcher, as replaceCatalog() does.
        val deriveAlbums = providerAlbumCache.isEmpty()
        val (mergedSongs, derivedAlbums, rawArtists) = withContext(Dispatchers.Default) {
            val merged = (currentSongs.filterIsInstance<StreamingSong>() + songs).distinctBy { it.id }
            Triple(
                merged,
                if (deriveAlbums) buildAlbumItems(serviceId, merged) else null,
                buildArtistItems(serviceId, merged)
            )
        }

        songsFlow.value = mergedSongs
        // Only populate albumsFlow with derived albums if no provider albums are cached
        if (derivedAlbums != null && providerAlbumCache.isEmpty()) {
            albumsFlow.value = derivedAlbums
        }
        artistsFlow.value = rawArtists
        playlistsFlow.value = emptyList()

        updateLikedSongsFlow()
        updateSavedAlbumsFlow()
        updateFollowedArtistsFlow()

        // Asynchronously enrich with Deezer images in background to avoid blocking.
        // A newer catalog change supersedes a running pass (finished lookups stay cached).
        artistEnrichmentJob?.cancel()
        artistEnrichmentJob = repositoryScope.launch {
            try {
                val enriched = enrichArtistsWithDeezerImages(rawArtists)
                if (artistsFlow.value != enriched) {
                    artistsFlow.value = enriched
                    updateFollowedArtistsFlow()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StreamingMusicRepo", "Background Deezer artist enrichment failed", e)
            }
        }
    }

    private fun clearInMemoryCatalog() {
        catalogLibraryMarker = null
        songCache.clear()
        followedPlaylistIds.clear()
        songsFlow.value = emptyList()
        albumsFlow.value = emptyList()
        artistsFlow.value = emptyList()
        playlistsFlow.value = emptyList()
    }

    private fun updateLikedSongsFlow() {
        likedSongsFlow.value = StarredSongs.likedSongs(likedSongIds, songCache, starredSongCache)
    }

    private fun updateSavedAlbumsFlow() {
        savedAlbumsFlow.value = savedAlbumIds.mapNotNull { id ->
            albumsFlow.value.firstOrNull { it.id == id } as? StreamingAlbum
        }
    }

    private fun updateFollowedArtistsFlow() {
        followedArtistsFlow.value = followedArtistIds.mapNotNull { id ->
            artistsFlow.value.firstOrNull { it.id == id } as? StreamingArtist
        }
    }

    /**
     * Maps a batch of provider songs on [Dispatchers.Default]. Each song builds a signed stream
     * URL (salt + MD5 + URL parse), so a full catalog sync of thousands of songs must not run on
     * the caller's dispatcher: callers are ViewModel coroutines on the main thread, and doing it
     * there blocked input for 20+ seconds ("Input dispatching timed out" ANR).
     */
    private suspend fun mapProviderSongs(
        serviceId: String,
        providerSongs: List<ProviderSong>
    ): List<StreamingSong> {
        if (providerSongs.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            providerSongs.map { mapProviderSong(serviceId, it) }
        }
    }

    private fun mapProviderSong(serviceId: String, providerSong: ProviderSong): StreamingSong {
        val encodedId = encodeSongId(serviceId, providerSong.providerId)
        val sourceType = serviceToSourceType(serviceId)
        val encodedAlbumId = providerSong.albumId
            ?.takeIf { it.isNotBlank() }
            ?.let { encodeAlbumId(serviceId, it) }
        return StreamingSong(
            id = encodedId,
            title = providerSong.title,
            artist = providerSong.artist,
            album = providerSong.album,
            duration = providerSong.durationMs,
            artworkUri = providerSong.artworkUrl,
            sourceType = sourceType,
            streamingUrl = providerSong.streamUrl ?: providerSong.previewUrl,
            previewUrl = providerSong.previewUrl,
            isPlayable = true,
            externalId = providerSong.providerId,
            albumId = encodedAlbumId,
            albumArtist = providerSong.albumArtist,
            isFavorite = providerSong.isFavorite,
            trackNumber = providerSong.trackNumber,
            year = providerSong.year,
            genre = providerSong.genre,
            bitrate = providerSong.bitrate,
            sampleRate = providerSong.sampleRate,
            channels = providerSong.channels,
            codec = providerSong.codec,
            price = providerSong.price,
            isPaidTrack = providerSong.isPaidTrack,
            isPurchased = providerSong.isPurchased,
            currency = providerSong.currency,
            createdAtMs = providerSong.createdAtMs,
            detailPath = "api/v1/songs/${providerSong.providerId}",
            streamEndpointPath = "api/v1/songs/${providerSong.providerId}/stream"
        )
    }

    private suspend fun fetchPlaylistTracks(serviceId: String, providerPlaylistId: String): List<StreamingSong> {
        val detail = firebaseClient.getPlaylistDetail(providerPlaylistId).getOrNull()
        val songs = detail?.songs.orEmpty().filterNot { isBlocked(it.providerId) }
        return mapProviderSongs(serviceId, songs)
    }

    private fun mapProviderPlaylist(
        serviceId: String,
        providerPlaylist: ProviderPlaylist,
        tracks: List<StreamingSong> = emptyList()
    ): StreamingPlaylist {
        val encodedId = encodePlaylistId(serviceId, providerPlaylist.providerId)
        val resolvedTracks = if (tracks.isNotEmpty()) tracks else emptyList()

        return StreamingPlaylist(
            id = encodedId,
            name = providerPlaylist.name,
            description = providerPlaylist.description,
            artworkUri = providerPlaylist.artworkUrl ?: resolvedTracks.firstOrNull()?.artworkUri,
            songCount = providerPlaylist.songCount.takeIf { it > 0 } ?: resolvedTracks.size,
            isEditable = providerPlaylist.owner.isNullOrBlank() ||
                providerPlaylist.owner.equals(firebaseClient.getUsername(), ignoreCase = true),
            sourceType = serviceToSourceType(serviceId),
            externalId = providerPlaylist.providerId,
            owner = if (providerPlaylist.owner != null) {
                chromahub.rhythm.app.features.streaming.domain.model.PlaylistOwner(
                    id = normalizeKey(providerPlaylist.owner),
                    displayName = providerPlaylist.owner,
                    imageUrl = null,
                    isCurrentUser = false
                )
            } else null,
            isPublic = providerPlaylist.isPublic,
            isCollaborative = false,
            followers = null,
            snapshotId = null,
            tracks = resolvedTracks,
            detailPath = "api/v1/playlists/${providerPlaylist.providerId}"
        )
    }

    private fun buildAlbumItems(serviceId: String, songs: List<StreamingSong>): List<StreamingAlbum> {
        return songs
            .filter { it.album.isNotBlank() }
            .groupBy { song -> song.albumId ?: buildLegacyAlbumId(serviceId, song.artist, song.album) }
            .map { (albumKey, tracks) ->
                val firstSong = tracks.first()
                StreamingAlbum(
                    id = albumKey,
                    title = firstSong.album,
                    artist = firstSong.albumArtist?.takeIf { it.isNotBlank() } ?: firstSong.artist,
                    artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
                    songCount = tracks.size,
                    year = firstSong.releaseDate?.take(4)?.toIntOrNull(),
                    sourceType = serviceToSourceType(serviceId),
                    detailPath = albumKey.substringAfter("::album::", "").takeIf { it.isNotBlank() }?.let { "api/v1/albums/$it" }
                )
            }
            .sortedWith(compareBy<StreamingAlbum> { it.title.lowercase() }.thenBy { it.artist.lowercase() })
    }

    private fun mapProviderAlbum(serviceId: String, providerAlbum: ProviderAlbum): StreamingAlbum {
        return StreamingAlbum(
            id = encodeAlbumId(serviceId, providerAlbum.providerId),
            title = providerAlbum.title,
            artist = providerAlbum.artist,
            artworkUri = providerAlbum.artworkUrl,
            songCount = providerAlbum.songCount,
            year = providerAlbum.year,
            sourceType = serviceToSourceType(serviceId),
            externalId = providerAlbum.providerId,
            releaseDate = null,
            albumType = chromahub.rhythm.app.features.streaming.domain.model.AlbumType.ALBUM,
            genres = emptyList(),
            label = null,
            copyright = null,
            isExplicit = false,
            detailPath = "api/v1/albums/${providerAlbum.providerId}"
        )
    }

    private fun buildArtistItems(serviceId: String, songs: List<StreamingSong>): List<StreamingArtist> {
        // Apply artist separator settings to split collaborations, matching ViewModel behavior
        val separatorEnabled = appSettings.artistSeparatorEnabled.value
        val separatorDelimiters = if (separatorEnabled) {
            appSettings.artistSeparatorDelimiters.value.ifBlank { AppSettings.DEFAULT_ARTIST_SEPARATOR_DELIMITERS }
        } else {
            ""
        }
        
        return songs
            .filter { it.artist.isNotBlank() }
            .flatMap { song ->
                val artistNames = ArtistSeparator.splitArtistNames(
                    song.artist,
                    delimiters = separatorDelimiters,
                    enabled = separatorEnabled
                )
                
                if (artistNames.isEmpty()) {
                    listOf(song to song.artist.trim())
                } else {
                    artistNames.mapNotNull { artistName ->
                        artistName.trim().takeIf { it.isNotBlank() }?.let { trimmedName ->
                            song to trimmedName
                        }
                    }
                }
            }
            .groupBy { (song, artistName) -> "${song.sourceType.name}:${artistName.lowercase()}" }
            .values
            .sortedByDescending { artistSongs -> artistSongs.size }
            .map { artistSongs ->
                val firstSong = artistSongs.first().first
                val artistName = artistSongs.first().second
                val tracks = artistSongs.map { it.first }
                val albumCount = tracks.map { it.album }.distinct().size
                
                // Look up enriched artwork from cache, with fallback to song artwork
                val artworkUri = artistArtworkCache[normalizeKey(artistName)] 
                    ?: tracks.firstNotNullOfOrNull { it.artworkUri }
                
                StreamingArtist(
                    id = buildArtistId(serviceId, artistName),
                    name = artistName,
                    artworkUri = artworkUri,
                    songCount = tracks.size,
                    albumCount = albumCount,
                    sourceType = serviceToSourceType(serviceId),
                    detailPath = "api/v1/artists/${Uri.encode(artistName)}"
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private suspend fun buildDerivedArtistItems(
        serviceId: String,
        songs: List<StreamingSong>
    ): List<StreamingArtist> {
        return enrichArtistsWithDeezerImages(buildArtistItems(serviceId, songs))
    }

    /**
     * Maps a provider artist list on [Dispatchers.Default], resolving cached songs through an
     * [ArtistSongIndex] built once for the batch. Scanning the song cache per artist on the
     * caller's (main) dispatcher cost ~2 s per sync and caused "Input dispatching timed out" ANRs
     * from syncArtists()/searchArtists() on large libraries.
     */
    private suspend fun mapProviderArtists(
        serviceId: String,
        providerArtists: List<ProviderArtist>
    ): List<StreamingArtist> {
        if (providerArtists.isEmpty()) return emptyList()
        // Snapshot the shared caches on the caller's thread; they are not thread-safe.
        val candidateSongs = songsFlow.value.filterIsInstance<StreamingSong>() +
            songCache.values.toList() + downloadedSongsMap.values.toList()
        val separatorEnabled = appSettings.artistSeparatorEnabled.value
        val separatorDelimiters = appSettings.artistSeparatorDelimiters.value
            .ifBlank { AppSettings.DEFAULT_ARTIST_SEPARATOR_DELIMITERS }
        return withContext(Dispatchers.Default) {
            val index = ArtistSongIndex(candidateSongs) { artist ->
                ArtistSeparator.splitArtistNames(artist, delimiters = separatorDelimiters, enabled = separatorEnabled)
            }
            providerArtists.map { mapProviderArtist(serviceId, it, index) }
        }
    }

    private fun mapProviderArtist(
        serviceId: String,
        providerArtist: ProviderArtist,
        artistSongIndex: ArtistSongIndex? = null
    ): StreamingArtist {
        // Only needed when the provider omitted artwork or counts.
        val cachedArtistSongs by lazy {
            artistSongIndex?.songsFor(providerArtist.name) ?: cachedSongsForArtist(providerArtist.name)
        }
        val cachedArtist by lazy {
            buildArtistItems(serviceId, cachedArtistSongs)
                .firstOrNull { it.name.equals(providerArtist.name, ignoreCase = true) }
        }

        return StreamingArtist(
            id = buildArtistId(serviceId, providerArtist.name),
            name = providerArtist.name,
            artworkUri = providerArtist.artworkUrl
                ?: artistArtworkCache[normalizeKey(providerArtist.name)]
                ?: cachedArtist?.artworkUri,
            songCount = providerArtist.songCount.takeIf { it > 0 }
                ?: cachedArtist?.songCount
                ?: cachedArtistSongs.size,
            albumCount = providerArtist.albumCount.takeIf { it > 0 }
                ?: cachedArtist?.albumCount
                ?: cachedArtistSongs.map { it.album }.distinct().size,
            sourceType = serviceToSourceType(serviceId),
            externalId = providerArtist.providerId,
            genres = emptyList(),
            followers = null,
            popularity = null,
            bio = providerArtist.description,
            detailPath = "api/v1/artists/${providerArtist.providerId}"
        )
    }

    private fun artistIdForSong(song: StreamingSong): String {
        val serviceId = decodeSongId(song.id)?.first ?: activeServiceId()
        return buildArtistId(serviceId, song.artist)
    }

    private fun cachedSongsForArtist(artistName: String): List<StreamingSong> {
        if (artistName.isBlank()) {
            return emptyList()
        }

        return (songsFlow.value.filterIsInstance<StreamingSong>() + songCache.values + downloadedSongsMap.values)
            .asSequence()
            .filter { song ->
                if (!song.artist.contains(artistName, ignoreCase = true)) {
                    false
                } else {
                    val artistNames = ArtistSeparator.splitArtistNames(
                        song.artist,
                        delimiters = appSettings.artistSeparatorDelimiters.value.ifBlank { AppSettings.DEFAULT_ARTIST_SEPARATOR_DELIMITERS },
                        enabled = appSettings.artistSeparatorEnabled.value
                    )

                    artistNames.isEmpty() || artistNames.any { it.equals(artistName, ignoreCase = true) }
                }
            }
            .distinctBy { it.id }
            .toList()
    }

    private fun artistIdForAlbum(album: StreamingAlbum): String {
        val serviceId = album.id.substringBefore("::", activeServiceId())
        return buildArtistId(serviceId, album.artist)
    }

    private fun deriveAlbumsFromSongs(
        songs: List<StreamingSong>,
        limit: Int
    ): List<StreamingAlbum> {
        if (songs.isEmpty()) {
            return emptyList()
        }

        return songs
            .filter { it.album.isNotBlank() }
            .groupBy { song -> song.albumId ?: "${song.sourceType.name}:${song.artist.lowercase()}:${song.album.lowercase()}" }
            .values
            .sortedByDescending { albumSongs -> albumSongs.size }
            .take(limit.coerceAtLeast(1))
            .map { albumSongs ->
                val firstSong = albumSongs.first()
                StreamingAlbum(
                    id = firstSong.albumId ?: buildLegacyAlbumId(
                        serviceId = firstSong.sourceType.name,
                        artist = firstSong.artist,
                        album = firstSong.album
                    ),
                    title = firstSong.album,
                    artist = firstSong.albumArtist?.takeIf { it.isNotBlank() } ?: firstSong.artist,
                    artworkUri = albumSongs.firstNotNullOfOrNull { it.artworkUri },
                    songCount = albumSongs.size,
                    year = firstSong.releaseDate?.take(4)?.toIntOrNull(),
                    sourceType = firstSong.sourceType,
                    tracks = albumSongs
                )
            }
    }
    
    /**
     * Legacy matching - kept for backward compatibility with exact ID matching.
     * Use extractArtistNameFromId and match by artist name instead.
     */
    @Deprecated("Use extractArtistNameFromId and match by artist name")
    private fun matchArtistIdByFormat(artistId: String, generatedId: String): Boolean {
        return artistId == generatedId || extractArtistNameFromId(artistId) == extractArtistNameFromId(generatedId)
    }

    override fun buildArtistId(serviceId: String, artist: String): String {
        return "$serviceId::artist::${normalizeKey(artist).replace("/", "_slash_")}"
    }

    private fun buildLegacyAlbumId(serviceId: String, artist: String, album: String): String {
        return "$serviceId::album::${normalizeKey(artist)}::${normalizeKey(album)}"
    }

    private fun decodeAlbumId(albumId: String): DecodedAlbumId? {
        val marker = "::album::"
        val separatorIndex = albumId.indexOf(marker)
        if (separatorIndex <= 0 || separatorIndex >= albumId.length - marker.length) {
            return null
        }

        val serviceId = albumId.substring(0, separatorIndex)
        val payload = albumId.substring(separatorIndex + marker.length)
        if (payload.isBlank()) {
            return null
        }

        val payloadParts = payload.split("::")
        return if (payloadParts.size >= 2) {
            val artist = payloadParts.first().replace("_", " ").trim()
            val album = payloadParts.drop(1).joinToString("::").replace("_", " ").trim()

            if (artist.isBlank() || album.isBlank()) {
                null
            } else {
                DecodedAlbumId(
                    serviceId = serviceId,
                    providerAlbumId = null,
                    title = album,
                    artist = artist,
                    isLegacy = true
                )
            }
        } else {
            val restoredPayload = payload.replace("_slash_", "/")
            DecodedAlbumId(
                serviceId = serviceId,
                providerAlbumId = restoredPayload,
                title = "",
                artist = "",
                isLegacy = false
            )
        }
    }

    private fun encodeAlbumId(serviceId: String, providerAlbumId: String): String {
        val safeProviderId = providerAlbumId.replace("/", "_slash_")
        return "$serviceId::album::$safeProviderId"
    }

    private fun legacyAlbumIdForSong(song: StreamingSong, fallbackServiceId: String?): String {
        val songServiceId = decodeSongId(song.id)?.first ?: fallbackServiceId ?: activeServiceId()
        return buildLegacyAlbumId(songServiceId, song.artist, song.album)
    }

    private data class DecodedAlbumId(
        val serviceId: String,
        val providerAlbumId: String?,
        val title: String,
        val artist: String,
        val isLegacy: Boolean
    )

    @Deprecated("Use encodeAlbumId(serviceId, providerAlbumId) for streaming album IDs")
    private fun buildAlbumId(serviceId: String, artist: String, album: String): String {
        return buildLegacyAlbumId(serviceId, artist, album)
    }

    @Deprecated("Use decodeAlbumId(albumId) and provider album IDs")
    private fun decodeLegacyAlbumId(albumId: String): Triple<String, String, String>? {
        val decoded = decodeAlbumId(albumId)
        if (decoded == null || !decoded.isLegacy) {
            return null
        }

        return Triple(decoded.serviceId, decoded.title, decoded.artist)
    }

    private fun normalizeKey(value: String): String {
        return value.trim().lowercase().replace(WHITESPACE_REGEX, "_")
    }

    private fun trimSongCache() {
        if (songCache.size <= MAX_CACHE_SIZE) return

        val toRemove = songCache.size - MAX_CACHE_SIZE
        repeat(toRemove) {
            val firstKey = songCache.entries.firstOrNull()?.key ?: return
            songCache.remove(firstKey)
        }
    }

    private fun activeServiceId(): String {
        return normalizeServiceId(appSettings.streamingService.value)
    }

    private fun encodeSongId(serviceId: String, providerId: String): String {
        return "$serviceId::$providerId"
    }

    private fun decodeSongId(songId: String): Pair<String, String>? {
        val separatorIndex = songId.indexOf("::")
        if (separatorIndex <= 0 || separatorIndex >= songId.length - 2) {
            return null
        }

        val serviceId = songId.substring(0, separatorIndex)
        val providerId = songId.substring(separatorIndex + 2)
        return serviceId to providerId
    }

    private fun decodePlaylistId(playlistId: String): Pair<String, String>? {
        val marker = "::playlist::"
        val separatorIndex = playlistId.indexOf(marker)
        if (separatorIndex <= 0 || separatorIndex >= playlistId.length - marker.length) {
            return null
        }

        val serviceId = playlistId.substring(0, separatorIndex)
        val providerId = playlistId.substring(separatorIndex + marker.length)
        if (providerId.isBlank()) {
            return null
        }

        return serviceId to providerId
    }

    private fun encodePlaylistId(serviceId: String, providerId: String): String {
        return "$serviceId::playlist::$providerId"
    }

    private fun desiredBitrateKbps(): Int {
        return when (appSettings.streamingQuality.value.uppercase()) {
            "LOW" -> 96
            "NORMAL" -> 160
            "HIGH" -> 320
            "LOSSLESS" -> 0   // No cap — server streams the original file at native quality
            else -> 0          // Unknown/future values also default to lossless (highest available)
        }
    }
    
    /**
     * Validate if the requested quality is supported by the provider.
     * Some providers may not support all quality levels.
     */
    private fun isSupportedQuality(quality: String): Boolean {
        // All major providers (Subsonic/Navidrome, Jellyfin) support these quality levels
        return quality.uppercase() in listOf("LOW", "NORMAL", "HIGH", "LOSSLESS")
    }
    
    /**
     * Get the fallback quality if the requested one is not supported.
     */
    private fun getFallbackQuality(unsupportedQuality: String): String {
        return when (unsupportedQuality.uppercase()) {
            else -> "HIGH" // Default fallback
        }
    }
    private fun resolveCookieInput(username: String, password: String): String {
        return when {
            looksLikeCookieBlob(password) -> password
            looksLikeCookieBlob(username) -> username
            else -> password
        }
    }

    private fun looksLikeCookieBlob(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("{") || trimmed.contains('=')
    }

    private fun serviceToSourceType(serviceId: String): SourceType {
        return when (serviceId) {
            StreamingServiceId.CUSTOM_API -> SourceType.CUSTOM_API
            else -> SourceType.CUSTOM_API
        }
    }

    private fun normalizeServiceId(serviceId: String): String {
        val normalized = serviceId.uppercase()
        return if (StreamingServiceId.all.contains(normalized)) {
            normalized
        } else {
            StreamingServiceId.CUSTOM_API
        }
    }

    suspend fun enrichArtistsWithDeezerImages(artists: List<StreamingArtist>): List<StreamingArtist> {
        return withContext(Dispatchers.IO) {
            try {
                val deezerService = NetworkClient.deezerApiService
                if (deezerService == null || !NetworkClient.isDeezerApiEnabled()) {
                    Log.d("StreamingMusicRepo", "Deezer API service not available or disabled in settings, skipping enrichment")
                    return@withContext artists
                }

                val enriched = mutableListOf<StreamingArtist>()
                var lookups = 0

                deezerEnrichmentMutex.withLock {
                    try {
                        for (artist in artists) {
                            if (artist.name.isBlank() || artist.name.equals("Unknown", ignoreCase = true)) {
                                enriched.add(artist)
                                continue
                            }

                            val key = normalizeKey(artist.name)
                            val cached = deezerArtistImageCache.get(key)
                            val imageUrl = if (cached != null) {
                                cached.imageUrl
                            } else {
                                lookups++
                                lookUpDeezerArtistImage(deezerService, artist.name, key)
                            }
                            val enrichedArtist = if (imageUrl != null) artist.copy(artworkUri = imageUrl) else artist

                            enrichedArtist.artworkUri?.takeIf { it.isNotBlank() }?.let { cachedUri ->
                                artistArtworkCache[key] = cachedUri
                            }
                            enriched.add(enrichedArtist)
                        }
                    } finally {
                        deezerArtistImageCache.flush()
                    }
                }

                if (lookups > 0) {
                    Log.d("StreamingMusicRepo", "Deezer artist enrichment: $lookups lookups for ${artists.size} artists")
                }
                enriched
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StreamingMusicRepo", "Error enriching artists with Deezer images", e)
                artists
            }
        }
    }

    private suspend fun lookUpDeezerArtistImage(
        deezerService: DeezerApiService,
        artistName: String,
        key: String
    ): String? {
        return try {
            val searchResponse = deezerService.searchArtists(artistName, limit = 5)
            val deezerArtist = searchResponse.data.firstOrNull {
                it.name.equals(artistName, ignoreCase = true)
            } ?: searchResponse.data.firstOrNull()

            val imageUrl = deezerArtist?.let {
                listOf(it.pictureXl, it.pictureBig, it.pictureMedium, it.picture)
                    .firstOrNull { url -> !url.isNullOrEmpty() }
            }
            deezerArtistImageCache.put(key, imageUrl)
            imageUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("StreamingMusicRepo", "Failed to fetch Deezer image for $artistName: ${e.message}")
            deezerArtistImageCache.markFailed(key)
            null
        }
    }

    fun updateArtistsFlow(enrichedArtists: List<StreamingArtist>) {
        artistsFlow.value = enrichedArtists
    }

    override suspend fun syncArtists(): List<StreamingArtist> {
        val serviceId = activeServiceId()
        if (!isServiceConnected(serviceId)) return emptyList()

        return try {
            val providerArtists = withContext(Dispatchers.IO) {
                firebaseClient.getArtists().getOrNull()
            }
            providerArtists?.forEach { artist ->
                if (!artist.artworkUrl.isNullOrBlank()) {
                    artistArtworkCache[normalizeKey(artist.name)] = artist.artworkUrl
                }
            }
            if (!providerArtists.isNullOrEmpty()) {
                val directArtists = mapProviderArtists(serviceId, providerArtists)
                artistsFlow.value = directArtists
                updateFollowedArtistsFlow()
                Log.d("StreamingMusicRepo", "Synced ${directArtists.size} artists directly from provider")
                directArtists
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w("StreamingMusicRepo", "Failed to sync artists from provider", e)
            emptyList()
        }
    }

    private suspend fun syncArtistArtworkCache(serviceId: String) {
        syncArtists()
    }

    // -------------------------------------------------------------------------
    // Firebase Account, Moderation, Vouchers & Store Capabilities
    // -------------------------------------------------------------------------

    override suspend fun getSiteInfo(): Result<CustomSiteInfo> = Result.success(firebaseClient.getSiteInfo())

    override suspend fun getUserProfile(): Result<CustomUserProfile> = firebaseClient.getProfile()

    override suspend fun updateUserProfile(displayName: String, email: String?): Result<CustomUserProfile> =
        firebaseClient.updateProfile(displayName, email)

    override suspend fun getCloudSettings(): Result<CustomUserSettings> = firebaseClient.getUserSettings()

    override suspend fun updateCloudSettings(settings: CustomUserSettings): Result<CustomUserSettings> =
        firebaseClient.updateUserSettings(settings)

    override suspend fun getBlockedSongs(): Result<List<BlockedSongItem>> {
        val result = firebaseClient.getBlockedSongs()
        result.getOrNull()?.let { list ->
            blockedSongIds.clear()
            list.forEach { blockedSongIds.add(it.songId) }
        }
        return result
    }

    override suspend fun blockSong(songId: String, reason: String?): Result<Boolean> {
        val cleanId = decodeSongId(songId)?.second ?: songId.substringAfterLast("::")
        val result = firebaseClient.blockSong(cleanId, reason)
        if (result.isSuccess) {
            blockedSongIds.add(cleanId)
            songsFlow.value = songsFlow.value.filterNot { isBlocked(it.id) }
            songCache.entries.removeAll { isBlocked(it.key) }
        }
        return result
    }

    override suspend fun unblockSong(songId: String): Result<Boolean> {
        val cleanId = decodeSongId(songId)?.second ?: songId.substringAfterLast("::")
        val result = firebaseClient.unblockSong(cleanId)
        if (result.isSuccess) {
            blockedSongIds.remove(cleanId)
        }
        return result
    }

    override suspend fun reportSong(songId: String, reason: String, details: String?): Result<Boolean> {
        val cleanId = decodeSongId(songId)?.second ?: songId.substringAfterLast("::")
        return firebaseClient.reportSong(cleanId, reason, details)
    }

    override suspend fun getSongMeta(songId: String): Result<CustomSongMeta> {
        val cleanId = decodeSongId(songId)?.second ?: songId.substringAfterLast("::")
        return firebaseClient.getSongDetail(cleanId)
    }

    override suspend fun purchaseSong(songId: String, voucherCode: String?): Result<PurchaseRecord> {
        val cleanId = decodeSongId(songId)?.second ?: songId.substringAfterLast("::")
        val result = firebaseClient.purchaseSong(cleanId, voucherCode)
        if (result.isSuccess) {
            val encodedId = encodeSongId(activeServiceId(), cleanId)
            val unlockedUrl = firebaseClient.getStreamingUrl(cleanId)
            songCache[encodedId]?.let { cached ->
                songCache[encodedId] = cached.copy(
                    isPurchased = true,
                    streamingUrl = unlockedUrl?.takeIf { it.isNotBlank() } ?: cached.streamingUrl
                )
            }
            val currentList = songsFlow.value
            if (currentList.isNotEmpty()) {
                songsFlow.value = currentList.map { item ->
                    if (item is StreamingSong && (item.id == encodedId || item.externalId == cleanId)) {
                        item.copy(
                            isPurchased = true,
                            streamingUrl = unlockedUrl?.takeIf { it.isNotBlank() } ?: item.streamingUrl
                        )
                    } else {
                        item
                    }
                }
            }
            repositoryScope.launch {
                runCatching {
                    localCatalogStore.updatePurchased(
                        songId = encodedId,
                        isPurchased = true,
                        streamingUrl = unlockedUrl?.takeIf { it.isNotBlank() }
                    )
                }
            }
            saveCatalogCache(activeServiceId())
        }
        return result
    }

    override suspend fun getPurchases(): Result<List<PurchaseRecord>> = firebaseClient.getPurchases()

    override suspend fun getVouchers(): Result<List<VoucherInfo>> = firebaseClient.getVouchers()

    override suspend fun validateVoucher(code: String, songId: String): Result<VoucherValidationResult> {
        val cleanId = decodeSongId(songId)?.second ?: songId.substringAfterLast("::")
        return firebaseClient.validateVoucher(code, cleanId)
    }

    override fun getStatsAccountKey(): String = firebaseClient.getStatsAccountKey()

    override suspend fun fetchCloudListeningStats(forceRefresh: Boolean): CloudListeningStats? =
        firebaseClient.fetchListeningStats(forceRefresh = forceRefresh)

    override suspend fun syncCloudListeningStats(stats: CloudListeningStats): Boolean =
        firebaseClient.syncListeningStats(stats)

    override suspend fun getWeeklyPlayCounts(weekKey: String): Map<String, Long> {
        if (appSettings.offlineMode.value || !isServiceConnected(activeServiceId())) {
            return emptyMap()
        }
        return firebaseClient.getWeeklyPlayCounts(weekKey)
    }

    private companion object {
        private const val SEARCH_LIMIT = 100
        private const val MAX_CACHE_SIZE = 10_000
        private const val CATALOG_TTL_MS = 6L * 60 * 60 * 1000L

        /** Compiled once; normalizeKey() runs per artist during catalog mapping. */
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }
}
