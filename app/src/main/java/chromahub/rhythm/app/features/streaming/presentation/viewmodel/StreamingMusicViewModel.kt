/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chromahub.rhythm.app.core.domain.model.SourceType
import chromahub.rhythm.app.core.domain.model.StreamingConfig
import chromahub.rhythm.app.core.domain.model.StreamingQuality
import chromahub.rhythm.app.core.utils.NetworkUtils
import chromahub.rhythm.app.features.streaming.data.provider.BlockedSongItem
import chromahub.rhythm.app.features.streaming.data.provider.CustomSiteInfo
import chromahub.rhythm.app.features.streaming.data.provider.CustomSongMeta
import chromahub.rhythm.app.features.streaming.data.provider.CustomUserProfile
import chromahub.rhythm.app.features.streaming.data.provider.CustomUserSettings
import chromahub.rhythm.app.features.streaming.data.provider.FirebaseProviderException
import chromahub.rhythm.app.features.streaming.data.provider.PurchaseRecord
import chromahub.rhythm.app.features.streaming.data.provider.VoucherInfo
import chromahub.rhythm.app.features.streaming.data.provider.VoucherValidationResult
import chromahub.rhythm.app.features.streaming.data.provider.XuanKenFirebaseClient
import chromahub.rhythm.app.network.XkMusicApiService
import chromahub.rhythm.app.network.XuanKenFirebaseService
import chromahub.rhythm.app.features.streaming.data.repository.StreamingMusicRepositoryImpl
import chromahub.rhythm.app.features.streaming.data.repository.StreamingServiceSession
import chromahub.rhythm.app.features.streaming.data.repository.StreamingServiceSessionRepository
import chromahub.rhythm.app.features.streaming.data.repository.WeeklyChartEntry
import chromahub.rhythm.app.features.streaming.data.repository.WeeklyChartPeriodInfo
import chromahub.rhythm.app.features.streaming.data.repository.WeeklyChartsTracker
import chromahub.rhythm.app.features.streaming.di.StreamingMusicModule
import chromahub.rhythm.app.features.streaming.domain.model.BrowseCategory
import chromahub.rhythm.app.features.streaming.domain.model.StreamingAlbum
import chromahub.rhythm.app.features.streaming.domain.model.StreamingArtist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingPlaylist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingServiceId
import chromahub.rhythm.app.features.streaming.domain.model.StreamingServiceRules
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.features.streaming.domain.repository.StreamingMusicRepository
import chromahub.rhythm.app.features.streaming.infrastructure.notification.StreamingNotificationManager
import chromahub.rhythm.app.features.streaming.infrastructure.notification.SyncProgressThrottle
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.util.ArtistSeparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.net.Uri
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.R
import android.util.Log
import androidx.core.net.toUri
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel for managing streaming music playback and library.
 * Handles authentication, browsing, and playback for streaming services.
 */
class StreamingMusicViewModel(application: Application) : AndroidViewModel(application) {
    private val appSettings = AppSettings.getInstance(application)

    companion object {
        private const val AUTH_PING_RETRIES = 3
        private const val AUTH_PING_RETRY_DELAY_MS = 800L
    }
    private val serviceSessionRepository = StreamingServiceSessionRepository(application)
    val repository = StreamingMusicModule.provideStreamingMusicRepository(application)
    private val providerRepository = repository as? StreamingMusicRepositoryImpl
    private val notificationManager = StreamingNotificationManager(application)
    private var playbackHandler: ((List<StreamingSong>, Int) -> Unit)? = null
    private var seekProgressHandler: ((Float) -> Unit)? = null
    private var seekPositionHandler: ((Long) -> Unit)? = null
    private var wasOffline: Boolean? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkLostJob: Job? = null
    private var networkAvailableJob: Job? = null
    private val authMutex = Mutex()
    private var lastSuccessfulAuthTimestamp = 0L
    private val weeklyChartsTracker = WeeklyChartsTracker(application)
    private val playbackStatsRepository = chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository.getInstance(application)
    private val statsSyncMutex = Mutex()
    private var cloudStatsUploadJob: Job? = null
    @Volatile
    private var lastPulledStatsAccountKey: String? = null
    @Volatile
    private var latestServerWeeklyPlays: Map<String, Long> = emptyMap()

    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(getApplication(), message, duration).show()
        }
    }

    fun showToast(resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(getApplication(), resId, duration).show()
        }
    }

    private fun showStatusToast(resId: Int) {
        if (appSettings.appMode.value != "STREAMING") return
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(getApplication(), resId, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        val previous = wasOffline
        if (previous == null) {
            wasOffline = !isOnline
            return
        }
        if (previous && isOnline) {
            wasOffline = false
            showStatusToast(R.string.rhythm_go_online_toast)
        } else if (!previous && !isOnline) {
            wasOffline = true
            showStatusToast(R.string.rhythm_go_offline_toast)
        }
    }

    fun switchToDownloadedMode() {
        _isAuthenticated.value = false
        notificationManager.cancelSyncNotification()
        val downloaded = _downloadedSongs.value
        val downloadedAlb = if (_downloadedAlbums.value.isNotEmpty()) _downloadedAlbums.value else deriveAlbumsFromSongs(downloaded, limit = 500)
        val downloadedArt = if (_downloadedArtists.value.isNotEmpty()) _downloadedArtists.value else deriveArtistsFromSongs(downloaded, limit = 500)
        
        _downloadedSongs.value = downloaded
        _downloadedAlbums.value = downloadedAlb
        _downloadedArtists.value = downloadedArt
        _allSongs.value = downloaded
        _savedAlbums.value = downloadedAlb
        _newReleases.value = downloadedAlb
        _followedArtists.value = downloadedArt
        _recommendations.value = downloaded.shuffled().take(24)
        _savedPlaylists.value = emptyList()
        _likedSongs.value = emptyList()
        _isLoading.value = false
        _syncProgress.value = StreamingSyncProgress(isSyncing = false, stage = StreamingSyncStage.Idle)
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networkLostJob?.cancel()
                    networkAvailableJob?.cancel()
                    _isOnline.value = true
                    updateOnlineStatus(true)
                    if (appSettings.appMode.value != "STREAMING" || appSettings.offlineMode.value) return

                    networkAvailableJob = viewModelScope.launch {
                        delay(1000)
                        if (!NetworkUtils.isNetworkAvailable(getApplication())) return@launch
                        val serviceId = appSettings.streamingService.value
                        if (serviceSessionRepository.isConnected(serviceId) || providerRepository?.isServiceConnected(serviceId) == true) {
                            val connected = checkAndSyncAuthentication(serviceId, forceCheck = true)
                            if (connected) {
                                loadHomeContent()
                                loadLibrary()
                            }
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (hasInternet) {
                        networkLostJob?.cancel()
                        if (!_isOnline.value) {
                            _isOnline.value = true
                            updateOnlineStatus(true)
                        }
                    } else {
                        networkAvailableJob?.cancel()
                        _isOnline.value = false
                        updateOnlineStatus(false)
                        if (appSettings.offlineMode.value) {
                            switchToDownloadedMode()
                        } else {
                            _isAuthenticated.value = false
                        }
                    }
                }

                override fun onLost(network: Network) {
                    networkAvailableJob?.cancel()
                    if (NetworkUtils.isNetworkAvailable(getApplication())) {
                        return
                    }
                    _isOnline.value = false
                    updateOnlineStatus(false)
                    if (appSettings.offlineMode.value) {
                        switchToDownloadedMode()
                    } else {
                        _isAuthenticated.value = false
                    }
                }
            }
            networkCallback = callback
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("StreamingMusicViewModel", "Failed to register network callback", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            networkCallback?.let {
                val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            // Ignore callback unregister error
        }
    }

    
    // Network & Authentication state
    private val _isOnline = MutableStateFlow(NetworkUtils.isNetworkAvailable(application))
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    val serviceSessions: StateFlow<Map<String, StreamingServiceSession>> = serviceSessionRepository.sessions
    
    private val _currentService = MutableStateFlow(SourceType.CUSTOM_API)
    val currentService: StateFlow<SourceType> = _currentService.asStateFlow()

    private val _streamingConfig = MutableStateFlow(StreamingConfig())
    val streamingConfig: StateFlow<StreamingConfig> = _streamingConfig.asStateFlow()

    // Custom API Account, Site, Store & Moderation State
    private val _siteInfo = MutableStateFlow<CustomSiteInfo?>(null)
    val siteInfo: StateFlow<CustomSiteInfo?> = _siteInfo.asStateFlow()

    private val _userProfile = MutableStateFlow<CustomUserProfile?>(null)
    val userProfile: StateFlow<CustomUserProfile?> = _userProfile.asStateFlow()

    private val _cloudSettings = MutableStateFlow(CustomUserSettings())
    val cloudSettings: StateFlow<CustomUserSettings> = _cloudSettings.asStateFlow()

    private val _blockedSongs = MutableStateFlow<List<BlockedSongItem>>(emptyList())
    val blockedSongs: StateFlow<List<BlockedSongItem>> = _blockedSongs.asStateFlow()

    private val _purchases = MutableStateFlow<List<PurchaseRecord>>(emptyList())
    val purchases: StateFlow<List<PurchaseRecord>> = _purchases.asStateFlow()

    private val _vouchers = MutableStateFlow<List<VoucherInfo>>(emptyList())
    val vouchers: StateFlow<List<VoucherInfo>> = _vouchers.asStateFlow()

    private val _newestSongs = MutableStateFlow<List<StreamingSong>>(emptyList())
    val newestSongs: StateFlow<List<StreamingSong>> = _newestSongs.asStateFlow()
    
    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _syncProgress = MutableStateFlow(StreamingSyncProgress())
    val syncProgress: StateFlow<StreamingSyncProgress> = _syncProgress.asStateFlow()

    private val _hasLoadedHomeContent = MutableStateFlow(false)
    val hasLoadedHomeContent: StateFlow<Boolean> = _hasLoadedHomeContent.asStateFlow()

    private val _hasLoadedLibrary = MutableStateFlow(false)
    val hasLoadedLibrary: StateFlow<Boolean> = _hasLoadedLibrary.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Home content
    private val _recommendations = MutableStateFlow<List<StreamingSong>>(emptyList())
    val recommendations: StateFlow<List<StreamingSong>> = _recommendations.asStateFlow()
    
    private val _newReleases = MutableStateFlow<List<StreamingAlbum>>(emptyList())
    val newReleases: StateFlow<List<StreamingAlbum>> = _newReleases.asStateFlow()
    
    private val _featuredPlaylists = MutableStateFlow<List<StreamingPlaylist>>(emptyList())
    val featuredPlaylists: StateFlow<List<StreamingPlaylist>> = _featuredPlaylists.asStateFlow()
    
    // Browse content
    private val _browseCategories = MutableStateFlow<List<BrowseCategory>>(emptyList())
    val browseCategories: StateFlow<List<BrowseCategory>> = _browseCategories.asStateFlow()
    
    private val _topCharts = MutableStateFlow<List<StreamingSong>>(emptyList())
    val topCharts: StateFlow<List<StreamingSong>> = _topCharts.asStateFlow()

    private val _weeklyCharts = MutableStateFlow<List<WeeklyChartEntry>>(emptyList())
    val weeklyCharts: StateFlow<List<WeeklyChartEntry>> = _weeklyCharts.asStateFlow()

    private val _weeklyChartPeriod = MutableStateFlow(weeklyChartsTracker.getCurrentPeriodInfo())
    val weeklyChartPeriod: StateFlow<WeeklyChartPeriodInfo> = _weeklyChartPeriod.asStateFlow()
    
    // Library content
    private val _likedSongs = MutableStateFlow<List<StreamingSong>>(emptyList())
    val likedSongs: StateFlow<List<StreamingSong>> = _likedSongs.asStateFlow()
    
    private val _savedAlbums = MutableStateFlow<List<StreamingAlbum>>(emptyList())
    val savedAlbums: StateFlow<List<StreamingAlbum>> = _savedAlbums.asStateFlow()
    
    private val _followedArtists = MutableStateFlow<List<StreamingArtist>>(emptyList())
    val followedArtists: StateFlow<List<StreamingArtist>> = _followedArtists.asStateFlow()
    
    private val _savedPlaylists = MutableStateFlow<List<StreamingPlaylist>>(emptyList())
    val savedPlaylists: StateFlow<List<StreamingPlaylist>> = _savedPlaylists.asStateFlow()
    
    private val _downloadedSongs = MutableStateFlow<List<StreamingSong>>(emptyList())
    val downloadedSongs: StateFlow<List<StreamingSong>> = _downloadedSongs.asStateFlow()

    private val _downloadedAlbums = MutableStateFlow<List<StreamingAlbum>>(emptyList())
    val downloadedAlbums: StateFlow<List<StreamingAlbum>> = _downloadedAlbums.asStateFlow()

    private val _downloadedArtists = MutableStateFlow<List<StreamingArtist>>(emptyList())
    val downloadedArtists: StateFlow<List<StreamingArtist>> = _downloadedArtists.asStateFlow()

    private val _downloadingSongIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingSongIds: StateFlow<Set<String>> = _downloadingSongIds.asStateFlow()

    // All provider songs (full catalog exposed by repository)
    private val _allSongs = MutableStateFlow<List<StreamingSong>>(emptyList())
    val allSongs: StateFlow<List<StreamingSong>> = _allSongs.asStateFlow()
    
    // Current playback state
    private val _currentSong = MutableStateFlow<StreamingSong?>(null)
    val currentSong: StateFlow<StreamingSong?> = _currentSong.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    
    // Queue
    private val _queue = MutableStateFlow<List<StreamingSong>>(emptyList())
    val queue: StateFlow<List<StreamingSong>> = _queue.asStateFlow()
    
    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _searchResults = MutableStateFlow<StreamingSearchResults>(StreamingSearchResults())
    val searchResults: StateFlow<StreamingSearchResults> = _searchResults.asStateFlow()
    
    init {
        switchLocalStatsScope(repository.getStatsAccountKey())
        observeSelectedService()
        registerNetworkCallback()
        observeListeningStatsForCloudSync()
        // Keep an updated view of the provider catalog exposed by the repository
        viewModelScope.launch {
            repository.getSongs().collect { items ->
                _allSongs.value = items.filterIsInstance<StreamingSong>()
            }
        }
        // Keep an updated view of provider artists
        viewModelScope.launch {
            repository.getArtists().collect { items ->
                val artists = items.filterIsInstance<StreamingArtist>()
                if (artists.isNotEmpty() && _followedArtists.value.isEmpty()) {
                    _followedArtists.value = artists
                }
            }
        }
        // Keep an updated view of provider albums
        viewModelScope.launch {
            repository.getAlbums().collect { items ->
                val albums = items.filterIsInstance<StreamingAlbum>()
                if (albums.isNotEmpty() && _savedAlbums.value.isEmpty()) {
                    _savedAlbums.value = albums
                }
            }
        }
        // Keep an updated view of downloaded songs
        viewModelScope.launch {
            repository.getDownloadedSongs().collect { items ->
                val downloaded = items.filterIsInstance<StreamingSong>()
                _downloadedSongs.value = downloaded
                _downloadedAlbums.value = deriveAlbumsFromSongs(downloaded, limit = 500)
                _downloadedArtists.value = deriveArtistsFromSongs(downloaded, limit = 500)
            }
        }
        // React immediately to offline mode toggles
        viewModelScope.launch {
            appSettings.offlineMode.drop(1).collect { isOffline ->
                if (isOffline) {
                    switchToDownloadedMode()
                } else {
                    if (NetworkUtils.isNetworkAvailable(getApplication())) {
                        val serviceId = appSettings.streamingService.value
                        if (serviceSessionRepository.isConnected(serviceId) || providerRepository?.isServiceConnected(serviceId) == true) {
                            val connected = checkAndSyncAuthentication(serviceId, forceCheck = true)
                            if (connected) {
                                loadHomeContent()
                                loadLibrary()
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            appSettings.appMode.drop(1).collect { mode ->
                if (mode == "STREAMING") {
                    if (NetworkUtils.isNetworkAvailable(getApplication()) && !appSettings.offlineMode.value) {
                        val serviceId = appSettings.streamingService.value
                        if (serviceSessionRepository.isConnected(serviceId) || providerRepository?.isServiceConnected(serviceId) == true) {
                            val connected = checkAndSyncAuthentication(serviceId, forceCheck = true)
                            if (connected) {
                                loadHomeContent()
                                loadLibrary()
                            } else {
                                loadHomeContent()
                                loadLibrary()
                            }
                        } else {
                            loadHomeContent()
                            loadLibrary()
                        }
                    } else if (appSettings.offlineMode.value) {
                        switchToDownloadedMode()
                    } else {
                        loadHomeContent()
                        loadLibrary()
                    }
                } else {
                    notificationManager.cancelSyncNotification()
                }
            }
        }

        viewModelScope.launch {
            appSettings.streamingQuality.drop(1).collect { qualityName ->
                val quality = try {
                    StreamingQuality.valueOf(qualityName.uppercase())
                } catch (_: Exception) {
                    StreamingQuality.HIGH
                }
                if (_streamingConfig.value.streamingQuality != quality) {
                    _streamingConfig.value = _streamingConfig.value.copy(streamingQuality = quality)
                    refreshCurrentSession()
                    refreshCurrentPlaybackQueue()
                }
            }
        }
    }

    private suspend fun hydrateCatalogFromLocalCache(): Boolean {
        repository.awaitCatalogCacheLoaded()
        if (!repository.hasCachedCatalog()) return false
        val likedSongs = runCatching { repository.getLikedSongs().first() }.getOrDefault(emptyList())
        val followedArtists = runCatching { repository.getFollowedArtists().first() }.getOrDefault(emptyList())
        val downloadedSongs = runCatching { repository.getDownloadedSongs().first() }.getOrDefault(emptyList())
        val savedPlaylists = runCatching { repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>() }.getOrDefault(emptyList())
        val savedAlbums = runCatching { repository.getSavedAlbums().first() }.getOrDefault(emptyList())
        val catalogAlbums = runCatching { repository.getAlbums().first().filterIsInstance<StreamingAlbum>() }.getOrDefault(emptyList())
        val catalogSongs = runCatching { repository.getSongs().first().filterIsInstance<StreamingSong>() }.getOrDefault(emptyList())
        val catalogArtists = runCatching {
            repository.getArtists().first().filterIsInstance<StreamingArtist>().distinctBy { it.id }
        }.getOrDefault(emptyList())
        val newestLocal = runCatching { repository.getNewestSongs(limit = 5) }.getOrDefault(emptyList())

        _likedSongs.value = likedSongs
        _savedAlbums.value = savedAlbums.ifEmpty { catalogAlbums }
        if (_newReleases.value.isEmpty() && (savedAlbums.isNotEmpty() || catalogAlbums.isNotEmpty())) {
            _newReleases.value = catalogAlbums.ifEmpty { savedAlbums }
        }
        _followedArtists.value = catalogArtists.ifEmpty { followedArtists }
        _savedPlaylists.value = savedPlaylists
        if (_featuredPlaylists.value.isEmpty()) {
            _featuredPlaylists.value = savedPlaylists
        }
        _downloadedSongs.value = downloadedSongs
        _downloadedAlbums.value = deriveAlbumsFromSongs(downloadedSongs, limit = 500)
        _downloadedArtists.value = deriveArtistsFromSongs(downloadedSongs, limit = 500)
        if (catalogSongs.isNotEmpty()) {
            _allSongs.value = catalogSongs
            if (_recommendations.value.isEmpty()) {
                _recommendations.value = catalogSongs.shuffled().take(24)
            }
            if (_newestSongs.value.isEmpty()) {
                _newestSongs.value = newestLocal.ifEmpty {
                    catalogSongs.sortedByDescending { it.createdAtMs }.take(5)
                }
            }
        }
        _hasLoadedLibrary.value = true
        if (catalogSongs.isNotEmpty()) {
            _hasLoadedHomeContent.value = true
        }
        return catalogSongs.isNotEmpty()
    }

    private fun observeSelectedService() {
        viewModelScope.launch {
            appSettings.streamingService.collect { serviceId ->
                val normalizedServiceId = normalizeServiceId(serviceId)
                if (normalizedServiceId != serviceId) {
                    appSettings.setStreamingService(normalizedServiceId)
                    return@collect
                }

                _currentService.value = sourceTypeFromServiceId(normalizedServiceId)

                if (appSettings.appMode.value == "STREAMING") {
                    if (appSettings.offlineMode.value) {
                        switchToDownloadedMode()
                    } else {
                        val hydrated = hydrateCatalogFromLocalCache()
                        if (NetworkUtils.isNetworkAvailable(getApplication())) {
                            val connected = checkAndSyncAuthentication(normalizedServiceId)
                            if (connected || hydrated) {
                                loadHomeContent()
                                loadLibrary(forceSync = !hydrated)
                            }
                        } else if (hydrated) {
                            _isOnline.value = false
                            _isLoading.value = false
                            refreshWeeklyCharts(fetchRemote = false)
                        } else {
                            switchToDownloadedMode()
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Check if user is authenticated with the current service.
     */
    private fun checkAuthenticationStatus() {
        viewModelScope.launch {
            checkAndSyncAuthentication()
        }
    }
    
    /**
     * Select a streaming service.
     */
    fun selectService(service: SourceType) {
        viewModelScope.launch {
            val serviceId = serviceIdFromSourceType(service)
            _currentService.value = service
            if (appSettings.streamingService.value != serviceId) {
                appSettings.setStreamingService(serviceId)
            }
            checkAndSyncAuthentication(serviceId)
        }
    }
    
    /**
     * Authenticate with the current streaming service.
     */
    fun authenticate() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                checkAndSyncAuthentication()
                if (!_isAuthenticated.value) {
                    _error.value = "Vui lòng kiểm tra kết nối mạng hoặc đăng nhập tài khoản"
                }
            } catch (e: Exception) {
                _error.value = "Xác thực thất bại: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Log out from the current streaming service.
     */
    fun logout() {
        val selectedService = appSettings.streamingService.value
        disconnectService(selectedService)
    }

    fun connectService(serviceId: String, serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val normalizedServiceId = normalizeServiceId(serviceId)
                validateCredentials(normalizedServiceId, serverUrl, username, password)
                pushActiveAccountStatsToCloud()

                val connection = providerRepository?.connect(
                    serviceId = normalizedServiceId,
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    saveCredentials = appSettings.rememberStreamingPasswords.value
                ) ?: throw IllegalStateException("Chưa khởi tạo kho nhạc trực tuyến")

                serviceSessionRepository.connect(
                    serviceId = normalizedServiceId,
                    serverUrl = connection.serverUrl.trim(),
                    username = connection.displayName.trim()
                )
                if (appSettings.streamingService.value != normalizedServiceId) {
                    appSettings.setStreamingService(normalizedServiceId)
                }
                lastPulledStatsAccountKey = null
                checkAndSyncAuthentication(normalizedServiceId, forceCheck = true)
                loadHomeContent()
                loadLibrary(forceSync = true)
                
                // Show success notification
                notificationManager.notifyAuthenticationSuccess(getSourceTypeName(sourceTypeFromServiceId(normalizedServiceId)))
            } catch (e: Exception) {
                val userMessage = when {
                    e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException ||
                    (e is java.io.IOException && (e.message?.contains("connect", ignoreCase = true) == true ||
                                                  e.message?.contains("timeout", ignoreCase = true) == true)) ||
                    e.cause is java.net.ConnectException ||
                    e.cause is java.net.SocketTimeoutException ->
                        "Không thể kết nối đến máy chủ XK Music. Vui lòng kiểm tra lại kết nối mạng."
                    e is java.net.UnknownHostException ||
                    e.cause is java.net.UnknownHostException ->
                        "Không có kết nối mạng. Không thể tìm thấy máy chủ."
                    e is javax.net.ssl.SSLException ||
                    e.cause is javax.net.ssl.SSLException ->
                        "Kết nối bảo mật SSL thất bại."
                    e is FirebaseProviderException && e.code == 401 ->
                        e.message ?: "Email hoặc mật khẩu không chính xác."
                    e is FirebaseProviderException ->
                        e.message ?: "Yêu cầu xác thực thất bại (${e.code})."
                    else -> e.message ?: "Kết nối thất bại"
                }
                _error.value = userMessage
                notificationManager.notifyAuthenticationFailed(getSourceTypeName(_currentService.value))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun registerService(
        serviceId: String,
        email: String,
        password: String,
        displayName: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val normalizedServiceId = normalizeServiceId(serviceId)
                val cleanEmail = email.trim()
                if (cleanEmail.isBlank() || password.length < 6) {
                    throw IllegalArgumentException("Vui lòng nhập email hợp lệ và mật khẩu tối thiểu 6 ký tự.")
                }

                val connection = providerRepository?.register(
                    serviceId = normalizedServiceId,
                    email = cleanEmail,
                    password = password,
                    displayName = displayName.trim(),
                    saveCredentials = appSettings.rememberStreamingPasswords.value
                ) ?: throw IllegalStateException("Chưa khởi tạo kho nhạc trực tuyến")

                serviceSessionRepository.connect(
                    serviceId = normalizedServiceId,
                    serverUrl = connection.serverUrl.trim(),
                    username = connection.displayName.trim()
                )
                if (appSettings.streamingService.value != normalizedServiceId) {
                    appSettings.setStreamingService(normalizedServiceId)
                }
                lastPulledStatsAccountKey = null
                checkAndSyncAuthentication(normalizedServiceId, forceCheck = true)
                loadHomeContent()
                loadLibrary(forceSync = true)

                notificationManager.notifyAuthenticationSuccess(getSourceTypeName(sourceTypeFromServiceId(normalizedServiceId)))
            } catch (e: Exception) {
                _error.value = mapAuthExceptionMessage(e)
                notificationManager.notifyAuthenticationFailed(getSourceTypeName(_currentService.value))
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun mapAuthExceptionMessage(e: Exception): String {
        return when {
            e is java.net.ConnectException ||
            e is java.net.SocketTimeoutException ||
            (e is java.io.IOException && (e.message?.contains("connect", ignoreCase = true) == true ||
                                          e.message?.contains("timeout", ignoreCase = true) == true)) ||
            e.cause is java.net.ConnectException ||
            e.cause is java.net.SocketTimeoutException ->
                "Không thể kết nối đến máy chủ XK Music. Vui lòng kiểm tra lại kết nối mạng."
            e is java.net.UnknownHostException ||
            e.cause is java.net.UnknownHostException ->
                "Không có kết nối mạng. Không thể tìm thấy máy chủ."
            e is javax.net.ssl.SSLException ||
            e.cause is javax.net.ssl.SSLException ->
                "Kết nối bảo mật SSL thất bại."
            e is FirebaseProviderException && e.code == 401 ->
                e.message ?: "Email hoặc mật khẩu không chính xác."
            e is FirebaseProviderException ->
                e.message ?: "Yêu cầu xác thực thất bại (${e.code})."
            else -> e.message ?: "Kết nối thất bại"
        }
    }

    fun disconnectService(serviceId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val normalizedServiceId = normalizeServiceId(serviceId)
                withTimeoutOrNull(4_000L) {
                    pushActiveAccountStatsToCloud()
                }
                providerRepository?.disconnect(normalizedServiceId)

                serviceSessionRepository.disconnect(normalizedServiceId)
                lastPulledStatsAccountKey = null
                switchLocalStatsScope("guest")
                clearContent()
                // Silently reconnect as Guest so music stays available without exposing API setup
                val reconnected = checkAndSyncAuthentication(normalizedServiceId, forceCheck = true)
                if (reconnected) {
                    loadHomeContent()
                    loadLibrary(forceSync = true)
                }
            } catch (e: Exception) {
                _error.value = "Đăng xuất thất bại: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun getServiceSession(serviceId: String): StreamingServiceSession {
        return serviceSessionRepository.getSession(serviceId)
    }

    fun setPlaybackHandler(handler: (List<StreamingSong>, Int) -> Unit) {
        playbackHandler = handler
    }

    fun setSeekHandlers(
        progressHandler: (Float) -> Unit,
        positionHandler: (Long) -> Unit
    ) {
        seekProgressHandler = progressHandler
        seekPositionHandler = positionHandler
    }
    
    /**
     * Report an error to the user.
     */
    fun reportError(message: String) {
        _error.value = message
    }
    
    /**
     * Report a warning (stored in error state for display).
     */
    fun reportWarning(message: String) {
        _error.value = message
    }
    
    /**
     * Refresh the current service session connection status.
     */
    fun refreshCurrentSession() {
        viewModelScope.launch {
            val currentServiceId = appSettings.streamingService.value
            checkAndSyncAuthentication(currentServiceId)
        }
    }
    
    /**
     * Load home screen content.
     */
    fun loadHomeContent() {
        viewModelScope.launch {
            loadHomeContentInternal()
        }
    }

    private suspend fun loadHomeContentInternal() {
        if (appSettings.offlineMode.value) {
            switchToDownloadedMode()
            _hasLoadedHomeContent.value = true
            _isLoading.value = false
            _error.value = null
            return
        }

        val hasLocalCache = hydrateCatalogFromLocalCache()
        if (!hasLocalCache && _newestSongs.value.isEmpty() && _recommendations.value.isEmpty()) {
            _isLoading.value = true
        }

        try {
            val isNetworkDown = !NetworkUtils.isNetworkAvailable(getApplication())
            if (isNetworkDown) {
                if (!hasLocalCache && _allSongs.value.isEmpty()) {
                    switchToDownloadedMode()
                } else {
                    if (_newestSongs.value.isEmpty()) {
                        _newestSongs.value = runCatching { repository.getNewestSongs(limit = 5) }.getOrDefault(emptyList())
                    }
                    refreshWeeklyCharts(fetchRemote = false)
                }
                _error.value = null
                return
            }

            if (!checkAndSyncAuthentication()) {
                if (!hasLocalCache && _allSongs.value.isEmpty()) {
                    switchToDownloadedMode()
                }
                return
            }

            // Check network constraints
            if (!NetworkUtils.canStream(getApplication(), appSettings.allowCellularStreaming.value)) {
                if (!hasLocalCache && _allSongs.value.isEmpty()) {
                    switchToDownloadedMode()
                }
                _error.value = "Không được phép phát trực tuyến trên kết nối mạng hiện tại"
                return
            }

            val syncedPlaylists = repository.syncPlaylists()

            var recommendations = repository.getRecommendations(limit = 24)
            if (recommendations.isEmpty()) {
                recommendations = repository.getRandomSongs(limit = 24)
            }

            val newest = repository.refreshNewestSongs(limit = 5)
            val charts = repository.getTopCharts(limit = 24)
            val newReleases = repository.getNewReleases(limit = 24)

            val featuredPlaylists = syncedPlaylists

            if (recommendations.isNotEmpty()) {
                _recommendations.value = recommendations
            }
            if (newest.isNotEmpty()) {
                _newestSongs.value = newest
            }
            if (charts.isNotEmpty()) {
                _topCharts.value = charts
            }
            if (newReleases.isNotEmpty()) {
                _newReleases.value = newReleases
            }
            if (featuredPlaylists.isNotEmpty()) {
                _featuredPlaylists.value = featuredPlaylists
            }

            viewModelScope.launch {
                repository.getSiteInfo().getOrNull()?.let { _siteInfo.value = it }
                repository.getUserProfile().getOrNull()?.let { _userProfile.value = it }
            }
            refreshWeeklyCharts(fetchRemote = true)
        } catch (e: Exception) {
            Log.e("StreamingMusicViewModel", "loadHomeContent failed", e)
            val isNetworkDown = !NetworkUtils.isNetworkAvailable(getApplication())
            if (isNetworkDown) {
                if (_allSongs.value.isEmpty()) {
                    switchToDownloadedMode()
                }
                updateOnlineStatus(false)
                _error.value = if (_allSongs.value.isNotEmpty()) null else "Mất kết nối mạng. Đã chuyển sang nội dung đã tải."
            } else {
                _error.value = "Không thể tải nội dung: ${e.message}"
            }
        } finally {
            _hasLoadedHomeContent.value = true
            _isLoading.value = false
        }
    }

    /**
     * Refresh home screen and library content.
     */
    fun refreshHome() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                withTimeoutOrNull(25_000L) {
                    checkAndSyncAuthentication(forceCheck = true)
                    loadLibraryInternal(forceSync = true)
                    loadHomeContentInternal()
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Refresh library content.
     */
    fun refreshLibrary() {
        loadLibrary(forceSync = true)
    }
    
    /**
     * Load browse categories.
     */
    fun loadBrowseCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                if (!checkAndSyncAuthentication()) {
                    _browseCategories.value = emptyList()
                    return@launch
                }

                _browseCategories.value = repository.getBrowseCategories()
            } catch (e: Exception) {
                _error.value = "Failed to load categories: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Load top charts.
     */
    fun loadTopCharts() {
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                if (!checkAndSyncAuthentication()) {
                    _topCharts.value = emptyList()
                    return@launch
                }

                _topCharts.value = repository.getTopCharts()
                refreshWeeklyCharts(fetchRemote = true)
            } catch (e: Exception) {
                _error.value = "Failed to load charts: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshWeeklyCharts(fetchRemote: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val period = weeklyChartsTracker.getCurrentPeriodInfo()
            _weeklyChartPeriod.value = period

            if (fetchRemote && !appSettings.offlineMode.value && NetworkUtils.isNetworkAvailable(getApplication())) {
                val remoteCounts = runCatching {
                    repository.getWeeklyPlayCounts(period.weekKey)
                }.getOrDefault(emptyMap())
                if (remoteCounts.isNotEmpty()) {
                    latestServerWeeklyPlays = remoteCounts
                }
            }

            val catalogPool = (_allSongs.value + _topCharts.value + _recommendations.value + _downloadedSongs.value)
                .distinctBy { it.externalId?.takeIf { ext -> ext.isNotBlank() } ?: it.id.substringAfterLast("::") }

            _weeklyCharts.value = weeklyChartsTracker.buildTopCharts(
                catalogSongs = catalogPool,
                serverWeeklyPlays = latestServerWeeklyPlays,
                limit = WeeklyChartsTracker.DEFAULT_TOP_LIMIT
            )
        }
    }
    
    /**
     * The disk cache may predate a server-side library change: check cheaply in the background
     * and run the full sync only if the library did change. The repository runs the check once
     * per process, so repeated cached loads (e.g. while that sync runs) do not start more syncs.
     */
    private fun checkCachedCatalogInBackground() {
        viewModelScope.launch {
            val outdated = try { repository.isCatalogOutdated() } catch (e: Exception) { false }
            if (outdated) {
                Log.d("StreamingMusicViewModel", "Server library changed since the cached catalog; syncing")
                loadLibrary(forceSync = true)
            }
        }
    }

    /**
     * Load user's library content.
     * @param forceSync If true, performs a full network sync and shows notifications.
     *                  If false, loads quickly from disk/memory cache without notifications or server overhead.
     */
    fun loadLibrary(forceSync: Boolean = false) {
        viewModelScope.launch {
            loadLibraryInternal(forceSync = forceSync)
        }
    }

    private suspend fun loadLibraryInternal(forceSync: Boolean = false) {
        if (_allSongs.value.isEmpty()) {
            _isLoading.value = true
        }
        val serviceName = getSourceTypeName(_currentService.value)

        // The cache loads in the background at start-up; wait for it.
        repository.awaitCatalogCacheLoaded()

        // Fast path: if not forced and cache exists, load immediately from Room without network sync
        if (!forceSync && repository.hasCachedCatalog()) {
            try {
                val likedSongs = try { repository.getLikedSongs().first() } catch (e: Exception) { emptyList() }
                val followedArtists = try { repository.getFollowedArtists().first() } catch (e: Exception) { emptyList() }
                val downloadedSongs = try { repository.getDownloadedSongs().first() } catch (e: Exception) { emptyList() }
                val savedPlaylists = try {
                    repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>()
                } catch (e: Exception) {
                    emptyList()
                }
                val savedAlbums = try { repository.getSavedAlbums().first() } catch (e: Exception) { emptyList() }
                val catalogAlbums = try {
                    repository.getAlbums().first().filterIsInstance<StreamingAlbum>()
                } catch (e: Exception) {
                    emptyList()
                }
                val catalogSongs = try {
                    repository.getSongs().first().filterIsInstance<StreamingSong>()
                } catch (e: Exception) {
                    emptyList()
                }
                val catalogArtists = try {
                    repository.getArtists().first()
                        .filterIsInstance<StreamingArtist>()
                        .distinctBy { it.id }
                } catch (e: Exception) {
                    emptyList()
                }

                _likedSongs.value = likedSongs
                _savedAlbums.value = savedAlbums.ifEmpty { catalogAlbums }
                if (_newReleases.value.isEmpty() && (savedAlbums.isNotEmpty() || catalogAlbums.isNotEmpty())) {
                    _newReleases.value = catalogAlbums.ifEmpty { savedAlbums }
                }
                _followedArtists.value = catalogArtists.ifEmpty { followedArtists }
                _savedPlaylists.value = savedPlaylists
                _downloadedSongs.value = downloadedSongs
                _downloadedAlbums.value = deriveAlbumsFromSongs(downloadedSongs, limit = 500)
                _downloadedArtists.value = deriveArtistsFromSongs(downloadedSongs, limit = 500)
                if (catalogSongs.isNotEmpty()) {
                    _allSongs.value = catalogSongs
                    if (_recommendations.value.isEmpty()) {
                        _recommendations.value = catalogSongs.shuffled().take(24)
                    }
                    if (_newestSongs.value.isEmpty()) {
                        _newestSongs.value = runCatching { repository.getNewestSongs(limit = 5) }
                            .getOrDefault(emptyList())
                            .ifEmpty { catalogSongs.sortedByDescending { it.createdAtMs }.take(5) }
                    }
                }
                if (_featuredPlaylists.value.isEmpty()) {
                    _featuredPlaylists.value = savedPlaylists
                }
                _syncProgress.value = StreamingSyncProgress(
                    isSyncing = false,
                    songsCount = _allSongs.value.size,
                    stage = StreamingSyncStage.Complete
                )
                _hasLoadedLibrary.value = true
                _isLoading.value = false
                refreshWeeklyCharts(fetchRemote = false)
                Log.d("StreamingMusicViewModel", "Loaded library instantly from Room cache: ${_allSongs.value.size} songs")
                if (!appSettings.offlineMode.value && NetworkUtils.isNetworkAvailable(getApplication())) {
                    checkCachedCatalogInBackground()
                }
                return
            } catch (e: Exception) {
                Log.w("StreamingMusicViewModel", "Fast cache load failed, falling back to network sync", e)
            }
        }

        if (_allSongs.value.isEmpty()) {
            _hasLoadedLibrary.value = false
        }
        var syncSuccess = false

        try {
            if (!checkAndSyncAuthentication()) {
                if (_allSongs.value.isEmpty() || appSettings.offlineMode.value) {
                    switchToDownloadedMode()
                }
                return
            }

                _syncProgress.value = StreamingSyncProgress(isSyncing = true, stage = StreamingSyncStage.Syncing)
                notificationManager.notifySyncStarted(serviceName)

                // 1. First fetch artists directly from provider so they are available immediately
                try {
                    repository.syncArtists()
                } catch (e: Exception) {
                    Log.e("StreamingMusicViewModel", "syncArtists failed", e)
                }

                // 2. Pull the provider catalog with live progress callbacks
                // Throttle notification updates to prevent system rate-limiting
                val notificationThrottle = SyncProgressThrottle()
                try {
                    repository.syncCatalog(limit = StreamingMusicRepository.MAX_LIBRARY_SONGS) { current, total, songCount ->
                        _syncProgress.value = StreamingSyncProgress(
                            isSyncing = true,
                            current = current,
                            total = total,
                            songsCount = songCount,
                            stage = StreamingSyncStage.Syncing
                        )
                        if (!notificationThrottle.tryAcquire()) return@syncCatalog
                        notificationManager.updateSyncProgress(
                            songCount = songCount,
                            albumCount = current,
                            artistCount = total,
                            current = current,
                            total = total
                        )
                    }
                } catch (e: Exception) {
                    Log.e("StreamingMusicViewModel", "syncCatalog failed", e)
                }

                val likedSongs = try { repository.getLikedSongs().first() } catch (e: Exception) { emptyList() }
                val followedArtists = try { repository.getFollowedArtists().first() } catch (e: Exception) { emptyList() }
                val downloadedSongs = try { repository.getDownloadedSongs().first() } catch (e: Exception) { emptyList() }
                
                var syncedPlaylists = emptyList<StreamingPlaylist>()
                try {
                    syncedPlaylists = repository.syncPlaylists()
                } catch (e: Exception) {
                    Log.e("StreamingMusicViewModel", "syncPlaylists failed", e)
                }

                val savedPlaylists = try {
                    repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>()
                } catch (e: Exception) {
                    emptyList()
                }

                val savedAlbums = try { repository.getSavedAlbums().first() } catch (e: Exception) { emptyList() }
                val newReleases = try { repository.getNewReleases(limit = 100) } catch (e: Exception) { emptyList() }

                val catalogAlbums = if (savedAlbums.isNotEmpty()) {
                    savedAlbums
                } else {
                    newReleases
                }
                val catalogArtists = try {
                    repository.getArtists().first()
                        .filterIsInstance<StreamingArtist>()
                        .distinctBy { it.id }
                } catch (e: Exception) {
                    emptyList()
                }

                val hasExplicitLibraryData = likedSongs.isNotEmpty() ||
                    savedAlbums.isNotEmpty() ||
                    followedArtists.isNotEmpty() ||
                    downloadedSongs.isNotEmpty() ||
                    savedPlaylists.isNotEmpty()

                // Use provider-native methods instead of inefficient seeding/derivation
                val resolvedAlbums = catalogAlbums

                val resolvedArtists = if (followedArtists.isNotEmpty()) {
                    val catalogArtistsByName = catalogArtists.associateBy { it.name.lowercase() }
                    val separatorEnabled = appSettings.artistSeparatorEnabled.value
                    val separatorDelimiters = appSettings.artistSeparatorDelimiters.value.ifBlank { AppSettings.DEFAULT_ARTIST_SEPARATOR_DELIMITERS }
                    followedArtists
                        .flatMap { followedArtist ->
                            val splitNames = ArtistSeparator.splitArtistNames(
                                followedArtist.name,
                                delimiters = separatorDelimiters,
                                enabled = separatorEnabled
                            )
                            if (splitNames.isEmpty()) {
                                listOf(
                                    catalogArtistsByName[followedArtist.name.lowercase()]
                                        ?: catalogArtists.firstOrNull { it.id == followedArtist.id }
                                        ?: followedArtist
                                )
                            } else {
                                splitNames.map { name ->
                                    catalogArtistsByName[name.lowercase()]
                                        ?: catalogArtists.firstOrNull { it.id == repository.buildArtistId(followedArtist.sourceType.name, name) }
                                        ?: followedArtist.copy(
                                            id = repository.buildArtistId(followedArtist.sourceType.name, name),
                                            name = name,
                                            artworkUri = followedArtist.artworkUri
                                        )
                                }
                            }
                        }
                        .distinctBy { it.id }
                } else if (catalogArtists.isNotEmpty()) {
                    catalogArtists
                } else {
                    val directArtists = try {
                        repository.syncArtists()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (directArtists.isNotEmpty()) {
                        directArtists
                    } else {
                        repository.searchArtists("")
                            .filterIsInstance<StreamingArtist>()
                            .distinctBy { it.id }
                    }
                }

                // Strictly keep only the currently logged-in member's own playlists
                val resolvedPlaylists = (savedPlaylists + syncedPlaylists).distinctBy { it.id }

                _likedSongs.value = likedSongs
                _savedAlbums.value = resolvedAlbums
                _newReleases.value = newReleases
                _followedArtists.value = resolvedArtists
                _savedPlaylists.value = resolvedPlaylists
                _downloadedSongs.value = downloadedSongs
                _downloadedAlbums.value = deriveAlbumsFromSongs(downloadedSongs, limit = 500)
                _downloadedArtists.value = deriveArtistsFromSongs(downloadedSongs, limit = 500)

                val catalogSongs = try {
                    repository.getSongs().first().filterIsInstance<StreamingSong>()
                } catch (e: Exception) {
                    emptyList()
                }
                if (catalogSongs.isNotEmpty()) {
                    _allSongs.value = catalogSongs
                    val refreshedNewest = runCatching { repository.getNewestSongs(limit = 5) }
                        .getOrDefault(emptyList())
                        .ifEmpty {
                            catalogSongs.withIndex()
                                .sortedWith(
                                    compareByDescending<IndexedValue<StreamingSong>> { it.value.createdAtMs }
                                        .thenByDescending { it.index }
                                )
                                .map { it.value }
                                .take(5)
                        }
                    if (refreshedNewest.isNotEmpty()) {
                        _newestSongs.value = refreshedNewest
                    }
                }

                if (_featuredPlaylists.value.isEmpty()) {
                    _featuredPlaylists.value = resolvedPlaylists
                }
                refreshWeeklyCharts(fetchRemote = true)
                syncSuccess = true
            } catch (e: Exception) {
                Log.e("StreamingMusicViewModel", "loadLibrary failed", e)
                _error.value = "Không thể tải thư viện: ${e.message}"
                notificationManager.notifySyncFailed(e.message)
                val isNetworkDown = !NetworkUtils.isNetworkAvailable(getApplication())
                if (isNetworkDown) {
                    if (_allSongs.value.isEmpty() || appSettings.offlineMode.value) {
                        switchToDownloadedMode()
                    }
                    updateOnlineStatus(false)
                }
            } finally {
                val finalSongsCount = if (syncSuccess) {
                    _allSongs.value.size.takeIf { it > 0 } ?: _syncProgress.value.songsCount
                } else {
                    0
                }
                _syncProgress.value = StreamingSyncProgress(
                    isSyncing = false,
                    current = if (syncSuccess) _syncProgress.value.total else 0,
                    total = if (syncSuccess) _syncProgress.value.total else 0,
                    songsCount = finalSongsCount,
                    stage = if (syncSuccess) StreamingSyncStage.Complete else if (_error.value != null) StreamingSyncStage.Error else StreamingSyncStage.Idle
                )
                if (syncSuccess) {
                    notificationManager.notifySyncComplete(finalSongsCount, serviceName)
                } else {
                    notificationManager.cancelSyncNotification()
                }
                _hasLoadedLibrary.value = true
                _isLoading.value = false
            }
    }
    
    /**
     * Search across the streaming service.
     */
    fun search(query: String) {
        _searchQuery.value = query

        if (query.isBlank()) {
            _searchResults.value = StreamingSearchResults()
            return
        }

        viewModelScope.launch {
            _isLoading.value = true

            try {
                if (!checkAndSyncAuthentication()) {
                    _searchResults.value = StreamingSearchResults()
                    showToast("Vui lòng kiểm tra kết nối mạng trước khi tìm kiếm")
                    return@launch
                }
                
                // Check network and offline constraints
                if (!NetworkUtils.canStream(getApplication(), appSettings.allowCellularStreaming.value)) {
                    _searchResults.value = StreamingSearchResults()
                    showToast("Không được phép phát trực tuyến trên mạng hiện tại")
                    return@launch
                }
                
                if (appSettings.offlineMode.value) {
                    _searchResults.value = StreamingSearchResults()
                    showToast("Không hỗ trợ tìm kiếm trực tuyến ở chế độ ngoại tuyến")
                    return@launch
                }

                val songs = repository.searchSongs(query).filterIsInstance<StreamingSong>()
                val artistsFromRepository = repository.searchArtists(query).filterIsInstance<StreamingArtist>()
                val albumsFromRepository = repository.searchAlbums(query).filterIsInstance<StreamingAlbum>()
                val playlists = repository.searchPlaylists(query).filterIsInstance<StreamingPlaylist>()

                _searchResults.value = StreamingSearchResults(
                    songs = songs,
                    albums = albumsFromRepository,
                    artists = artistsFromRepository,
                    playlists = playlists
                )
            } catch (e: Exception) {
                showToast("Tìm kiếm thất bại: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Play a streaming song.
     */
    fun playSong(song: StreamingSong) {
        val queueSource = when {
            _searchResults.value.songs.any { it.id == song.id } -> _searchResults.value.songs
            _recommendations.value.any { it.id == song.id } -> _recommendations.value
            _queue.value.any { it.id == song.id } -> _queue.value
            else -> listOf(song)
        }

        val selectedIndex = queueSource.indexOfFirst { it.id == song.id }
            .takeIf { it >= 0 }
            ?: 0

        val keepShuffle = appSettings.keepShuffleOnSelection.value && appSettings.savedShuffleState.value
        playQueue(queueSource, startIndex = selectedIndex, shuffle = keepShuffle, pinStartIndex = keepShuffle)
    }

    /**
     * Play the current recommendation list.
     */
    fun playRecommendations(shuffle: Boolean = false) {
        playQueue(_recommendations.value, startIndex = 0, shuffle = shuffle)
    }

    /**
     * Play a specific queue and start index.
     */
    fun playQueue(queue: List<StreamingSong>, startIndex: Int = 0, shuffle: Boolean = false, pinStartIndex: Boolean = false) {
        val playableQueue = queue.filter { it.isPlayable }
        if (playableQueue.isEmpty()) {
            showToast("No playable tracks available")
            return
        }

        viewModelScope.launch {
            val safeStartIndex = startIndex.coerceIn(0, playableQueue.lastIndex)
            val selectedTargetSong = playableQueue[safeStartIndex]
            val isTargetDownloaded = isSongDownloaded(selectedTargetSong.id)

            val isOffline = !_isOnline.value || !NetworkUtils.isNetworkAvailable(getApplication()) || appSettings.offlineMode.value
            if (isOffline && !isTargetDownloaded) {
                showToast(if (appSettings.offlineMode.value) "Offline mode: Song is not downloaded" else "Device is offline: Song is not downloaded")
                return@launch
            }

            val normalizedServiceId = normalizeServiceId(appSettings.streamingService.value)
            val sessionMarkedConnected = serviceSessionRepository.isConnected(normalizedServiceId)
            val credentialsExist = providerRepository?.isServiceConnected(normalizedServiceId) ?: sessionMarkedConnected

            if (!credentialsExist && !isTargetDownloaded && _downloadedSongs.value.isEmpty()) {
                showToast("Connect to a streaming service first")
                return@launch
            }
            val shouldPinStart = pinStartIndex || (shuffle && safeStartIndex > 0)
            val queueToPlay = if (shuffle && playableQueue.size > 1) {
                if (shouldPinStart) {
                    val startSong = playableQueue[safeStartIndex]
                    val tail = playableQueue.toMutableList().apply {
                        removeAt(safeStartIndex)
                        shuffle()
                    }
                    listOf(startSong) + tail
                } else {
                    playableQueue.shuffled()
                }
            } else {
                playableQueue
            }

            val selectedIndex = if (shuffle && queueToPlay.size > 1) {
                0
            } else {
                safeStartIndex
            }

            val selectedSong = queueToPlay[selectedIndex]
            val selectedCachedUrl = selectedSong.streamingUrl?.takeIf { url ->
                url.startsWith("file://", ignoreCase = true) ||
                    url.startsWith("content://", ignoreCase = true) ||
                    !selectedSong.id.contains("::") ||
                    (!selectedSong.isPaidTrack && selectedSong.price <= 0.0 && !chromahub.rhythm.app.features.streaming.data.provider.XkResponseParser.looksLikeDemoUrl(url)) ||
                    (selectedSong.isPaidTrack && !selectedSong.isPurchased && url.isNotBlank())
            }
            val selectedResolvedUrl = selectedCachedUrl
                ?: withContext(Dispatchers.IO) {
                    repository.getStreamingUrl(selectedSong.id)
                }
                ?: selectedSong.streamingUrl
                ?: selectedSong.previewUrl

            if (selectedResolvedUrl.isNullOrBlank()) {
                val errorMsg = when {
                    appSettings.offlineMode.value -> "Chế độ ngoại tuyến: Bài hát chưa được tải về"
                    !NetworkUtils.canStream(getApplication(), appSettings.allowCellularStreaming.value) ->
                        "Không được phép phát trực tuyến trên mạng hiện tại"
                    else -> "Không thể tải đường dẫn phát nhạc cho bài hát này"
                }
                showToast(errorMsg)
                return@launch
            }

            val selectedResolvedSong = selectedSong.copy(streamingUrl = selectedResolvedUrl)

            // Resolve only the tapped song up-front; remaining streaming tracks keep lazy URIs
            // (resolved on-demand by ExoPlayer ResolvingDataSource / PreloadController) for instant playback.
            val playbackQueue = queueToPlay.mapIndexed { idx, song ->
                if (idx == selectedIndex) {
                    selectedResolvedSong
                } else {
                    val localOrNonStreaming = song.streamingUrl?.takeIf { url ->
                        url.startsWith("file://", ignoreCase = true) ||
                            url.startsWith("content://", ignoreCase = true) ||
                            !song.id.contains("::")
                    }
                    if (localOrNonStreaming != null) {
                        song.copy(streamingUrl = localOrNonStreaming)
                    } else {
                        song.copy(streamingUrl = null, previewUrl = null)
                    }
                }
            }

            _queue.value = playbackQueue
            _currentSong.value = selectedResolvedSong
            _isPlaying.value = true

            playbackHandler?.invoke(playbackQueue, selectedIndex)

            viewModelScope.launch(Dispatchers.IO) {
                weeklyChartsTracker.recordPlay(selectedResolvedSong.id)
                runCatching { repository.reportPlaybackStart(selectedResolvedSong.id) }
                refreshWeeklyCharts(fetchRemote = false)
            }
            }
    }

    /**
     * Play an album.
     */
    fun playAlbum(album: StreamingAlbum) {
        // Album logic intentionally disabled for streaming mode cleanup.
    }

    /**
     * Play a playlist.
     */
    fun playPlaylist(playlist: StreamingPlaylist) {
        viewModelScope.launch {
            val tracks = playlist.getTracks()
            if (tracks.isNotEmpty()) {
                playQueue(tracks, startIndex = 0, shuffle = false)
            }
        }
    }

    /**
     * Resolve songs for an album with repository-first lookup and local fallback.
     */
    suspend fun getAlbumSongs(album: StreamingAlbum): List<StreamingSong> {
        // 1. If the album already contains tracks (derived from songs or downloaded), return immediately
        if (album.tracks.isNotEmpty()) {
            return album.tracks
        }

        // 2. Check downloaded songs matching this album (by albumId or by title and artist)
        val downloadedMatches = _downloadedSongs.value.filter { song ->
            (song.albumId != null && song.albumId == album.id) ||
            (song.album.equals(album.title, ignoreCase = true) &&
                (album.artist.isBlank() || song.artist.equals(album.artist, ignoreCase = true)))
        }
        if (downloadedMatches.isNotEmpty()) {
            return downloadedMatches
        }

        // 3. Query repository
        try {
            val songs = repository.getAlbumSongs(album.id)
            if (songs.isNotEmpty()) {
                return songs
            }
        } catch (e: Exception) {
            Log.w("StreamingMusicVM", "Failed to get album songs from repository for ${album.id}", e)
        }

        // 4. Fallback to all loaded songs in memory
        val memoryMatches = _allSongs.value.filter { song ->
            (song.albumId != null && song.albumId == album.id) ||
            (song.album.equals(album.title, ignoreCase = true) &&
                (album.artist.isBlank() || song.artist.equals(album.artist, ignoreCase = true)))
        }
        if (memoryMatches.isNotEmpty()) {
            return memoryMatches
        }

        return emptyList()
    }

    /**
     * Resolve top songs for an artist with repository-first lookup and local fallback.
     */
    suspend fun getArtistTopSongs(
        artistId: String,
        artistNameHint: String? = null,
        limit: Int = 40
    ): List<StreamingSong> {
        val safeLimit = limit.coerceAtLeast(1)
        val cachedArtist = (_downloadedArtists.value + _followedArtists.value + _searchResults.value.artists)
            .distinctBy { it.id }
            .firstOrNull { it.id == artistId || (artistNameHint != null && it.name.equals(artistNameHint, ignoreCase = true)) }

        val embeddedTracks = cachedArtist
            ?.getTopTracks()
            .orEmpty()
            .filter { it.isPlayable }
            .distinctBy { it.id }
        if (embeddedTracks.isNotEmpty()) {
            return embeddedTracks.take(safeLimit)
        }

        val normalizedHint = artistNameHint?.trim().orEmpty()
        val separatorEnabled = appSettings.artistSeparatorEnabled.value
        val separatorDelimiters = appSettings.artistSeparatorDelimiters.value.ifBlank { AppSettings.DEFAULT_ARTIST_SEPARATOR_DELIMITERS }

        // Check downloaded songs matching this artist
        val downloadedMatches = _downloadedSongs.value.filter { song ->
            when {
                normalizedHint.isNotBlank() -> song.artist.equals(normalizedHint, ignoreCase = true) ||
                    ArtistSeparator.splitArtistNames(song.artist, delimiters = separatorDelimiters, enabled = separatorEnabled).any { it.equals(normalizedHint, ignoreCase = true) }
                cachedArtist != null -> song.artist.equals(cachedArtist.name, ignoreCase = true) ||
                    ArtistSeparator.splitArtistNames(song.artist, delimiters = separatorDelimiters, enabled = separatorEnabled).any { it.equals(cachedArtist.name, ignoreCase = true) }
                else -> artistIdMatchesSongArtist(artistId = artistId, songArtist = song.artist)
            }
        }
        if (downloadedMatches.isNotEmpty()) {
            return downloadedMatches.take(safeLimit)
        }

        // If service is connected and not syncing, try repository
        if (serviceSessionRepository.isConnected(appSettings.streamingService.value) && !_syncProgress.value.isSyncing) {
            try {
                val repositoryTracks = repository.getArtistTopTracks(artistId, safeLimit)
                    .filter { it.isPlayable }
                    .distinctBy { it.id }
                if (repositoryTracks.isNotEmpty()) {
                    return repositoryTracks
                }
            } catch (e: Exception) {
                Log.w("StreamingMusicVM", "getArtistTopTracks repository lookup failed for $artistId", e)
            }
        }

        // Fallback: match songs by artist name
        val allAvailableSongs = _likedSongs.value +
            _downloadedSongs.value +
            _allSongs.value +
            _recommendations.value +
            _searchResults.value.songs +
            _queue.value

        return allAvailableSongs
            .asSequence()
            .filter {
                when {
                    normalizedHint.isNotBlank() -> it.artist.equals(normalizedHint, ignoreCase = true) ||
                        ArtistSeparator.splitArtistNames(it.artist, delimiters = separatorDelimiters, enabled = separatorEnabled).any { name -> name.equals(normalizedHint, ignoreCase = true) }
                    cachedArtist != null -> it.artist.equals(cachedArtist.name, ignoreCase = true) ||
                        ArtistSeparator.splitArtistNames(it.artist, delimiters = separatorDelimiters, enabled = separatorEnabled).any { name -> name.equals(cachedArtist.name, ignoreCase = true) }
                    else -> artistIdMatchesSongArtist(artistId = artistId, songArtist = it.artist)
                }
            }
            .filter { it.isPlayable }
            .distinctBy { it.id }
            .take(safeLimit)
            .toList()
    }

    /**
     * Resolve albums for an artist with repository-first lookup and local fallback.
     */
    suspend fun getArtistAlbums(
        artistId: String,
        artistNameHint: String? = null
    ): List<StreamingAlbum> {
        val normalizedHint = artistNameHint?.trim().orEmpty()
        val downloadedArtistAlbums = _downloadedAlbums.value.filter {
            it.artist.equals(normalizedHint, ignoreCase = true)
        }

        // If connected and not syncing, try repository
        if (serviceSessionRepository.isConnected(appSettings.streamingService.value) && !_syncProgress.value.isSyncing) {
            try {
                val resolvedArtistId = if (normalizedHint.isNotBlank()) {
                    val serviceName = artistId.substringBefore("::", _currentService.value.name)
                    repository.buildArtistId(serviceName, normalizedHint)
                } else {
                    artistId
                }
                val repoAlbums = repository.getArtistAlbums(resolvedArtistId)
                if (repoAlbums.isNotEmpty()) {
                    return (downloadedArtistAlbums + repoAlbums).distinctBy { it.id }
                }
            } catch (e: Exception) {
                Log.w("StreamingMusicVM", "getArtistAlbums repository lookup failed for $artistId", e)
            }
        }

        if (downloadedArtistAlbums.isNotEmpty()) {
            return downloadedArtistAlbums
        }

        // Derive from matching downloaded or cached songs
        val matchingSongs = (_downloadedSongs.value + _allSongs.value).filter {
            it.artist.equals(normalizedHint, ignoreCase = true)
        }
        if (matchingSongs.isNotEmpty()) {
            return deriveAlbumsFromSongs(matchingSongs, 100)
        }

        return emptyList()
    }

    /**
     * Fetch full artist info (including artwork) from the repository.
     * Returns null if the artist cannot be resolved.
     */
    suspend fun getArtistInfo(
        artistId: String,
        artistNameHint: String? = null
    ): StreamingArtist? {
        // Check memory caches first including downloaded artists
        val cached = (_downloadedArtists.value + _followedArtists.value + _searchResults.value.artists)
            .distinctBy { it.id }
            .firstOrNull { it.id == artistId || (artistNameHint != null && it.name.equals(artistNameHint, ignoreCase = true)) }
        if (cached?.artworkUri != null) return cached

        return try {
            val item = repository.getArtistById(artistId)
            item as? StreamingArtist
                ?: item?.let { artistItem ->
                    // Map generic ArtistItem -> StreamingArtist using name hint
                    val name = artistItem.name.ifBlank { artistNameHint ?: artistId }
                    StreamingArtist(
                        id = artistItem.id,
                        name = name,
                        artworkUri = artistItem.artworkUri ?: cached?.artworkUri,
                        songCount = if (artistItem.songCount > 0) artistItem.songCount else (cached?.songCount ?: 0),
                        albumCount = if (artistItem.albumCount > 0) artistItem.albumCount else (cached?.albumCount ?: 0),
                        sourceType = _currentService.value
                    )
                } ?: cached
        } catch (e: Exception) {
            cached
        }
    }

    /**
     * Toggle play/pause.
     */
    fun togglePlayPause() {
        _isPlaying.value = !_isPlaying.value
        // TODO: Connect to MediaPlaybackService
    }

    /**
     * Skip to next song.
     */
    private var lastSkipTime = 0L
    private val SKIP_DEBOUNCE_MS = 400L

    fun skipToNext() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSkipTime < SKIP_DEBOUNCE_MS) return
        lastSkipTime = currentTime

        val currentIndex = _queue.value.indexOf(_currentSong.value)
        if (currentIndex >= 0 && currentIndex < _queue.value.size - 1) {
            playSong(_queue.value[currentIndex + 1])
        }
    }

    /**
     * Skip to previous song.
     */
    fun skipToPrevious() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSkipTime < SKIP_DEBOUNCE_MS) return
        lastSkipTime = currentTime

        val currentIndex = _queue.value.indexOf(_currentSong.value)
        if (currentIndex > 0) {
            playSong(_queue.value[currentIndex - 1])
        }
    }

    /**
     * Seek to a position.
     */
    fun seekTo(progress: Float) {
        _progress.value = progress
        seekProgressHandler?.invoke(progress)
    }

    fun seekTo(positionMs: Long, autoPlayIfPaused: Boolean = false) {
        seekPositionHandler?.invoke(positionMs)
        if (autoPlayIfPaused && _isPlaying.value != true) {
            togglePlayPause()
        }
    }

    /**
     * Like/save a song.
     */
    fun likeSong(song: StreamingSong) {
        viewModelScope.launch {
            try {
                repository.likeSong(song.id)
                _likedSongs.value = repository.getLikedSongs().first()
                notificationManager.notifyLikeSong(getSourceTypeName(_currentService.value))
            } catch (e: Exception) {
                showToast("Failed to save song: ${e.message}")
            }
        }
    }

    /**
     * Unlike/unsave a song.
     */
    fun unlikeSong(song: StreamingSong) {
        viewModelScope.launch {
            try {
                repository.unlikeSong(song.id)
                _likedSongs.value = repository.getLikedSongs().first()
                notificationManager.notifyUnlikeSong(getSourceTypeName(_currentService.value))
            } catch (e: Exception) {
                showToast("Failed to remove song: ${e.message}")
            }
        }
    }

    /**
     * Like a song by its ID. Looks up the song from known library content.
     */
    fun likeSongById(songId: String) {
        viewModelScope.launch {
            try {
                repository.likeSong(songId)
                _likedSongs.value = repository.getLikedSongs().first()
                notificationManager.notifyLikeSong(getSourceTypeName(_currentService.value))
            } catch (e: Exception) {
                showToast("Không thể lưu bài hát: ${e.message}")
            }
        }
    }

    /**
     * Download a song for offline playback.
     */
    fun downloadSong(song: StreamingSong) {
        if (!_isOnline.value || !NetworkUtils.isNetworkAvailable(getApplication()) || appSettings.offlineMode.value) {
            showToast("Không thể tải xuống khi đang ngoại tuyến")
            return
        }
        viewModelScope.launch {
            if (_downloadingSongIds.value.contains(song.id)) return@launch
            _downloadingSongIds.value = _downloadingSongIds.value + song.id
            showToast("Đang tải ${song.title}...")
            try {
                val success = repository.downloadSong(song)
                if (success) {
                    val downloaded = repository.getDownloadedSongs().first()
                    _downloadedSongs.value = downloaded
                    _downloadedAlbums.value = deriveAlbumsFromSongs(downloaded, limit = 500)
                    _downloadedArtists.value = deriveArtistsFromSongs(downloaded, limit = 500)
                    showToast("Đã tải xong ${song.title}")
                    if (!_isAuthenticated.value || appSettings.offlineMode.value || !NetworkUtils.isNetworkAvailable(getApplication())) {
                        switchToDownloadedMode()
                    }
                } else {
                    showToast("Không thể tải ${song.title}")
                }
            } catch (e: Exception) {
                showToast("Tải xuống thất bại: ${e.message ?: "Lỗi máy chủ"}")
            } finally {
                _downloadingSongIds.value = _downloadingSongIds.value - song.id
            }
        }
    }

    /**
     * Download a song by ID for offline playback.
     */
    fun downloadSongById(songId: String) {
        if (!_isOnline.value || !NetworkUtils.isNetworkAvailable(getApplication()) || appSettings.offlineMode.value) {
            showToast("Không thể tải xuống khi đang ngoại tuyến")
            return
        }
        val song = _allSongs.value.firstOrNull { it.id == songId }
            ?: _likedSongs.value.firstOrNull { it.id == songId }
            ?: _recommendations.value.firstOrNull { it.id == songId }
            ?: _queue.value.firstOrNull { it.id == songId }
        if (song != null) {
            downloadSong(song)
        } else {
            viewModelScope.launch {
                if (_downloadingSongIds.value.contains(songId)) return@launch
                _downloadingSongIds.value = _downloadingSongIds.value + songId
                showToast("Đang tải bài hát...")
                try {
                    val success = repository.downloadSong(songId)
                    if (success) {
                        val downloaded = repository.getDownloadedSongs().first()
                        _downloadedSongs.value = downloaded
                        _downloadedAlbums.value = deriveAlbumsFromSongs(downloaded, limit = 500)
                        _downloadedArtists.value = deriveArtistsFromSongs(downloaded, limit = 500)
                        showToast("Đã tải xong bài hát")
                        if (!_isAuthenticated.value || appSettings.offlineMode.value || !NetworkUtils.isNetworkAvailable(getApplication())) {
                            switchToDownloadedMode()
                        }
                    } else {
                        showToast("Không thể tải bài hát")
                    }
                } catch (e: Exception) {
                    showToast("Tải xuống thất bại: ${e.message ?: "Lỗi máy chủ"}")
                } finally {
                    _downloadingSongIds.value = _downloadingSongIds.value - songId
                }
            }
        }
    }

    /**
     * Remove a downloaded song.
     */
    fun removeDownload(songId: String) {
        viewModelScope.launch {
            try {
                val success = repository.removeDownload(songId)
                if (success) {
                    val downloaded = repository.getDownloadedSongs().first()
                    _downloadedSongs.value = downloaded
                    _downloadedAlbums.value = deriveAlbumsFromSongs(downloaded, limit = 500)
                    _downloadedArtists.value = deriveArtistsFromSongs(downloaded, limit = 500)
                    showToast("Đã xóa bản tải xuống")
                    if (!_isAuthenticated.value || appSettings.offlineMode.value || !NetworkUtils.isNetworkAvailable(getApplication())) {
                        switchToDownloadedMode()
                    }
                } else {
                    showToast("Không thể xóa bản tải xuống")
                }
            } catch (e: Exception) {
                showToast("Không thể xóa bản tải xuống: ${e.message}")
            }
        }
    }

    /**
     * Download multiple songs for offline playback (batch).
     */
    fun downloadSongs(songs: List<StreamingSong>) {
        if (!_isOnline.value || !NetworkUtils.isNetworkAvailable(getApplication()) || appSettings.offlineMode.value) {
            showToast("Không thể tải xuống khi đang ngoại tuyến")
            return
        }
        val pending = songs.filter { !isSongDownloaded(it.id) && !_downloadingSongIds.value.contains(it.id) }
        if (pending.isEmpty()) return
        showToast("Đang tải ${pending.size} bài hát...")
        viewModelScope.launch {
            pending.forEach { song ->
                downloadSong(song)
            }
        }
    }

    /**
     * Check if a song is downloaded locally.
     */
    fun isSongDownloaded(songId: String): Boolean {
        return _downloadedSongs.value.any { it.id == songId }
    }

    /**
     * Check if a song is currently downloading.
     */
    fun isSongDownloading(songId: String): Boolean {
        return _downloadingSongIds.value.contains(songId)
    }

    /**
     * Create a new playlist.
     */
    fun createPlaylist(name: String, songsToAdd: List<StreamingSong> = emptyList(), onCreated: ((StreamingPlaylist) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val newPlaylist = repository.createPlaylist(name)
                if (newPlaylist != null) {
                    if (songsToAdd.isNotEmpty()) {
                        repository.addSongsToPlaylist(newPlaylist.id, songsToAdd.map { it.id })
                    }
                    val playlistsList = repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>()
                    _savedPlaylists.value = playlistsList
                    notificationManager.notifyPlaylistCreated(name, getSourceTypeName(_currentService.value))
                    
                    val updatedPlaylist = playlistsList.firstOrNull { it.id == newPlaylist.id } ?: newPlaylist
                    onCreated?.invoke(updatedPlaylist)
                } else {
                    showToast("Không thể tạo playlist mới")
                }
            } catch (e: Exception) {
                showToast("Không thể tạo playlist: ${e.message}")
            }
        }
    }

    /**
     * Rename a playlist on the streaming service.
     */
    fun renamePlaylist(playlist: StreamingPlaylist, newName: String) {
        if (newName.isBlank() || playlist.name == newName) return

        viewModelScope.launch {
            try {
                val success = repository.renamePlaylist(playlist.id, newName)
                if (success) {
                    _savedPlaylists.value = repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>()
                    notificationManager.notifyPlaylistUpdated(newName, getSourceTypeName(_currentService.value))
                } else {
                    showToast("Không thể đổi tên playlist")
                }
            } catch (e: Exception) {
                showToast("Không thể đổi tên playlist: ${e.message}")
            }
        }
    }

    /**
     * Add a song to a playlist.
     */
    fun addSongToPlaylist(playlistId: String, song: StreamingSong) {
        viewModelScope.launch {
            try {
                repository.addSongsToPlaylist(playlistId, listOf(song.id))
                _savedPlaylists.value = repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>()
            } catch (e: Exception) {
                showToast("Không thể thêm bài hát vào playlist: ${e.message}")
            }
        }
    }

    /**
     * Add multiple songs to a playlist.
     */
    fun addSongsToPlaylist(playlistId: String, songs: List<StreamingSong>) {
        if (songs.isEmpty()) return

        viewModelScope.launch {
            try {
                repository.addSongsToPlaylist(playlistId, songs.map { it.id })
                _savedPlaylists.value = repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>()
            } catch (e: Exception) {
                showToast("Không thể thêm các bài hát vào playlist: ${e.message}")
            }
        }
    }

    /**
     * Remove a song from a playlist.
     */
    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            try {
                repository.removeSongsFromPlaylist(playlistId, listOf(songId))
                _savedPlaylists.value = repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>()
            } catch (e: Exception) {
                showToast("Không thể xóa bài hát khỏi playlist: ${e.message}")
            }
        }
    }

    /**
     * Unfollow/delete a playlist.
     */
    fun unfollowPlaylist(playlist: StreamingPlaylist) {
        deletePlaylist(playlist)
    }

    /**
     * Delete a playlist on the streaming service.
     */
    fun deletePlaylist(playlist: StreamingPlaylist, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val success = repository.deletePlaylist(playlist.id)
                if (success) {
                    _savedPlaylists.value = repository.getPlaylists().first().filterIsInstance<StreamingPlaylist>()
                    notificationManager.notifyPlaylistDeleted(playlist.name, getSourceTypeName(_currentService.value))
                } else {
                    showToast("Không thể xóa playlist")
                }
                onComplete(success)
            } catch (e: Exception) {
                showToast("Không thể xóa playlist: ${e.message}")
                onComplete(false)
            }
        }
    }

    /**
     * Follow an artist.
     */
    fun followArtist(artist: StreamingArtist) {
        viewModelScope.launch {
            try {
                repository.followArtist(artist.id)
                _followedArtists.value = repository.getFollowedArtists().first()
            } catch (e: Exception) {
                showToast("Không thể theo dõi nghệ sĩ: ${e.message}")
            }
        }
    }

    /**
     * Save an album.
     */
    fun saveAlbum(album: StreamingAlbum) {
        // Album logic intentionally disabled for streaming mode cleanup.
    }
    
    /**
     * Set streaming quality.
     */
    fun setStreamingQuality(quality: StreamingQuality) {
        viewModelScope.launch {
            _streamingConfig.value = _streamingConfig.value.copy(streamingQuality = quality)
            appSettings.setStreamingQuality(quality.name)
            refreshCurrentSession()
            refreshCurrentPlaybackQueue()
        }
    }

    private fun refreshCurrentPlaybackQueue() {
        viewModelScope.launch {
            val currentQueue = _queue.value
            if (currentQueue.isEmpty()) {
                return@launch
            }

            if (!checkAndSyncAuthentication()) {
                return@launch
            }

            // Preserve current playback position and playing state so we can re-seek
            val savedProgress = _progress.value
            val savedDuration = _duration.value
            val savedPositionMs = if (savedDuration > 0L) {
                (savedProgress.coerceIn(0f, 1f) * savedDuration).toLong()
            } else {
                0L
            }
            val wasPlaying = _isPlaying.value

            val refreshedQueue = currentQueue.map { song ->
                val resolvedUrl = repository.getStreamingUrl(song.id)
                    ?: song.streamingUrl
                    ?: song.previewUrl

                if (resolvedUrl.isNullOrBlank()) {
                    song
                } else {
                    song.copy(streamingUrl = resolvedUrl)
                }
            }

            val currentSongId = _currentSong.value?.id
            val currentIndex = refreshedQueue.indexOfFirst { it.id == currentSongId }
                .takeIf { it >= 0 }
                ?: 0

            _queue.value = refreshedQueue
            _currentSong.value = refreshedQueue[currentIndex]

            // Tell the playback handler to re-prepare the queue at the same index
            playbackHandler?.invoke(refreshedQueue, currentIndex)

            // Attempt to restore playback position immediately after re-preparing
            // The handler should be ready to accept a seek command; call the seek handler
            // which wiring normally forwards into the active player controller.
            if (savedPositionMs > 0L) {
                seekPositionHandler?.invoke(savedPositionMs)
            }

            // If playback was active before the change, ensure playing state is preserved
            if (wasPlaying) {
                _isPlaying.value = true
            }
        }
    }
    
    /**
     * Clear all loaded content.
     */
    private fun clearContent() {
        _recommendations.value = emptyList()
        _newestSongs.value = emptyList()
        _newReleases.value = emptyList()
        _featuredPlaylists.value = emptyList()
        _browseCategories.value = emptyList()
        _topCharts.value = emptyList()
        _likedSongs.value = emptyList()
        _savedAlbums.value = emptyList()
        _followedArtists.value = emptyList()
        _savedPlaylists.value = emptyList()
        _blockedSongs.value = emptyList()
        _purchases.value = emptyList()
        _vouchers.value = emptyList()
        _userProfile.value = null
        _queue.value = emptyList()
        _currentSong.value = null
    }
    
    /**
     * Clear the currently playing song state.
     */
    fun clearCurrentSong() {
        _currentSong.value = null
    }

    /**
     * Clear error state.
     */
    fun clearError() {
        _error.value = null
    }

    fun deriveAlbumsFromSongs(
        songs: List<StreamingSong>,
        limit: Int
    ): List<StreamingAlbum> {
        if (songs.isEmpty()) {
            return emptyList()
        }

        return songs
            .filter { it.album.isNotBlank() }
            .groupBy { song ->
                // Prefer provider album ID (albumId) for dedup; fall back to title/artist key
                song.albumId?.takeIf { it.isNotBlank() }
                    ?: "derived:${song.sourceType.name}:album:${song.artist.lowercase()}:${song.album.lowercase()}"
            }
            .values
            .sortedByDescending { albumSongs -> albumSongs.size }
            .take(limit.coerceAtLeast(1))
            .map { albumSongs ->
                val firstSong = albumSongs.first()
                val providerId = firstSong.albumId?.takeIf { it.isNotBlank() }
                val derivedKey = "derived:${firstSong.sourceType.name}:album:${firstSong.artist.lowercase()}:${firstSong.album.lowercase()}"
                val albumArt = albumSongs.firstOrNull { it.artworkUri?.startsWith("file:") == true }?.artworkUri
                    ?: albumSongs.firstNotNullOfOrNull { it.artworkUri }
                StreamingAlbum(
                    id = providerId ?: derivedKey,
                    title = firstSong.album,
                    artist = firstSong.artist,
                    artworkUri = albumArt,
                    songCount = albumSongs.size,
                    year = firstSong.releaseDate?.take(4)?.toIntOrNull(),
                    sourceType = firstSong.sourceType,
                    tracks = albumSongs
                )
            }
    }

    fun deriveArtistsFromSongs(
        songs: List<StreamingSong>,
        limit: Int
    ): List<StreamingArtist> {
        if (songs.isEmpty()) {
            return emptyList()
        }

        val separatorEnabled = appSettings.artistSeparatorEnabled.value
        val separatorDelimiters = appSettings.artistSeparatorDelimiters.value.ifBlank { AppSettings.DEFAULT_ARTIST_SEPARATOR_DELIMITERS }

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
            .take(limit.coerceAtLeast(1))
            .map { artistSongs ->
                val firstSong = artistSongs.first().first
                val artistName = artistSongs.first().second
                val artistTracks = artistSongs.map { it.first }
                val artistAlbums = deriveAlbumsFromSongs(artistTracks, limit = 8)
                val artistArt = artistTracks.firstOrNull { it.artworkUri?.startsWith("file:") == true }?.artworkUri
                    ?: artistTracks.firstNotNullOfOrNull { it.artworkUri }
                    ?: artistAlbums.firstNotNullOfOrNull { it.artworkUri }
                StreamingArtist(
                    id = "derived:${firstSong.sourceType.name}:artist:${artistName.lowercase()}",
                    name = artistName,
                    artworkUri = artistArt,
                    songCount = artistTracks.size,
                    albumCount = artistAlbums.size,
                    sourceType = firstSong.sourceType,
                    topTracks = artistTracks.take(20),
                    albums = artistAlbums
                )
            }
    }

    suspend fun checkAndSyncAuthentication(
        serviceId: String = appSettings.streamingService.value,
        retries: Int = AUTH_PING_RETRIES,
        forceCheck: Boolean = false
    ): Boolean = authMutex.withLock {
        val normalizedServiceId = normalizeServiceId(serviceId)
        val sessionMarkedConnected = serviceSessionRepository.isConnected(normalizedServiceId)
        var credentialsExist = providerRepository?.isServiceConnected(normalizedServiceId)
            ?: sessionMarkedConnected

        // Auto-connect silently as Guest in background so users never face API connection dialogs
        if (!credentialsExist &&
            normalizedServiceId == StreamingServiceId.CUSTOM_API &&
            !appSettings.offlineMode.value &&
            NetworkUtils.isNetworkAvailable(getApplication())
        ) {
            val autoConnection = runCatching {
                providerRepository?.connect(
                    serviceId = StreamingServiceId.CUSTOM_API,
                    serverUrl = XkMusicApiService.BASE_URL,
                    username = "",
                    password = "",
                    saveCredentials = false
                )
            }.getOrNull()
            if (autoConnection != null) {
                serviceSessionRepository.connect(
                    serviceId = normalizedServiceId,
                    serverUrl = autoConnection.serverUrl.ifBlank { XkMusicApiService.BASE_URL.trimEnd('/') },
                    username = autoConnection.displayName.ifBlank { "Khách" }
                )
                credentialsExist = true
            }
        }

        if (!credentialsExist) {
            _isAuthenticated.value = false
            _streamingConfig.value = _streamingConfig.value.copy(
                activeService = sourceTypeFromServiceId(normalizedServiceId),
                isAuthenticated = false
            )
            if (sessionMarkedConnected) {
                _error.value = getApplication<Application>().getString(
                    R.string.streaming_home_connect_selected_service,
                    getSourceTypeName(sourceTypeFromServiceId(normalizedServiceId))
                )
            }
            if (appSettings.offlineMode.value) {
                switchToDownloadedMode()
            }
            return@withLock false
        }

        val isNetworkAvail = NetworkUtils.isNetworkAvailable(getApplication())
        if (!isNetworkAvail) {
            _isAuthenticated.value = false
            _streamingConfig.value = _streamingConfig.value.copy(
                activeService = sourceTypeFromServiceId(normalizedServiceId),
                isAuthenticated = false
            )
            updateOnlineStatus(false)
            if (appSettings.offlineMode.value) {
                switchToDownloadedMode()
            }
            return@withLock false
        }

        // If offline mode is enabled, trust saved credentials without pinging
        if (appSettings.offlineMode.value) {
            _isAuthenticated.value = true
            _streamingConfig.value = _streamingConfig.value.copy(
                activeService = sourceTypeFromServiceId(normalizedServiceId),
                isAuthenticated = true
            )
            return@withLock true
        }

        // Cache hit: If already authenticated and checked recently (< 15 seconds ago), avoid ping stampede
        val now = System.currentTimeMillis()
        if (!forceCheck && _isAuthenticated.value && (now - lastSuccessfulAuthTimestamp) < 15_000L) {
            return@withLock true
        }

        val wasAlreadyAuthenticated = _isAuthenticated.value
        var connected = false

        for (attempt in 0 until retries) {
            val authJob = kotlinx.coroutines.withTimeoutOrNull(12000L) {
                try {
                    when (normalizedServiceId) {
                        StreamingServiceId.CUSTOM_API -> providerRepository?.authenticate() == true
                        else -> false
                    }
                } catch (e: Exception) {
                    Log.w("StreamingMusicViewModel", "Auth ping attempt $attempt failed", e)
                    false
                }
            }
            if (authJob == true) {
                connected = true
                break
            }
            if (attempt < retries - 1) {
                delay(AUTH_PING_RETRY_DELAY_MS)
            }
        }

        if (connected) {
            if (!sessionMarkedConnected && normalizedServiceId == StreamingServiceId.CUSTOM_API) {
                val client = XuanKenFirebaseClient.getInstance(getApplication())
                val primaryHost = if (XkMusicApiService.isApiKeyConfigured()) {
                    XkMusicApiService.BASE_URL.trimEnd('/')
                } else {
                    XuanKenFirebaseService.BASE_URL.trimEnd('/')
                }
                serviceSessionRepository.connect(
                    serviceId = normalizedServiceId,
                    serverUrl = primaryHost,
                    username = client.getUsername().ifBlank { "Khách" }
                )
            }
            lastSuccessfulAuthTimestamp = System.currentTimeMillis()
            _isAuthenticated.value = true
            _streamingConfig.value = _streamingConfig.value.copy(
                activeService = sourceTypeFromServiceId(normalizedServiceId),
                isAuthenticated = true
            )
            updateOnlineStatus(true)
            _error.value = null
            val targetStatsAccountKey = repository.getStatsAccountKey()
            switchLocalStatsScope(targetStatsAccountKey)
            if (targetStatsAccountKey.startsWith("user_") &&
                (forceCheck || lastPulledStatsAccountKey != targetStatsAccountKey)
            ) {
                viewModelScope.launch(Dispatchers.IO) {
                    syncAccountStatsWithCloud(targetStatsAccountKey, forceRefresh = forceCheck)
                }
            }
            return@withLock true
        }

        if (wasAlreadyAuthenticated && NetworkUtils.isNetworkAvailable(getApplication())) {
            Log.w("StreamingMusicViewModel", "Auth ping timed out while network is available; maintaining active session")
            return@withLock true
        }

        _isAuthenticated.value = false
        _streamingConfig.value = _streamingConfig.value.copy(
            activeService = sourceTypeFromServiceId(normalizedServiceId),
            isAuthenticated = false
        )
        updateOnlineStatus(false)
        val detailedPingError = if (normalizedServiceId == StreamingServiceId.CUSTOM_API) {
            XuanKenFirebaseClient.getInstance(getApplication()).getLastError()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        _error.value = detailedPingError ?: getApplication<Application>().getString(
            R.string.streaming_home_connect_selected_service,
            getSourceTypeName(sourceTypeFromServiceId(normalizedServiceId))
        )
        if (appSettings.offlineMode.value) {
            switchToDownloadedMode()
        }
        return@withLock false
    }

    private fun validateCredentials(
        serviceId: String,
        serverUrl: String,
        username: String,
        password: String
    ) {
        val hasUsername = username.isNotBlank()
        val hasPassword = password.isNotBlank()
        if (hasUsername != hasPassword) {
            throw IllegalArgumentException(
                if (!hasUsername) {
                    "Vui lòng nhập email hoặc để trống cả hai để nghe với tư cách Khách"
                } else {
                    "Vui lòng nhập mật khẩu hoặc để trống cả hai để nghe với tư cách Khách"
                }
            )
        }
    }

    private fun sourceTypeFromServiceId(serviceId: String): SourceType {
        return when (serviceId.uppercase()) {
            StreamingServiceId.CUSTOM_API -> SourceType.CUSTOM_API
            else -> SourceType.CUSTOM_API
        }
    }

    private fun serviceIdFromSourceType(sourceType: SourceType): String {
        return StreamingServiceId.CUSTOM_API
    }

    private fun normalizeServiceId(serviceId: String): String {
        val normalized = serviceId.uppercase()
        return if (StreamingServiceId.all.contains(normalized)) {
            normalized
        } else {
            StreamingServiceId.CUSTOM_API
        }
    }

    /**
     * Get display name for SourceType
     */
    fun getSourceTypeName(sourceType: SourceType): String {
        return when (sourceType) {
            SourceType.CUSTOM_API -> "XK Music"
            SourceType.SPOTIFY -> "Spotify"
            SourceType.APPLE_MUSIC -> "Apple Music"
            SourceType.YOUTUBE_MUSIC -> "YouTube Music"
            SourceType.DEEZER -> "Deezer"
            SourceType.LOCAL -> "Thiết bị"
            SourceType.UNKNOWN -> "XK Music"
        }
    }

    // -------------------------------------------------------------------------
    // Custom API Account, Moderation & Store Actions
    // -------------------------------------------------------------------------

    private fun observeListeningStatsForCloudSync() {
        viewModelScope.launch(Dispatchers.IO) {
            playbackStatsRepository.statsChangeVersion.drop(1).collect {
                val accountKey = repository.getStatsAccountKey()
                if (!accountKey.startsWith("user_")) return@collect
                cloudStatsUploadJob?.cancel()
                cloudStatsUploadJob = viewModelScope.launch(Dispatchers.IO) {
                    delay(8_000L)
                    pushActiveAccountStatsToCloud()
                }
            }
        }
    }

    private fun switchLocalStatsScope(accountKey: String) {
        val currentKey = playbackStatsRepository.getActiveAccountKey()
        if (currentKey != accountKey) {
            playbackStatsRepository.setActiveAccount(accountKey)
        }
        appSettings.switchStatsAccount(accountKey)
    }

    private suspend fun syncAccountStatsWithCloud(
        targetAccountKey: String,
        forceRefresh: Boolean
    ) {
        if (!targetAccountKey.startsWith("user_")) return
        statsSyncMutex.withLock {
            if (repository.getStatsAccountKey() != targetAccountKey) return@withLock
            switchLocalStatsScope(targetAccountKey)
            if (!NetworkUtils.isNetworkAvailable(getApplication())) return@withLock
            val cloud = repository.fetchCloudListeningStats(forceRefresh = forceRefresh)
            if (cloud != null) {
                val merged = withContext(Dispatchers.IO) {
                    playbackStatsRepository.mergeCloudSnapshot(
                        accountKey = targetAccountKey,
                        cloud = cloud
                    )
                }
                val dailyPlayCounts = withContext(Dispatchers.IO) {
                    playbackStatsRepository.getDailyPlayCountsSnapshot()
                }
                appSettings.applyMergedStatsSnapshot(
                    accountKey = targetAccountKey,
                    totalDurationMs = merged.totalDurationMs,
                    totalPlayCount = merged.totalPlayCount,
                    uniqueArtists = merged.uniqueArtists,
                    dailyPlayCounts = dailyPlayCounts
                )
                lastPulledStatsAccountKey = targetAccountKey
                if (merged.totalDurationMs > cloud.totalDurationMs ||
                    merged.totalPlayCount > cloud.totalPlayCount
                ) {
                    val pushPayload = withContext(Dispatchers.IO) {
                        playbackStatsRepository.exportCloudSnapshot()
                    }
                    repository.syncCloudListeningStats(pushPayload)
                }
            } else {
                lastPulledStatsAccountKey = targetAccountKey
                val localSnapshot = withContext(Dispatchers.IO) {
                    playbackStatsRepository.exportCloudSnapshot()
                }
                if (localSnapshot.totalDurationMs > 0L || localSnapshot.totalPlayCount > 0) {
                    repository.syncCloudListeningStats(localSnapshot)
                }
            }
        }
    }

    suspend fun pushActiveAccountStatsToCloud() {
        val accountKey = repository.getStatsAccountKey()
        if (!accountKey.startsWith("user_")) return
        if (!NetworkUtils.isNetworkAvailable(getApplication())) return
        statsSyncMutex.withLock {
            if (repository.getStatsAccountKey() != accountKey) return@withLock
            val snapshot = withContext(Dispatchers.IO) {
                playbackStatsRepository.exportCloudSnapshot()
            }
            if (snapshot.totalDurationMs > 0L || snapshot.totalPlayCount > 0) {
                repository.syncCloudListeningStats(snapshot)
            }
        }
    }

    fun refreshSiteAndAccount() {
        viewModelScope.launch {
            repository.getSiteInfo().onSuccess { _siteInfo.value = it }
            repository.getUserProfile().onSuccess { _userProfile.value = it }
            repository.getCloudSettings().onSuccess { _cloudSettings.value = it }
            val accountKey = repository.getStatsAccountKey()
            if (accountKey.startsWith("user_")) {
                syncAccountStatsWithCloud(accountKey, forceRefresh = true)
            }
        }
    }

    fun updateUserProfile(displayName: String, email: String? = null, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            repository.updateUserProfile(displayName, email)
                .onSuccess {
                    _userProfile.value = it
                    showToast(R.string.custom_api_profile_updated)
                    onResult(true, null)
                }
                .onFailure { err ->
                    onResult(false, err.message)
                }
        }
    }

    fun updateCloudSettings(settings: CustomUserSettings, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            repository.updateCloudSettings(settings)
                .onSuccess {
                    _cloudSettings.value = it
                    showToast(R.string.custom_api_settings_updated)
                    onResult(true, null)
                }
                .onFailure { err ->
                    onResult(false, err.message)
                }
        }
    }

    fun loadBlockedSongs() {
        viewModelScope.launch {
            repository.getBlockedSongs()
                .onSuccess { _blockedSongs.value = it }
                .onFailure { err -> showToast(err.message ?: "Không thể tải danh sách bài hát đã chặn") }
        }
    }

    fun blockSong(songId: String, reason: String? = null, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            repository.blockSong(songId, reason)
                .onSuccess {
                    _recommendations.value = _recommendations.value.filterNot { it.id == songId }
                    _newestSongs.value = _newestSongs.value.filterNot { it.id == songId }
                    _topCharts.value = _topCharts.value.filterNot { it.id == songId }
                    _allSongs.value = _allSongs.value.filterNot { it.id == songId }
                    loadBlockedSongs()
                    showToast(R.string.custom_api_song_blocked)
                    onComplete(true)
                }
                .onFailure { err ->
                    showToast(err.message ?: "Không thể chặn bài hát")
                    onComplete(false)
                }
        }
    }

    fun unblockSong(songId: String) {
        viewModelScope.launch {
            repository.unblockSong(songId)
                .onSuccess {
                    _blockedSongs.value = _blockedSongs.value.filterNot { it.songId == songId || songId.endsWith("::${it.songId}") }
                    showToast(R.string.custom_api_song_unblocked)
                    loadHomeContent()
                }
                .onFailure { err ->
                    showToast(err.message ?: "Không thể bỏ chặn bài hát")
                }
        }
    }

    fun reportSong(songId: String, reason: String, details: String? = null, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            repository.reportSong(songId, reason, details)
                .onSuccess {
                    showToast(R.string.custom_api_report_submitted)
                    onResult(true, null)
                }
                .onFailure { err ->
                    onResult(false, err.message)
                }
        }
    }

    fun loadPurchases() {
        viewModelScope.launch {
            repository.getPurchases()
                .onSuccess { _purchases.value = it }
                .onFailure { err -> showToast(err.message ?: "Không thể tải lịch sử mua bài hát") }
        }
    }

    fun loadVouchers() {
        viewModelScope.launch {
            repository.getVouchers()
                .onSuccess { _vouchers.value = it }
                .onFailure { err -> showToast(err.message ?: "Không thể tải danh sách voucher") }
        }
    }

    suspend fun validateVoucher(code: String, songId: String): Result<VoucherValidationResult> {
        return repository.validateVoucher(code, songId)
    }

    suspend fun getSongMeta(songId: String): Result<CustomSongMeta> {
        return repository.getSongMeta(songId)
    }

    fun playSongById(songId: String) {
        val localMatch = (_newestSongs.value + _topCharts.value + _recommendations.value + _allSongs.value)
            .firstOrNull { it.id == songId || it.externalId == songId || "CUSTOM_API::${it.externalId}" == songId }
        if (localMatch != null) {
            playSong(localMatch)
            return
        }
        viewModelScope.launch {
            repository.getSongMeta(songId)
                .onSuccess { meta ->
                    val resolved = meta.song
                    val encodedId = if (resolved.providerId.startsWith("CUSTOM_API::")) {
                        resolved.providerId
                    } else {
                        "CUSTOM_API::${resolved.providerId}"
                    }
                    val rawProviderId = resolved.providerId.removePrefix("CUSTOM_API::")
                    val encodedAlbumId = resolved.albumId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { if (it.startsWith("CUSTOM_API::")) it else "CUSTOM_API::$it" }
                    val streamingSong = StreamingSong(
                        id = encodedId,
                        title = resolved.title,
                        artist = resolved.artist,
                        album = resolved.album,
                        duration = resolved.durationMs,
                        artworkUri = resolved.artworkUrl,
                        sourceType = SourceType.CUSTOM_API,
                        streamingUrl = null,
                        previewUrl = null,
                        isPlayable = true,
                        externalId = rawProviderId,
                        albumId = encodedAlbumId,
                        albumArtist = resolved.albumArtist,
                        isFavorite = resolved.isFavorite,
                        trackNumber = resolved.trackNumber,
                        year = resolved.year,
                        genre = resolved.genre,
                        bitrate = resolved.bitrate,
                        sampleRate = resolved.sampleRate,
                        channels = resolved.channels,
                        codec = resolved.codec,
                        price = maxOf(resolved.price, meta.price),
                        isPaidTrack = meta.isPaidTrack || resolved.isPaidTrack,
                        isPurchased = meta.isPurchased || resolved.isPurchased,
                        currency = meta.currency.ifBlank { resolved.currency }
                    )
                    playQueue(listOf(streamingSong), startIndex = 0)
                }
        }
    }

    fun purchaseSong(songId: String, voucherCode: String? = null, onResult: (Result<PurchaseRecord>) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.purchaseSong(songId, voucherCode)
            result.onSuccess {
                val cleanId = songId.substringAfterLast("::")
                _allSongs.value = _allSongs.value.map { s ->
                    if (s.id == songId || s.externalId == cleanId || s.id.substringAfterLast("::") == cleanId) {
                        s.copy(isPurchased = true)
                    } else {
                        s
                    }
                }
                _topCharts.value = _topCharts.value.map { s ->
                    if (s.id == songId || s.externalId == cleanId || s.id.substringAfterLast("::") == cleanId) {
                        s.copy(isPurchased = true)
                    } else {
                        s
                    }
                }
                refreshWeeklyCharts(fetchRemote = false)
                loadPurchases()
                repository.getUserProfile().onSuccess { _userProfile.value = it }
                loadLibrary(forceSync = true)
                loadHomeContent()
                showToast(R.string.custom_api_purchase_success)
            }
            onResult(result)
        }
    }

    private fun artistIdMatchesSongArtist(artistId: String, songArtist: String): Boolean {
        val normalizedArtist = songArtist.trim().lowercase()
        if (normalizedArtist.isBlank()) {
            return false
        }

        // Normalize the artist name the same way it's done in buildArtistId:
        // lowercase, then replace spaces with underscores
        val normalizedIdFormat = normalizedArtist.replace("\\s+".toRegex(), "_")
        val normalizedId = artistId.lowercase()
        
        return normalizedId.contains(normalizedIdFormat) ||
            normalizedId.contains(normalizedArtist.replace(" ", "_")) ||
            normalizedId.contains(normalizedArtist.replace(" ", "-"))
    }

    /**
     * Play the streaming song next in the active local playback queue.
     */
    fun playNext(song: StreamingSong, localViewModel: MusicViewModel) {
        viewModelScope.launch {
            try {
                val isDownloadedSong = isSongDownloaded(song.id) || repository.isDownloaded(song.id)
                if (!isDownloadedSong && !checkAndSyncAuthentication()) {
                    _error.value = "Không thể kết nối đến hệ thống phát nhạc"
                    return@launch
                }
                val resolvedUrl = repository.getStreamingUrl(song.id)
                    ?: song.streamingUrl
                    ?: song.previewUrl

                val updatedSong = if (resolvedUrl.isNullOrBlank()) {
                    song
                } else {
                    song.copy(streamingUrl = resolvedUrl)
                }

                if (updatedSong.streamingUrl.isNullOrBlank()) {
                    _error.value = "Không thể lấy đường dẫn phát nhạc cho bài hát này"
                    android.widget.Toast.makeText(getApplication(), R.string.streamingmusicviewmodel_failed_to_play_next, android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Sync VM queue state flow representation if needed
                val currentQueue = _queue.value.toMutableList()
                val currentSongId = _currentSong.value?.id
                val currentIndex = currentQueue.indexOfFirst { it.id == currentSongId }
                val insertIndex = if (currentIndex >= 0) currentIndex + 1 else 0
                if (insertIndex in 0..currentQueue.size) {
                    currentQueue.add(insertIndex, updatedSong)
                } else {
                    currentQueue.add(updatedSong)
                }
                _queue.value = currentQueue

                // Delegate to localViewModel
                val localSong = updatedSong.toLocalSong()
                localViewModel.playNext(localSong)
            } catch (e: Exception) {
                android.util.Log.e("StreamingMusicViewModel", "Error in playNext for streaming song", e)
                _error.value = "Không thể phát kế tiếp: ${e.message}"
            }
        }
    }

    /**
     * Add the streaming song to the end of the active local playback queue.
     */
    fun addSongToQueue(song: StreamingSong, localViewModel: MusicViewModel) {
        viewModelScope.launch {
            try {
                val isDownloadedSong = isSongDownloaded(song.id) || repository.isDownloaded(song.id)
                if (!isDownloadedSong && !checkAndSyncAuthentication()) {
                    _error.value = "Không thể kết nối đến hệ thống phát nhạc"
                    return@launch
                }
                val resolvedUrl = repository.getStreamingUrl(song.id)
                    ?: song.streamingUrl
                    ?: song.previewUrl

                val updatedSong = if (resolvedUrl.isNullOrBlank()) {
                    song
                } else {
                    song.copy(streamingUrl = resolvedUrl)
                }

                if (updatedSong.streamingUrl.isNullOrBlank()) {
                    _error.value = "Không thể lấy đường dẫn phát nhạc cho bài hát này"
                    android.widget.Toast.makeText(getApplication(), R.string.streamingmusicviewmodel_failed_to_add_to, android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Sync VM queue state flow representation if needed
                val currentQueue = _queue.value.toMutableList()
                currentQueue.add(updatedSong)
                _queue.value = currentQueue

                // Delegate to localViewModel
                val localSong = updatedSong.toLocalSong()
                localViewModel.addSongToQueue(localSong)
            } catch (e: Exception) {
                android.util.Log.e("StreamingMusicViewModel", "Error in addSongToQueue for streaming song", e)
                _error.value = "Không thể thêm vào hàng đợi: ${e.message}"
            }
        }
    }

    private fun StreamingSong.toLocalSong(): Song {
        val playbackUri = when {
            !streamingUrl.isNullOrBlank() -> (streamingUrl).toUri()
            !previewUrl.isNullOrBlank() -> (previewUrl).toUri()
            else -> ("streaming://track/$id").toUri()
        }

        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumId = albumId.orEmpty(),
            duration = duration,
            uri = playbackUri,
            artworkUri = artworkUri?.takeIf { it.isNotBlank() }?.let(Uri::parse),
            albumArtist = albumArtist,
            price = price,
            isPaidTrack = isPaidTrack,
            isPurchased = isPurchased,
            currency = currency
        )
    }
}

/**
 * Container for streaming search results.
 */
data class StreamingSearchResults(
    val songs: List<StreamingSong> = emptyList(),
    val albums: List<StreamingAlbum> = emptyList(),
    val artists: List<StreamingArtist> = emptyList(),
    val playlists: List<StreamingPlaylist> = emptyList()
) {
    val isEmpty: Boolean
        get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
    
    val totalCount: Int
        get() = songs.size + albums.size + artists.size + playlists.size
}

/**
 * Stages of library synchronization for streaming services.
 */
enum class StreamingSyncStage {
    Idle,
    Syncing,
    Complete,
    Error
}

/**
 * Live library sync progress state.
 */
data class StreamingSyncProgress(
    val isSyncing: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val songsCount: Int = 0,
    val stage: StreamingSyncStage = StreamingSyncStage.Idle
)
