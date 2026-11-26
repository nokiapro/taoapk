/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import chromahub.rhythm.app.network.FirebaseAuthService
import chromahub.rhythm.app.network.FirebaseLoginReq
import chromahub.rhythm.app.network.MemberDto
import chromahub.rhythm.app.network.SongMasterDto
import chromahub.rhythm.app.network.UserPurchaseReq
import chromahub.rhythm.app.network.XkFirestoreHelper
import chromahub.rhythm.app.network.XkMusicApiService
import chromahub.rhythm.app.network.XuanKenFirebaseService
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.LyricsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.util.UUID

class FirebaseProviderException(val code: Int, message: String) : Exception(message)

/**
 * Coordinates the 3-tier hybrid architecture matching `xk-music.zip`:
 * 1. Primary API (`https://xkmu.xuanken.name.vn/api/v1/` with `x-api-key`, `x-user-token`, and `Bearer` headers)
 * 2. Firebase Identity Toolkit Auth + Firebase RTDB (`newmusic-5625b-default-rtdb`) on 404/fallback
 * 3. Firestore REST fallback (`XkFirestoreHelper`) for profiles, purchases, playlists, and `song_masters`
 */
class XuanKenFirebaseClient internal constructor(
    private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val tokenStorage = SecureTokenStorage(context)
    private val refreshMutex = Mutex()
    private val catalogMutex = Mutex()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var uid: String = prefs.getString(KEY_UID, "") ?: ""

    @Volatile
    private var email: String = prefs.getString(KEY_EMAIL, "") ?: ""

    @Volatile
    private var displayName: String = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""

    @Volatile
    private var idToken: String = tokenStorage.getToken(KEY_ID_TOKEN)

    @Volatile
    private var refreshToken: String = tokenStorage.getToken(KEY_REFRESH_TOKEN)

    @Volatile
    private var tokenExpiresAtMs: Long = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)

    @Volatile
    private var guestConnected: Boolean = prefs.getBoolean(KEY_GUEST_CONNECTED, false)

    @Volatile
    private var isAdminUser: Boolean = prefs.getBoolean(KEY_IS_ADMIN, false)

    @Volatile
    private var lastConnectionError: String? = null

    @Volatile
    private var cachedSongEntries: Map<String, ParsedSongEntry> = emptyMap()

    @Volatile
    private var cachedAlbumsEntries: List<ParsedAlbumEntry> = emptyList()

    @Volatile
    private var cachedPlaylistsMap: Map<String, ParsedPlaylistEntry> = emptyMap()

    @Volatile
    private var cachedArtistsList: List<ProviderArtist> = emptyList()

    @Volatile
    private var cachedMember: MemberDto? = null

    @Volatile
    private var cachedProfile: CustomUserProfile? = null

    @Volatile
    private var cachedPurchasedIds: MutableSet<String> = linkedSetOf()

    @Volatile
    private var cachedLikedSongIds: MutableSet<String> = linkedSetOf()

    @Volatile
    private var cachedLikedAlbumIds: MutableSet<String> = linkedSetOf()

    @Volatile
    private var cachedBlockedSongIds: MutableSet<String> = linkedSetOf()

    @Volatile
    private var cachedMasterUrls: MutableMap<String, String> = mutableMapOf()

    @Volatile
    private var cachedCloudListeningStats: CloudListeningStats? = null

    @Volatile
    private var cachedSiteInfo: CustomSiteInfo = CustomSiteInfo(
        siteName = "XuanKen Music",
        description = "xkmu.xuanken.name.vn • Firebase RTDB & Firestore"
    )

    init {
        if (idToken.isNotBlank()) {
            XkMusicApiService.setUserToken(idToken)
        }
    }

    fun isApiKeyConfigured(): Boolean =
        XkMusicApiService.isApiKeyConfigured() || FirebaseAuthService.isApiKeyConfigured()

    fun isPrimaryApiKeyConfigured(): Boolean = XkMusicApiService.isApiKeyConfigured()

    fun isFirebaseKeyConfigured(): Boolean = FirebaseAuthService.isApiKeyConfigured()

    fun isConnected(): Boolean = hasUserSession() || guestConnected

    fun hasUserSession(): Boolean = uid.isNotBlank() && (idToken.isNotBlank() || refreshToken.isNotBlank())

    fun isGuestSession(): Boolean = guestConnected && !hasUserSession()

    fun isAdmin(): Boolean = isAdminUser

    fun getUid(): String = uid

    fun getEmail(): String = email

    fun getUsername(): String = displayName.ifBlank { email }.ifBlank { if (guestConnected) "Guest" else "" }

    fun getLastError(): String? = lastConnectionError

    // -------------------------------------------------------------------------
    // Authentication (Primary /api/v1/auth/* -> Firebase Auth fallback)
    // -------------------------------------------------------------------------

    suspend fun login(
        emailInput: String,
        passwordInput: String,
        saveCredentials: Boolean = true
    ): Result<ProviderConnectionResult> = withContext(Dispatchers.IO) {
        val cleanEmail = emailInput.trim()
        val isGuestAttempt = cleanEmail.isBlank() && passwordInput.isBlank()

        if (isGuestAttempt) {
            return@withContext connectAsGuest()
        }
        if (cleanEmail.isBlank() || passwordInput.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Enter both email and password, or leave both blank to browse as Guest")
            )
        }

        val canCallPrimary = XkMusicApiService.isApiKeyConfigured() || XkMusicApiService.baseUrlOverride != null
        val canCallFirebase = FirebaseAuthService.isApiKeyConfigured() || FirebaseAuthService.authBaseUrlOverride != null

        if (!canCallPrimary && !canCallFirebase) {
            return@withContext Result.failure(
                IllegalStateException("Missing XKMU_API_KEY / FIREBASE_WEB_API_KEY. Set them in the AI Studio Secrets panel.")
            )
        }

        // 1. Try POST /api/v1/auth/login first (when primary API key/override is available)
        if (canCallPrimary) {
            val payload = JSONObject().apply {
                put("email", cleanEmail)
                put("password", passwordInput)
            }.toString().toRequestBody(jsonMediaType)

            val primaryRes = runCatching { XkMusicApiService.api().login(payload) }.getOrNull()
            if (primaryRes != null) {
                val body = primaryRes.body()?.string()?.trim().orEmpty()
                val errBody = primaryRes.errorBody()?.string()?.trim().orEmpty()
                if (primaryRes.isSuccessful && XkResponseParser.isJsonBody(body)) {
                    val parsed = XkResponseParser.parseAuthSession(body, fallbackEmail = cleanEmail)
                    if (parsed != null) {
                        return@withContext finalizeAuthenticatedSession(parsed, saveCredentials)
                    }
                } else if (primaryRes.code() != 404 && XkResponseParser.isJsonBody(errBody)) {
                    val msg = XkResponseParser.parseErrorMessage(errBody)
                        ?: "Sign-in failed (HTTP ${primaryRes.code()})"
                    lastConnectionError = msg
                    return@withContext Result.failure(FirebaseProviderException(primaryRes.code(), msg))
                }
            }
        }

        // 2. Fallback: Firebase Identity Toolkit signInWithPassword
        if (!canCallFirebase) {
            val msg = "Primary API unreachable and FIREBASE_WEB_API_KEY is not configured."
            lastConnectionError = msg
            return@withContext Result.failure(IllegalStateException(msg))
        }

        try {
            val res = FirebaseAuthService.authApi().login(
                apiKey = FirebaseAuthService.getApiKey(),
                body = FirebaseLoginReq(email = cleanEmail, password = passwordInput, returnSecureToken = true)
            )
            if (res.idToken.isBlank() || res.localId.isBlank()) {
                return@withContext Result.failure(
                    FirebaseProviderException(401, "Firebase login succeeded without idToken or localId")
                )
            }
            val expiresSec = res.expiresIn.toLongOrNull() ?: 3600L
            val parsed = ParsedAuthSession(
                idToken = res.idToken,
                refreshToken = res.refreshToken,
                expiresAtMs = System.currentTimeMillis() + expiresSec * 1000L,
                uid = res.localId,
                email = res.email.ifBlank { cleanEmail },
                displayName = res.displayName.orEmpty().ifBlank { cleanEmail.substringBefore("@") }
            )
            finalizeAuthenticatedSession(parsed, saveCredentials)
        } catch (e: HttpException) {
            val message = parseFirebaseAuthError(e)
            lastConnectionError = message
            Result.failure(FirebaseProviderException(e.code(), message))
        } catch (e: IOException) {
            lastConnectionError = e.message
            Result.failure(e)
        } catch (e: Exception) {
            lastConnectionError = e.message
            Result.failure(e)
        }
    }

    suspend fun register(
        emailInput: String,
        passwordInput: String,
        displayNameInput: String,
        saveCredentials: Boolean = true
    ): Result<ProviderConnectionResult> = withContext(Dispatchers.IO) {
        val cleanEmail = emailInput.trim()
        val cleanName = displayNameInput.trim().ifBlank { cleanEmail.substringBefore("@") }
        if (cleanEmail.isBlank() || passwordInput.length < 6) {
            return@withContext Result.failure(
                IllegalArgumentException("Vui lòng nhập email hợp lệ và mật khẩu tối thiểu 6 ký tự.")
            )
        }

        val canCallPrimary = XkMusicApiService.isApiKeyConfigured() || XkMusicApiService.baseUrlOverride != null
        val canCallFirebase = FirebaseAuthService.isApiKeyConfigured() || FirebaseAuthService.authBaseUrlOverride != null

        // 1. Try POST /api/v1/auth/register
        if (canCallPrimary) {
            val payload = JSONObject().apply {
                put("email", cleanEmail)
                put("password", passwordInput)
                put("displayName", cleanName)
            }.toString().toRequestBody(jsonMediaType)

            val primaryRes = runCatching { XkMusicApiService.api().register(payload) }.getOrNull()
            if (primaryRes != null) {
                val body = primaryRes.body()?.string()?.trim().orEmpty()
                val errBody = primaryRes.errorBody()?.string()?.trim().orEmpty()
                if (primaryRes.isSuccessful && XkResponseParser.isJsonBody(body)) {
                    val parsed = XkResponseParser.parseAuthSession(
                        body,
                        fallbackEmail = cleanEmail,
                        fallbackDisplayName = cleanName
                    )
                    if (parsed != null) {
                        return@withContext finalizeAuthenticatedSession(parsed, saveCredentials)
                    }
                } else if (primaryRes.code() != 404 && XkResponseParser.isJsonBody(errBody)) {
                    val msg = XkResponseParser.parseErrorMessage(errBody)
                        ?: "Registration failed (HTTP ${primaryRes.code()})"
                    lastConnectionError = msg
                    return@withContext Result.failure(FirebaseProviderException(primaryRes.code(), msg))
                }
            }
        }

        // 2. Fallback: Firebase signUp + updateProfile + PATCH /members/{uid}.json
        if (!canCallFirebase) {
            return@withContext Result.failure(
                IllegalStateException("Missing FIREBASE_WEB_API_KEY. Set it in the AI Studio Secrets panel.")
            )
        }

        try {
            val fbPayload = JSONObject().apply {
                put("email", cleanEmail)
                put("password", passwordInput)
                put("returnSecureToken", true)
            }.toString().toRequestBody(jsonMediaType)

            val res = FirebaseAuthService.authApi().signUp(FirebaseAuthService.getApiKey(), fbPayload)
            val body = res.body()?.string().orEmpty()
            if (!res.isSuccessful) {
                val errRaw = res.errorBody()?.string()
                val msg = mapFirebaseAuthErrorMessage(XkResponseParser.parseErrorMessage(errRaw))
                    ?: "Đăng ký thất bại (HTTP ${res.code()})"
                lastConnectionError = msg
                return@withContext Result.failure(FirebaseProviderException(res.code(), msg))
            }

            val parsed = XkResponseParser.parseAuthSession(
                body,
                fallbackEmail = cleanEmail,
                fallbackDisplayName = cleanName
            ) ?: return@withContext Result.failure(
                FirebaseProviderException(500, "Không thể xử lý phản hồi đăng ký.")
            )

            // Update Firebase Auth profile displayName & initialize RTDB /members/{uid}
            val updateBody = JSONObject().apply {
                put("idToken", parsed.idToken)
                put("displayName", cleanName)
                put("returnSecureToken", false)
            }.toString().toRequestBody(jsonMediaType)
            runCatching { FirebaseAuthService.authApi().updateProfile(FirebaseAuthService.getApiKey(), updateBody) }

            if (parsed.uid.isNotBlank()) {
                runCatching {
                    XuanKenFirebaseService.api().patchMember(
                        uid = parsed.uid,
                        idToken = parsed.idToken,
                        updates = mapOf(
                            "uid" to parsed.uid,
                            "email" to cleanEmail,
                            "displayName" to cleanName,
                            "balance" to 0L,
                            "role" to "member"
                        )
                    )
                }
            }

            finalizeAuthenticatedSession(parsed.copy(displayName = cleanName), saveCredentials)
        } catch (e: Exception) {
            lastConnectionError = e.message
            Result.failure(e)
        }
    }

    private suspend fun finalizeAuthenticatedSession(
        parsed: ParsedAuthSession,
        saveCredentials: Boolean
    ): Result<ProviderConnectionResult> {
        uid = parsed.uid
        email = parsed.email
        idToken = parsed.idToken
        refreshToken = parsed.refreshToken
        tokenExpiresAtMs = parsed.expiresAtMs
        guestConnected = false
        lastConnectionError = null
        cachedMasterUrls.clear()
        cachedCloudListeningStats = null
        cachedPurchasedIds = parsed.purchasedIds.toMutableSet()
        cachedLikedSongIds = parsed.likedIds.toMutableSet()
        isAdminUser = parsed.role.equals("admin", ignoreCase = true)

        XkMusicApiService.setUserToken(parsed.idToken)

        // Hydrate full member + admin + Firestore profile immediately
        val profile = runCatching { fetchAndMergeMemberState(parsed.idToken) }.getOrNull()
        val resolvedName = firstNonBlank(
            profile?.displayName,
            parsed.displayName,
            email,
            uid
        )
        displayName = resolvedName

        if (saveCredentials) {
            tokenStorage.putToken(KEY_ID_TOKEN, idToken)
            tokenStorage.putToken(KEY_REFRESH_TOKEN, refreshToken)
            prefs.edit {
                putString(KEY_UID, uid)
                putString(KEY_EMAIL, email)
                putString(KEY_DISPLAY_NAME, resolvedName)
                putLong(KEY_EXPIRES_AT_MS, tokenExpiresAtMs)
                putBoolean(KEY_IS_ADMIN, isAdminUser)
                putBoolean(KEY_GUEST_CONNECTED, false)
            }
        } else {
            tokenStorage.clearAll()
            prefs.edit {
                remove(KEY_UID)
                remove(KEY_EMAIL)
                remove(KEY_DISPLAY_NAME)
                remove(KEY_EXPIRES_AT_MS)
                remove(KEY_IS_ADMIN)
                putBoolean(KEY_GUEST_CONNECTED, false)
            }
        }

        val connectedHost = if (XkMusicApiService.isApiKeyConfigured()) {
            XkMusicApiService.BASE_URL.trimEnd('/')
        } else {
            XuanKenFirebaseService.BASE_URL.trimEnd('/')
        }

        return Result.success(
            ProviderConnectionResult(
                displayName = resolvedName,
                serverUrl = connectedHost
            )
        )
    }

    suspend fun connectAsGuest(): Result<ProviderConnectionResult> = withContext(Dispatchers.IO) {
        try {
            // Try primary /api/v1/home or /api/v1/songs first, then RTDB songs.json
            val primarySongs = tryPrimary { XkMusicApiService.api().getSongs() }
            if (primarySongs != null) {
                val entries = XkResponseParser.parseSongsEntries(primarySongs)
                if (entries.isNotEmpty()) {
                    cachedSongEntries = entries.associateBy { it.song.providerId }
                }
            } else {
                refreshRtdbCatalogLocked(forceRefresh = true)
            }

            clearUserSessionOnly()
            guestConnected = true
            displayName = "Khách"
            lastConnectionError = null
            prefs.edit {
                putBoolean(KEY_GUEST_CONNECTED, true)
                putString(KEY_DISPLAY_NAME, "Khách")
            }
            Result.success(
                ProviderConnectionResult(
                    displayName = "Khách",
                    serverUrl = XkMusicApiService.BASE_URL.trimEnd('/')
                )
            )
        } catch (e: HttpException) {
            val msg = "Kết nối thất bại (HTTP ${e.code()}): ${e.message()}"
            lastConnectionError = msg
            Result.failure(FirebaseProviderException(e.code(), msg))
        } catch (e: Exception) {
            lastConnectionError = e.message
            Result.failure(e)
        }
    }

    suspend fun ping(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isConnected()) {
            lastConnectionError = "Chưa kết nối"
            return@withContext Result.failure(IllegalStateException("Chưa kết nối"))
        }
        return@withContext try {
            if (hasUserSession()) {
                val token = getValidIdToken()
                if (token.isBlank()) {
                    lastConnectionError = "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
                    return@withContext Result.failure(FirebaseProviderException(401, lastConnectionError!!))
                }
                withTokenRefresh { validToken ->
                    fetchAndMergeMemberState(validToken)
                }
                lastConnectionError = null
                Result.success(true)
            } else {
                val primary = tryPrimary { XkMusicApiService.api().getSiteInfo() }
                if (primary == null) {
                    refreshRtdbCatalogLocked(forceRefresh = false)
                }
                lastConnectionError = null
                Result.success(true)
            }
        } catch (e: Exception) {
            lastConnectionError = e.message
            Result.failure(e)
        }
    }

    fun logout() {
        clearUserSessionOnly()
        guestConnected = false
        displayName = ""
        cachedSongEntries = emptyMap()
        cachedAlbumsEntries = emptyList()
        cachedPlaylistsMap = emptyMap()
        cachedArtistsList = emptyList()
        lastConnectionError = null
        prefs.edit { clear() }
    }

    private fun clearUserSessionOnly() {
        uid = ""
        email = ""
        idToken = ""
        refreshToken = ""
        tokenExpiresAtMs = 0L
        isAdminUser = false
        cachedMember = null
        cachedProfile = null
        cachedPurchasedIds.clear()
        cachedLikedSongIds.clear()
        cachedLikedAlbumIds.clear()
        cachedBlockedSongIds.clear()
        cachedMasterUrls.clear()
        cachedCloudListeningStats = null
        XkMusicApiService.setUserToken(null)
        tokenStorage.clearAll()
        prefs.edit {
            remove(KEY_UID)
            remove(KEY_EMAIL)
            remove(KEY_EXPIRES_AT_MS)
            remove(KEY_IS_ADMIN)
        }
    }

    suspend fun getValidIdToken(): String {
        if (uid.isBlank()) return ""
        val current = idToken
        val now = System.currentTimeMillis()
        if (current.isNotBlank() && (tokenExpiresAtMs <= 0L || now < tokenExpiresAtMs - TOKEN_REFRESH_MARGIN_MS)) {
            XkMusicApiService.setUserToken(current)
            return current
        }
        val currentRefresh = refreshToken
        if (currentRefresh.isBlank()) {
            if (current.isNotBlank()) XkMusicApiService.setUserToken(current)
            return current
        }
        return if (refreshTokenLocked()) idToken else ""
    }

    private suspend fun refreshTokenLocked(): Boolean = refreshMutex.withLock {
        val currentRefresh = refreshToken
        if (currentRefresh.isBlank()) return@withLock false
        val now = System.currentTimeMillis()
        if (idToken.isNotBlank() && tokenExpiresAtMs > 0L && now < tokenExpiresAtMs - TOKEN_REFRESH_MARGIN_MS) {
            XkMusicApiService.setUserToken(idToken)
            return@withLock true
        }

        // 1. Try POST /api/v1/auth/refresh first
        if (XkMusicApiService.isApiKeyConfigured() || XkMusicApiService.baseUrlOverride != null) {
            val payload = JSONObject().apply {
                put("refreshToken", currentRefresh)
                put("refresh_token", currentRefresh)
            }.toString().toRequestBody(jsonMediaType)
            val primaryRes = runCatching { XkMusicApiService.api().refreshToken(payload) }.getOrNull()
            if (primaryRes != null && primaryRes.isSuccessful) {
                val body = primaryRes.body()?.string()?.trim().orEmpty()
                if (XkResponseParser.isJsonBody(body)) {
                    val parsed = XkResponseParser.parseAuthSession(body, fallbackEmail = email, fallbackDisplayName = displayName)
                    if (parsed != null) {
                        idToken = parsed.idToken
                        refreshToken = parsed.refreshToken.ifBlank { currentRefresh }
                        tokenExpiresAtMs = parsed.expiresAtMs
                        if (parsed.uid.isNotBlank()) uid = parsed.uid
                        XkMusicApiService.setUserToken(idToken)
                        persistRefreshedTokens()
                        return@withLock true
                    }
                }
            }
        }

        // 2. Fallback: Firebase securetoken refresh
        val canCallFirebase = FirebaseAuthService.isApiKeyConfigured() || FirebaseAuthService.secureTokenBaseUrlOverride != null
        if (!canCallFirebase) return@withLock false

        return@withLock try {
            val res = FirebaseAuthService.secureTokenApi().refreshToken(
                apiKey = FirebaseAuthService.getApiKey(),
                grantType = "refresh_token",
                refreshToken = currentRefresh
            )
            if (res.idToken.isBlank()) return@withLock false

            val expiresSec = res.expiresIn.toLongOrNull() ?: 3600L
            val newExpiryMs = System.currentTimeMillis() + expiresSec * 1000L
            idToken = res.idToken
            refreshToken = res.refreshToken.ifBlank { currentRefresh }
            tokenExpiresAtMs = newExpiryMs
            if (res.userId.isNotBlank()) uid = res.userId

            XkMusicApiService.setUserToken(idToken)
            persistRefreshedTokens()
            true
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 401 || e.code() == 403) {
                clearUserSessionOnly()
            }
            Log.w(TAG, "Token refresh failed (${e.code()})", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh error", e)
            false
        }
    }

    private fun persistRefreshedTokens() {
        if (tokenStorage.hasToken(KEY_REFRESH_TOKEN) || prefs.contains(KEY_UID)) {
            tokenStorage.putToken(KEY_ID_TOKEN, idToken)
            tokenStorage.putToken(KEY_REFRESH_TOKEN, refreshToken)
            prefs.edit { putLong(KEY_EXPIRES_AT_MS, tokenExpiresAtMs) }
        }
    }

    private suspend fun <T> withTokenRefresh(block: suspend (idToken: String) -> T): T {
        val token = getValidIdToken()
        if (token.isBlank()) {
            throw FirebaseProviderException(401, "Vui lòng đăng nhập tài khoản để sử dụng tính năng này")
        }
        return try {
            block(token)
        } catch (e: HttpException) {
            if (e.code() == 401 && refreshToken.isNotBlank() && refreshTokenLocked()) {
                block(idToken)
            } else {
                throw e
            }
        }
    }

    fun isSongPurchased(songId: String): Boolean {
        val cleanId = stripProviderPrefix(songId)
        val entry = cachedSongEntries[cleanId]
        val isPaid = entry?.let { it.isPaid || it.price > 0.0 } ?: false
        return if (!isPaid) true else (cleanId in cachedPurchasedIds || isAdminUser)
    }

    fun isSongLiked(songId: String): Boolean = stripProviderPrefix(songId) in cachedLikedSongIds

    fun getCachedPurchasedSongIds(): Set<String> = cachedPurchasedIds.toSet()

    fun getCachedEntryPlayCount(songId: String): Long =
        cachedSongEntries[stripProviderPrefix(songId)]?.playCount ?: 0L

    private fun enrichSongCommerce(
        song: ProviderSong,
        entry: ParsedSongEntry? = cachedSongEntries[song.providerId]
    ): ProviderSong {
        val isPaid = song.isPaidTrack || (entry?.isPaid == true) || song.price > 0.0 || (entry?.price ?: 0.0) > 0.0
        val resolvedPrice = maxOf(song.price, entry?.price ?: 0.0)
        val resolvedCurrency = song.currency.ifBlank { entry?.currency ?: "VND" }
        val purchased = !isPaid ||
            song.providerId in cachedPurchasedIds || isAdminUser
        return song.copy(
            isFavorite = song.isFavorite || song.providerId in cachedLikedSongIds,
            price = resolvedPrice,
            isPaidTrack = isPaid,
            isPurchased = purchased,
            currency = resolvedCurrency
        )
    }

    // -------------------------------------------------------------------------
    // Public Music Catalog (Primary api/v1 -> RTDB fallback merging album songs)
    // -------------------------------------------------------------------------

    suspend fun getHomeBundle(): Result<CustomHomePayload> = withContext(Dispatchers.IO) {
        if (hasUserSession() && cachedProfile == null) {
            runCatching { withTokenRefresh { token -> fetchAndMergeMemberState(token) } }
        }
        val primary = tryPrimary { XkMusicApiService.api().getHome() }
        if (primary != null) {
            XkResponseParser.parseSiteInfo(primary)?.let { cachedSiteInfo = it }
            val home = XkResponseParser.parseHomePayload(primary)
            if (home != null) {
                return@withContext Result.success(
                    home.copy(
                        recommendations = home.recommendations.map { enrichSongCommerce(it) },
                        newestSongs = home.newestSongs.map { enrichSongCommerce(it) },
                        charts = home.charts.map { enrichSongCommerce(it) }
                    )
                )
            }
        }
        try {
            val songs = getPublicSongs(forceRefresh = true, sort = "newest").getOrDefault(emptyList())
            val albums = getAlbums().getOrDefault(emptyList())
            val playlists = getPlaylists().getOrDefault(emptyList())
            val artists = getArtists().getOrDefault(emptyList())
            val newestSorted = songs.withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<ProviderSong>> { it.value.createdAtMs }
                        .thenByDescending { it.index }
                )
                .map { it.value }
                .take(20)
            Result.success(
                CustomHomePayload(
                    recommendations = songs.take(24),
                    newestSongs = newestSorted,
                    charts = songs.take(24),
                    newReleases = albums.take(24),
                    featuredPlaylists = playlists.take(24),
                    artists = artists
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPublicSongs(forceRefresh: Boolean = false, sort: String = "newest"): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        try {
            if (forceRefresh || cachedSongEntries.isEmpty()) {
                // 1. Try primary /api/v1/songs
                val primary = tryPrimary { XkMusicApiService.api().getSongs(sort = sort) }
                if (primary != null) {
                    val entries = XkResponseParser.parseSongsEntries(primary)
                    if (entries.isNotEmpty()) {
                        cachedSongEntries = entries.associateBy { it.song.providerId }
                    }
                } else {
                    refreshRtdbCatalogLocked(forceRefresh = true)
                }
            }

            if (hasUserSession() && cachedProfile == null) {
                runCatching { withTokenRefresh { token -> fetchAndMergeMemberState(token) } }
            }

            val blockedIds = cachedBlockedSongIds.toSet()
            val songs = cachedSongEntries.values
                .asSequence()
                .filter { !it.isHidden }
                .filterNot { it.song.providerId in blockedIds }
                .map { enrichSongCommerce(it.song, it) }
                .toList()
            Result.success(songs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSongDetail(songId: String): Result<CustomSongMeta> = withContext(Dispatchers.IO) {
        val cleanId = stripProviderPrefix(songId)
        try {
            var entry: ParsedSongEntry? = null
            // 1. Try primary GET /api/v1/songs/{id}
            val primary = tryPrimary { XkMusicApiService.api().getSongById(cleanId) }
            if (primary != null) {
                entry = XkResponseParser.parseSongEntry(primary)
            }

            // 2. Fallback to cached / RTDB catalog
            if (entry == null) {
                if (cachedSongEntries.isEmpty()) {
                    refreshRtdbCatalogLocked(forceRefresh = false)
                }
                entry = cachedSongEntries[cleanId]
            }
            if (entry == null) {
                return@withContext Result.failure(FirebaseProviderException(404, "Không tìm thấy bài hát ($cleanId)"))
            }

            if (hasUserSession() && cachedProfile == null) {
                runCatching { withTokenRefresh { token -> fetchAndMergeMemberState(token) } }
            }

            val isPurchased = !entry.isPaid || entry.price <= 0.0 ||
                cleanId in cachedPurchasedIds || isAdminUser
            val isLiked = cleanId in cachedLikedSongIds
            val isBlocked = cleanId in cachedBlockedSongIds
            val lyrics = hydrateLyrics(entry.rawLyrics, entry.lrcUrl)

            Result.success(
                CustomSongMeta(
                    song = enrichSongCommerce(entry.song, entry).copy(isFavorite = isLiked, isPurchased = isPurchased),
                    price = entry.price,
                    currency = entry.currency,
                    isPurchased = isPurchased,
                    isPaidTrack = entry.isPaid,
                    isLiked = isLiked,
                    isBlocked = isBlocked,
                    playCount = entry.playCount,
                    lyrics = lyrics
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun hydrateLyrics(rawLyrics: String?, lrcUrl: String?): LyricsData? {
        if (!rawLyrics.isNullOrBlank()) {
            return XkResponseParser.parseLyricsText(rawLyrics)
        }
        if (!lrcUrl.isNullOrBlank()) {
            val text = runCatching {
                val res = XkMusicApiService.api().getTextFromUrl(lrcUrl)
                if (res.isSuccessful) res.body()?.string()?.trim() else null
            }.getOrNull()
            return XkResponseParser.parseLyricsText(text)
        }
        return null
    }

    suspend fun getAlbums(): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        try {
            val primary = tryPrimary { XkMusicApiService.api().getAlbums() }
            if (primary != null) {
                val entries = XkResponseParser.parseAlbumsEntries(primary)
                if (entries.isNotEmpty()) {
                    cachedAlbumsEntries = entries
                    return@withContext Result.success(entries.map { it.album })
                }
            }
            refreshRtdbCatalogLocked(forceRefresh = cachedAlbumsEntries.isEmpty())
            Result.success(cachedAlbumsEntries.map { it.album })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbumDetail(albumId: String): Result<CustomAlbumDetail> = withContext(Dispatchers.IO) {
        val cleanId = stripProviderPrefix(albumId)
        try {
            // 1. Try GET /api/v1/albums/{id}
            val primary = tryPrimary { XkMusicApiService.api().getAlbumById(cleanId) }
            if (primary != null) {
                val detail = XkResponseParser.parseAlbumDetail(primary)
                if (detail != null) {
                    val liked = cleanId in cachedLikedAlbumIds
                    return@withContext Result.success(
                        CustomAlbumDetail(
                            album = detail.album,
                            songs = detail.embeddedSongs.map { enrichSongCommerce(it) },
                            isLiked = liked
                        )
                    )
                }
            }

            // 2. Fallback to RTDB exclusive_albums + songs
            if (cachedAlbumsEntries.isEmpty()) {
                refreshRtdbCatalogLocked(forceRefresh = false)
            }
            val entry = cachedAlbumsEntries.firstOrNull { it.album.providerId == cleanId }
                ?: return@withContext Result.failure(FirebaseProviderException(404, "Không tìm thấy album ($cleanId)"))

            val allSongs = getPublicSongs().getOrDefault(emptyList())
            val songs = when {
                entry.embeddedSongs.isNotEmpty() -> entry.embeddedSongs.map { enrichSongCommerce(it) }
                entry.embeddedSongIds.isNotEmpty() -> allSongs.filter { it.providerId in entry.embeddedSongIds }
                else -> allSongs.filter {
                    it.albumId == cleanId || it.album.equals(entry.album.title, ignoreCase = true)
                }
            }
            Result.success(
                CustomAlbumDetail(
                    album = entry.album.copy(songCount = maxOf(entry.album.songCount, songs.size)),
                    songs = songs,
                    isLiked = cleanId in cachedLikedAlbumIds
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArtists(): Result<List<ProviderArtist>> = withContext(Dispatchers.IO) {
        try {
            val primary = tryPrimary { XkMusicApiService.api().getArtists() }
            if (primary != null) {
                val artists = XkResponseParser.parseArtistsList(primary)
                if (artists.isNotEmpty()) {
                    cachedArtistsList = artists
                    return@withContext Result.success(artists)
                }
            }
            refreshRtdbCatalogLocked(forceRefresh = cachedArtistsList.isEmpty())
            Result.success(cachedArtistsList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArtistDetail(artistId: String): Result<CustomArtistDetail> = withContext(Dispatchers.IO) {
        val cleanId = stripProviderPrefix(artistId)
        try {
            val primary = tryPrimary { XkMusicApiService.api().getArtistById(cleanId) }
            if (primary != null) {
                XkResponseParser.parseArtistDetail(primary)?.let { detail ->
                    return@withContext Result.success(
                        detail.copy(topTracks = detail.topTracks.map { enrichSongCommerce(it) })
                    )
                }
            }

            val decodedQueryId = cleanId.replace("_slash_", "/")
            val artists = getArtists().getOrDefault(emptyList())
            val artist = artists.firstOrNull {
                it.providerId.equals(cleanId, ignoreCase = true) ||
                    it.providerId.equals(decodedQueryId, ignoreCase = true) ||
                    it.name.equals(cleanId, ignoreCase = true) ||
                    it.name.equals(decodedQueryId, ignoreCase = true)
            } ?: ProviderArtist(providerId = decodedQueryId, name = decodedQueryId)

            val allSongs = getPublicSongs().getOrDefault(emptyList())
            val topTracks = allSongs.filter { song ->
                song.artist.equals(artist.name, ignoreCase = true) ||
                    chromahub.rhythm.app.util.ArtistSeparator.splitArtistNames(song.artist)
                        .any { it.equals(artist.name, ignoreCase = true) } ||
                    song.artist.contains(artist.name, ignoreCase = true)
            }
            val albums = getAlbums().getOrDefault(emptyList()).filter { album ->
                album.artist.equals(artist.name, ignoreCase = true) ||
                    chromahub.rhythm.app.util.ArtistSeparator.splitArtistNames(album.artist)
                        .any { it.equals(artist.name, ignoreCase = true) } ||
                    album.artist.contains(artist.name, ignoreCase = true)
            }
            Result.success(
                CustomArtistDetail(
                    artist = artist.copy(songCount = topTracks.size, albumCount = albums.size),
                    topTracks = topTracks,
                    albums = albums
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun search(query: String): Result<CustomSearchPayload> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext Result.success(CustomSearchPayload())
        try {
            val primary = tryPrimary { XkMusicApiService.api().search(clean) }
            if (primary != null) {
                XkResponseParser.parseSearchPayload(primary)?.let { payload ->
                    return@withContext Result.success(
                        payload.copy(songs = payload.songs.map { enrichSongCommerce(it) })
                    )
                }
            }

            val q = clean.lowercase()
            val songs = getPublicSongs().getOrDefault(emptyList()).filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q) ||
                    it.genre.orEmpty().lowercase().contains(q)
            }
            val albums = getAlbums().getOrDefault(emptyList()).filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
            }
            val artists = getArtists().getOrDefault(emptyList()).filter {
                it.name.lowercase().contains(q)
            }
            val playlists = getPlaylists().getOrDefault(emptyList()).filter {
                it.name.lowercase().contains(q)
            }
            Result.success(CustomSearchPayload(songs = songs, albums = albums, artists = artists, playlists = playlists))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWeeklyPlayCounts(weekKey: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val raw = runCatching {
            val res = XuanKenFirebaseService.api().getWeeklyPlaysRaw()
            if (res.isSuccessful) res.body()?.string().orEmpty() else ""
        }.getOrNull().orEmpty()

        val parsed = if (XkResponseParser.isJsonBody(raw)) {
            XkResponseParser.parseWeeklyPlays(raw, targetWeekKey = weekKey)
        } else {
            emptyMap()
        }
        if (parsed.isNotEmpty()) {
            parsed
        } else {
            cachedSongEntries
                .filterValues { !it.isHidden && it.playCount > 0L }
                .mapValues { it.value.playCount }
        }
    }

    suspend fun getCharts(): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        try {
            val primary = tryPrimary { XkMusicApiService.api().getCharts() }
            if (primary != null) {
                val parsed = XkResponseParser.parseChartsSongs(primary)
                if (parsed.isNotEmpty()) return@withContext Result.success(parsed.map { enrichSongCommerce(it) })
            }
            if (cachedSongEntries.isEmpty()) {
                refreshRtdbCatalogLocked(forceRefresh = true)
            }
            val weeklyRaw = runCatching {
                val res = XuanKenFirebaseService.api().getWeeklyPlaysRaw()
                if (res.isSuccessful) res.body()?.string().orEmpty() else ""
            }.getOrNull().orEmpty()
            val weeklyCounts = if (XkResponseParser.isJsonBody(weeklyRaw)) {
                XkResponseParser.parseWeeklyPlays(weeklyRaw)
            } else {
                emptyMap()
            }
            val sorted = cachedSongEntries.values
                .filter { !it.isHidden }
                .sortedByDescending { entry ->
                    weeklyCounts[entry.song.providerId] ?: entry.playCount
                }
                .map { enrichSongCommerce(it.song, it) }
            Result.success(sorted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun recordSongPlay(songId: String) = withContext(Dispatchers.IO) {
        val cleanId = stripProviderPrefix(songId)
        val body = JSONObject().apply {
            put("songId", cleanId)
            put("timestamp", System.currentTimeMillis())
        }.toString().toRequestBody(jsonMediaType)
        runCatching { XkMusicApiService.api().recordSongPlay(cleanId, body) }
    }

    // -------------------------------------------------------------------------
    // Streaming Resolution (Matching xk-music.zip 4-step cascade)
    // -------------------------------------------------------------------------

    suspend fun getStreamingUrl(songId: String): String? = withContext(Dispatchers.IO) {
        if (!isConnected()) return@withContext null
        val cleanId = stripProviderPrefix(songId)
        val qualityPref = runCatching {
            AppSettings.getInstance(context).streamingQuality.value
        }.getOrDefault("HIGH")

        if (cachedSongEntries.isEmpty()) {
            runCatching { refreshRtdbCatalogLocked(forceRefresh = false) }
        }
        val entry = cachedSongEntries[cleanId]
        val publicFallback = entry?.song?.let { it.streamUrl ?: it.previewUrl }
        val isFree = entry != null && (!entry.isPaid || entry.price <= 0.0)

        if (hasUserSession() && cachedProfile == null) {
            runCatching { withTokenRefresh { token -> fetchAndMergeMemberState(token) } }
        }
        val isPurchased = cleanId in cachedPurchasedIds
        val preferFullMaster = isFree || isPurchased || isAdminUser

        // Apply selected 320kbps / WAV quality strictly to purchased / free master tracks
        val effectiveQuality = if (preferFullMaster) qualityPref else "LOW"
        val masterCacheKey = "$cleanId|${effectiveQuality.uppercase()}"

        // Instant in-memory cache hit for purchased/free master URLs -> zero-latency playback start
        if (preferFullMaster) {
            cachedMasterUrls[masterCacheKey]?.let { cached ->
                if (!XkResponseParser.looksLikeDemoUrl(cached)) return@withContext cached
            }
            // Guests (or already-high-quality direct URLs) can start free tracks immediately without a master lookup
            val publicIsHighQuality = !publicFallback.isNullOrBlank() &&
                (publicFallback.contains("320") || publicFallback.contains(".wav", ignoreCase = true) || publicFallback.contains(".flac", ignoreCase = true))
            if (isFree && (!hasUserSession() || publicIsHighQuality) &&
                !publicFallback.isNullOrBlank() && !XkResponseParser.looksLikeDemoUrl(publicFallback)
            ) {
                return@withContext publicFallback
            }
        } else if (!publicFallback.isNullOrBlank()) {
            // Unpurchased paid song: demo/preview URL is already cached -> play immediately
            return@withContext publicFallback
        }

        // 1. Try GET /api/v1/songs/{id}/stream
        val streamJson = tryPrimary { XkMusicApiService.api().getSongStream(cleanId) }
        val streamInfo = streamJson?.let { XkResponseParser.parseStreamInfo(it, effectiveQuality) }

        if (preferFullMaster) {
            if (streamInfo != null && !streamInfo.isDemo && !streamInfo.url.isNullOrBlank()) {
                cachedMasterUrls[masterCacheKey] = streamInfo.url
                return@withContext streamInfo.url
            }

            // 2. Try RTDB + Firestore `song_masters/{id}` using fresh Firebase token
            if (hasUserSession()) {
                val masterUrl = runCatching {
                    withTokenRefresh { token -> fetchMasterUrlFromFirebase(cleanId, token, effectiveQuality) }
                }.getOrNull()
                if (!masterUrl.isNullOrBlank() && !XkResponseParser.looksLikeDemoUrl(masterUrl)) {
                    cachedMasterUrls[masterCacheKey] = masterUrl
                    return@withContext masterUrl
                }
            }

            // 3. Try GET /api/v1/songs/{id}/download (full master)
            val downloadJson = tryPrimary { XkMusicApiService.api().getSongDownload(cleanId) }
            val dlUrl = downloadJson?.let { XkResponseParser.parseDownloadUrl(it) }
            if (!dlUrl.isNullOrBlank() && !XkResponseParser.looksLikeDemoUrl(dlUrl)) {
                cachedMasterUrls[masterCacheKey] = dlUrl
                return@withContext dlUrl
            }

            // 4. Fallback to any non-demo stream/public URL
            return@withContext streamInfo?.url ?: publicFallback
        }

        // Unpurchased paid song: stay on demo/preview stream, never unlock 320kbps/WAV master
        if (streamInfo != null && !streamInfo.url.isNullOrBlank()) {
            return@withContext streamInfo.url
        }
        return@withContext publicFallback
    }

    private suspend fun fetchMasterUrlFromFirebase(
        songId: String,
        idToken: String,
        qualityPref: String
    ): String? {
        // 1. RTDB song_masters/{songId}.json?auth={idToken}
        val rtdbBody = runCatching { XuanKenFirebaseService.api().getSongMaster(songId, idToken) }.getOrNull()
        val rtdbSelected = rtdbBody?.let { selectMasterStreamUrl(it, qualityPref) }
        if (!rtdbSelected.isNullOrBlank()) return rtdbSelected

        // 2. Firestore /song_masters/{songId}
        return XkFirestoreHelper.fetchSongMasterUrl(songId, idToken, qualityPref)
    }

    internal fun selectMasterStreamUrl(
        master: SongMasterDto,
        qualityPref: String = runCatching {
            AppSettings.getInstance(context).streamingQuality.value
        }.getOrDefault("HIGH")
    ): String? {
        val ordered = when (qualityPref.uppercase()) {
            "LOSSLESS" -> listOf(
                master.wavUrl, master.flacUrl, master.mp3Url, master.mp3_320Url,
                master.mp3320Url, master.bestUrl, master.streamUrl, master.url,
                master.mp3_128Url, master.demoUrl
            )
            "HIGH" -> listOf(
                master.mp3Url, master.mp3_320Url, master.mp3320Url, master.bestUrl,
                master.wavUrl, master.flacUrl, master.streamUrl, master.url,
                master.mp3_128Url, master.demoUrl
            )
            "MEDIUM" -> listOf(
                master.mp3Url, master.mp3_128Url, master.mp3_320Url,
                master.streamUrl, master.url, master.demoUrl, master.wavUrl
            )
            "LOW" -> listOf(master.mp3_128Url, master.demoUrl, master.mp3Url, master.streamUrl, master.url)
            else -> listOf(master.mp3Url, master.wavUrl, master.mp3_320Url, master.mp3_128Url, master.demoUrl)
        }
        return ordered.firstOrNull { !it.isNullOrBlank() && it != "null" }?.trim()
    }

    // -------------------------------------------------------------------------
    // Purchases & Vouchers (Primary /api/v1/songs/:id/purchase -> RTDB fallback)
    // -------------------------------------------------------------------------

    suspend fun getVouchers(): Result<List<VoucherInfo>> = withContext(Dispatchers.IO) {
        try {
            val primary = tryPrimary { XkMusicApiService.api().getVouchers() }
            if (primary != null) {
                return@withContext Result.success(XkResponseParser.parseVouchersList(primary))
            }
            val raw = XuanKenFirebaseService.api().getVouchers().orEmpty()
            val list = raw.entries.mapNotNull { (key, value) ->
                val map = asStringAnyMap(value) ?: return@mapNotNull null
                val code = firstNonBlank(map["code"]?.toString(), map["voucherCode"]?.toString(), key).uppercase()
                if (code.isBlank()) return@mapNotNull null
                VoucherInfo(
                    id = firstNonBlank(map["id"]?.toString(), key),
                    code = code,
                    title = firstNonBlank(map["title"]?.toString(), map["name"]?.toString(), code),
                    description = map["description"]?.toString().orEmpty(),
                    discountType = firstNonBlank(map["discountType"]?.toString(), map["type"]?.toString(), "percent"),
                    discountValue = (map["discountValue"] as? Number)?.toDouble()
                        ?: (map["value"] as? Number)?.toDouble() ?: 0.0,
                    minOrderAmount = (map["minOrderAmount"] as? Number)?.toDouble() ?: 0.0,
                    expiresAt = firstNonBlank(map["expiresAt"]?.toString(), map["validUntil"]?.toString()).takeIf { it.isNotBlank() },
                    isActive = (map["isActive"] as? Boolean) ?: (map["active"] as? Boolean) ?: true
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateVoucher(code: String, songId: String): Result<VoucherValidationResult> = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val cleanSongId = stripProviderPrefix(songId)
        val detail = getSongDetail(cleanSongId).getOrElse { return@withContext Result.failure(it) }
        val originalPrice = detail.price

        val primary = tryPrimary { XkMusicApiService.api().validateVoucher(cleanCode, cleanSongId) }
        if (primary != null) {
            XkResponseParser.parseVoucherValidation(primary, cleanCode, originalPrice, detail.currency)
                ?.let { return@withContext Result.success(it) }
        }

        val voucher = getVouchers().getOrDefault(emptyList())
            .firstOrNull { it.code.equals(cleanCode, ignoreCase = true) }
            ?: return@withContext Result.success(
                VoucherValidationResult(
                    valid = false,
                    code = cleanCode,
                    message = "Không tìm thấy mã giảm giá",
                    originalPrice = originalPrice,
                    discountAmount = 0.0,
                    finalPrice = originalPrice,
                    currency = detail.currency
                )
            )

        if (!voucher.isActive) {
            return@withContext Result.success(
                VoucherValidationResult(
                    valid = false,
                    code = voucher.code,
                    message = "Mã giảm giá đã hết hạn hoặc tạm ngưng",
                    originalPrice = originalPrice,
                    discountAmount = 0.0,
                    finalPrice = originalPrice,
                    currency = detail.currency
                )
            )
        }
        if (originalPrice < voucher.minOrderAmount) {
            return@withContext Result.success(
                VoucherValidationResult(
                    valid = false,
                    code = voucher.code,
                    message = "Đơn hàng chưa đạt mức tối thiểu (${voucher.minOrderAmount.toLong()})",
                    originalPrice = originalPrice,
                    discountAmount = 0.0,
                    finalPrice = originalPrice,
                    currency = detail.currency
                )
            )
        }

        val rawDiscount = when (voucher.discountType.lowercase()) {
            "fixed", "amount", "flat" -> voucher.discountValue
            else -> originalPrice * (voucher.discountValue / 100.0)
        }
        val discount = rawDiscount.coerceIn(0.0, originalPrice)
        val finalPrice = (originalPrice - discount).coerceAtLeast(0.0)

        Result.success(
            VoucherValidationResult(
                valid = true,
                code = voucher.code,
                message = "Đã áp dụng mã giảm giá",
                originalPrice = originalPrice,
                discountAmount = discount,
                finalPrice = finalPrice,
                currency = detail.currency
            )
        )
    }

    suspend fun purchaseSong(songId: String, voucherCode: String? = null): Result<PurchaseRecord> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(
                FirebaseProviderException(401, "Vui lòng đăng nhập tài khoản trước khi mua bài hát")
            )
        }
        val cleanId = stripProviderPrefix(songId)
        val cleanVoucher = voucherCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

        try {
            val detail = getSongDetail(cleanId).getOrElse { return@withContext Result.failure(it) }
            if (detail.isPurchased && detail.isPaidTrack && !isAdminUser) {
                return@withContext Result.failure(IllegalStateException("Bạn đã sở hữu bài hát này"))
            }

            // 1. Try POST /api/v1/songs/{id}/purchase
            if (XkMusicApiService.isApiKeyConfigured() || XkMusicApiService.baseUrlOverride != null) {
                val payload = JSONObject().apply {
                    put("songId", cleanId)
                    if (cleanVoucher != null) put("voucherCode", cleanVoucher)
                }.toString().toRequestBody(jsonMediaType)

                val primaryRes = runCatching { XkMusicApiService.api().purchaseSong(cleanId, payload) }.getOrNull()
                if (primaryRes != null) {
                    val body = primaryRes.body()?.string()?.trim().orEmpty()
                    val errBody = primaryRes.errorBody()?.string()?.trim().orEmpty()
                    if (primaryRes.isSuccessful && XkResponseParser.isJsonBody(body)) {
                        val songsLookup = cachedSongEntries.mapValues { it.value.song }
                        val record = XkResponseParser.parseSinglePurchase(body, cleanId, songsLookup)
                            ?: PurchaseRecord(
                                id = "pur_${System.currentTimeMillis()}",
                                songId = cleanId,
                                songTitle = detail.song.title,
                                artist = detail.song.artist,
                                artworkUrl = detail.song.artworkUrl,
                                amount = detail.price,
                                finalAmount = detail.price,
                                currency = detail.currency,
                                voucherCode = cleanVoucher,
                                status = "completed",
                                purchasedAt = Instant.now().toString()
                            )
                        cachedPurchasedIds.add(cleanId)
                        cachedMasterUrls.keys.removeAll { it == cleanId || it.startsWith("$cleanId|") }
                        runCatching { getStreamingUrl(cleanId) }
                        runCatching { withTokenRefresh { token -> fetchAndMergeMemberState(token) } }
                        return@withContext Result.success(record)
                    } else if (primaryRes.code() != 404 && XkResponseParser.isJsonBody(errBody)) {
                        val msg = XkResponseParser.parseErrorMessage(errBody)
                            ?: "Thanh toán thất bại (HTTP ${primaryRes.code()})"
                        return@withContext Result.failure(FirebaseProviderException(primaryRes.code(), msg))
                    }
                }
            }

            // 2. Fallback: Firebase RTDB atomic-style purchase flow
            val originalPrice = detail.price.coerceAtLeast(0.0)
            var discountAmount = 0.0
            var finalPrice = originalPrice
            var appliedCode: String? = null

            if (cleanVoucher != null) {
                val validation = validateVoucher(cleanVoucher, cleanId).getOrElse {
                    return@withContext Result.failure(it)
                }
                if (!validation.valid) {
                    return@withContext Result.failure(
                        IllegalArgumentException(validation.message.ifBlank { "Mã giảm giá không hợp lệ" })
                    )
                }
                discountAmount = validation.discountAmount
                finalPrice = validation.finalPrice
                appliedCode = validation.code
            }

            val member = withTokenRefresh { token ->
                XuanKenFirebaseService.api().getMember(uid, token)
            } ?: return@withContext Result.failure(
                FirebaseProviderException(404, "Không tìm thấy thông tin thành viên")
            )

            val oldBalance = member.balance ?: 0.0
            if (oldBalance < finalPrice) {
                return@withContext Result.failure(
                    IllegalStateException("Số dư không đủ (${oldBalance.toLong()} < ${finalPrice.toLong()} ${detail.currency})")
                )
            }

            val newBalance = (oldBalance - finalPrice).coerceAtLeast(0.0)
            val updatedPurchasedValue = updateIdCollection(member.purchasedSongIds, cleanId, add = true)
            val purchaseId = "pur_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            val isoTime = Instant.now().toString()

            withTokenRefresh { token ->
                val api = XuanKenFirebaseService.api()
                api.patchMember(
                    uid = uid,
                    idToken = token,
                    updates = mapOf(
                        "balance" to newBalance,
                        "purchasedSongIds" to updatedPurchasedValue
                    )
                )
                api.putUserPurchase(
                    uid = uid,
                    songId = cleanId,
                    idToken = token,
                    body = UserPurchaseReq(
                        songId = cleanId,
                        purchaseId = purchaseId,
                        price = finalPrice,
                        purchasedAt = isoTime,
                        voucherCode = appliedCode
                    )
                )
                api.putPurchase(
                    purchaseId = purchaseId,
                    idToken = token,
                    body = buildMap {
                        put("id", purchaseId)
                        put("purchaseId", purchaseId)
                        put("userId", uid)
                        put("songId", cleanId)
                        put("songTitle", detail.song.title)
                        put("artist", detail.song.artist)
                        put("amount", originalPrice)
                        put("discountAmount", discountAmount)
                        put("finalPrice", finalPrice)
                        put("price", finalPrice)
                        put("currency", detail.currency)
                        if (!appliedCode.isNullOrBlank()) put("voucherCode", appliedCode)
                        put("status", "completed")
                        put("purchasedAt", isoTime)
                    }
                )

                cachedPurchasedIds.add(cleanId)
                cachedMember = member.copy(balance = newBalance, purchasedSongIds = updatedPurchasedValue)
                cachedMasterUrls.keys.removeAll { it == cleanId || it.startsWith("$cleanId|") }
                val unlockQuality = runCatching {
                    AppSettings.getInstance(context).streamingQuality.value
                }.getOrDefault("HIGH")
                val unlocked = fetchMasterUrlFromFirebase(cleanId, token, unlockQuality)
                if (!unlocked.isNullOrBlank()) {
                    cachedMasterUrls["$cleanId|${unlockQuality.uppercase()}"] = unlocked
                }
            }

            Result.success(
                PurchaseRecord(
                    id = purchaseId,
                    songId = cleanId,
                    songTitle = detail.song.title,
                    artist = detail.song.artist,
                    artworkUrl = detail.song.artworkUrl,
                    amount = originalPrice,
                    discountAmount = discountAmount,
                    finalAmount = finalPrice,
                    currency = detail.currency,
                    voucherCode = appliedCode,
                    status = "completed",
                    purchasedAt = isoTime
                )
            )
        } catch (e: HttpException) {
            Result.failure(FirebaseProviderException(e.code(), "Purchase failed (HTTP ${e.code()}): ${e.message()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPurchases(): Result<List<PurchaseRecord>> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) return@withContext Result.success(emptyList())
        try {
            val songsLookup = cachedSongEntries.mapValues { it.value.song }
            val primary = tryPrimary { XkMusicApiService.api().getPurchases() }
            if (primary != null) {
                return@withContext Result.success(XkResponseParser.parsePurchasesList(primary, songsLookup))
            }

            val map = withTokenRefresh { token ->
                XuanKenFirebaseService.api().getUserPurchases(uid, token)
            }.orEmpty()

            if (cachedSongEntries.isEmpty()) {
                runCatching { refreshRtdbCatalogLocked(forceRefresh = false) }
            }
            val currentLookup = cachedSongEntries.mapValues { it.value.song }
            val list = map.entries.map { (songKey, recordMap) ->
                val sId = firstNonBlank(recordMap["songId"]?.toString(), songKey)
                val songDto = currentLookup[sId]
                val price = (recordMap["finalAmount"] as? Number)?.toDouble()
                    ?: (recordMap["price"] as? Number)?.toDouble() ?: 0.0
                PurchaseRecord(
                    id = firstNonBlank(recordMap["purchaseId"]?.toString(), recordMap["id"]?.toString(), songKey),
                    songId = sId,
                    songTitle = firstNonBlank(recordMap["songTitle"]?.toString(), songDto?.title, sId),
                    artist = firstNonBlank(recordMap["artist"]?.toString(), songDto?.artist, "XuanKen Music"),
                    artworkUrl = songDto?.artworkUrl,
                    amount = (recordMap["amount"] as? Number)?.toDouble() ?: price,
                    discountAmount = (recordMap["discountAmount"] as? Number)?.toDouble() ?: 0.0,
                    finalAmount = price,
                    currency = firstNonBlank(recordMap["currency"]?.toString(), "VND"),
                    voucherCode = recordMap["voucherCode"]?.toString()?.takeIf { it.isNotBlank() },
                    status = firstNonBlank(recordMap["status"]?.toString(), "completed"),
                    purchasedAt = recordMap["purchasedAt"]?.toString()
                )
            }.sortedByDescending { it.purchasedAt.orEmpty() }
            Result.success(list)
        } catch (e: HttpException) {
            if (e.code() == 404) Result.success(emptyList()) else Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // Site Info, Profile (/api/v1/me + RTDB + Firestore + accounts:lookup), Settings
    // -------------------------------------------------------------------------

    suspend fun getSiteInfo(): CustomSiteInfo {
        val primary = tryPrimary { XkMusicApiService.api().getSiteInfo() }
        if (primary != null) {
            XkResponseParser.parseSiteInfo(primary)?.let {
                cachedSiteInfo = it
                return it
            }
        }
        val rtdbRaw = runCatching { XuanKenFirebaseService.api().getSiteSettingsRaw() }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.string()?.trim().orEmpty()
        if (XkResponseParser.isJsonBody(rtdbRaw)) {
            XkResponseParser.parseSiteInfo(rtdbRaw)?.let { cachedSiteInfo = it }
        }
        return cachedSiteInfo
    }

    suspend fun getProfile(): Result<CustomUserProfile> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.success(
                CustomUserProfile(displayName = "Khách", username = "Khách", role = "guest")
            )
        }
        try {
            val profile = withTokenRefresh { token -> fetchAndMergeMemberState(token) }
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchAndMergeMemberState(token: String): CustomUserProfile = coroutineScope {
        XkMusicApiService.setUserToken(token)

        val apiMeDeferred = async {
            if (XkMusicApiService.isApiKeyConfigured() || XkMusicApiService.baseUrlOverride != null) {
                tryPrimary { XkMusicApiService.api().getMe() }?.let { raw ->
                    XkResponseParser.parseMemberState(raw, fallbackUid = uid, fallbackEmail = email, fallbackDisplayName = displayName)
                }
            } else {
                null
            }
        }
        val rtdbMemberDeferred = async {
            runCatching { XuanKenFirebaseService.api().getMember(uid, token) }.getOrNull()
        }
        val fsMemberDeferred = async {
            XkFirestoreHelper.fetchMemberProfile(uid, token, email, displayName)
        }
        val adminDeferred = async {
            runCatching { XuanKenFirebaseService.api().getAdminRecord(uid, token) }.getOrNull()
                ?.takeIf { it.isSuccessful }?.body()?.string()?.trim().orEmpty()
        }
        val rtdbPurchasesDeferred = async {
            runCatching { XuanKenFirebaseService.api().getUserPurchases(uid, token) }.getOrNull()
        }
        val fsPurchasesDeferred = async {
            XkFirestoreHelper.fetchUserPurchases(uid, token)
        }
        val likesDeferred = async {
            runCatching { XuanKenFirebaseService.api().getUserLikedSongs(uid, token) }.getOrNull()
                ?.takeIf { it.isSuccessful }?.body()?.string()?.trim().orEmpty()
        }

        val apiState = apiMeDeferred.await()
        val rtdbMember = rtdbMemberDeferred.await()
        val fsMember = fsMemberDeferred.await()
        val adminBody = adminDeferred.await()
        val rtdbPurchases = rtdbPurchasesDeferred.await().orEmpty()
        val fsPurchaseIds = fsPurchasesDeferred.await()
        val likesBody = likesDeferred.await()

        rtdbMember?.let { cachedMember = it }
        val rtdbStats = parseCloudListeningStatsFromAny(rtdbMember?.listeningStats)
        val fsStats = fsMember?.listeningStatsJson?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { parseCloudListeningStatsFromJsonObject(it) }
        }
        val mergedCloudStats = pickRicherCloudStats(rtdbStats, fsStats)
        if (mergedCloudStats != null) {
            cachedCloudListeningStats = pickRicherCloudStats(cachedCloudListeningStats, mergedCloudStats)
        }

        var baseProfile = apiState?.profile ?: rtdbMember?.let {
            CustomUserProfile(
                id = uid,
                username = firstNonBlank(it.username, it.displayName, it.name, displayName, email),
                email = firstNonBlank(it.email, email),
                displayName = firstNonBlank(it.displayName, it.name, it.username, displayName, email, uid),
                avatarUrl = firstNonBlank(it.avatarUrl, it.avatar).takeIf { url -> url.isNotBlank() },
                role = firstNonBlank(it.role, "user"),
                plan = firstNonBlank(it.plan, it.tier),
                balance = it.balance,
                currency = firstNonBlank(it.currency, "VND")
            )
        } ?: fsMember?.let {
            CustomUserProfile(
                id = uid,
                username = it.displayName,
                email = it.email,
                displayName = it.displayName,
                avatarUrl = it.avatarUrl,
                role = it.role ?: "user",
                balance = it.balance
            )
        }

        if (baseProfile == null && (FirebaseAuthService.isApiKeyConfigured() || FirebaseAuthService.authBaseUrlOverride != null)) {
            val lookupBody = JSONObject().apply { put("idToken", token) }.toString().toRequestBody(jsonMediaType)
            val lookupRaw = runCatching {
                FirebaseAuthService.authApi().lookupAccount(FirebaseAuthService.getApiKey(), lookupBody)
            }.getOrNull()?.takeIf { it.isSuccessful }?.body()?.string()?.trim().orEmpty()
            if (XkResponseParser.isJsonBody(lookupRaw)) {
                baseProfile = XkResponseParser.parseMemberState(lookupRaw, uid, email, displayName)?.profile
            }
        }

        val trimmedAdmin = adminBody.trim()
        val rtdbAdminFlag = when {
            trimmedAdmin.equals("true", ignoreCase = true) -> true
            trimmedAdmin.startsWith("\"") -> trimmedAdmin.trim('"').equals("true", ignoreCase = true)
            trimmedAdmin.startsWith("{") -> {
                val obj = runCatching { JSONObject(trimmedAdmin) }.getOrNull()
                obj != null && (
                    obj.optBoolean("isAdmin", false) ||
                        obj.optString("role").equals("admin", ignoreCase = true) ||
                        (obj.length() > 0 && !obj.has("isAdmin") && !obj.has("role"))
                    )
            }
            else -> false
        }
        val admin = rtdbAdminFlag ||
            baseProfile?.role.equals("admin", ignoreCase = true) ||
            rtdbMember?.role.equals("admin", ignoreCase = true) ||
            fsMember?.role.equals("admin", ignoreCase = true)
        isAdminUser = admin

        val mergedPurchased = (
            apiState?.purchasedSongIds.orEmpty() +
                extractIdSet(rtdbMember?.purchasedSongIds) +
                fsMember?.purchasedSongIds.orEmpty() +
                rtdbPurchases.keys +
                fsPurchaseIds
            ).filter { it.isNotBlank() }.toMutableSet()

        val rtdbLikedNode = if (XkResponseParser.isJsonBody(likesBody)) {
            XkResponseParser.extractLikedSongIdsFromNode(likesBody)
        } else {
            emptyList()
        }
        val mergedLikedSongs = (
            apiState?.likedSongIds.orEmpty() +
                extractIdSet(rtdbMember?.likedSongIds) +
                fsMember?.likedSongIds.orEmpty() +
                rtdbLikedNode
            ).filter { it.isNotBlank() }.toMutableSet()

        val mergedLikedAlbums = (
            apiState?.likedAlbumIds.orEmpty() +
                extractIdSet(rtdbMember?.likedAlbumIds) +
                fsMember?.likedAlbumIds.orEmpty()
            ).filter { it.isNotBlank() }.toMutableSet()

        val mergedBlockedSongs = (
            apiState?.blockedSongIds.orEmpty() +
                extractIdSet(rtdbMember?.blockedSongIds) +
                fsMember?.blockedSongIds.orEmpty()
            ).filter { it.isNotBlank() }.toMutableSet()

        cachedPurchasedIds = mergedPurchased
        cachedLikedSongIds = mergedLikedSongs
        cachedLikedAlbumIds = mergedLikedAlbums
        cachedBlockedSongIds = mergedBlockedSongs

        val resolvedProfile = (baseProfile ?: CustomUserProfile(
            id = uid,
            username = displayName.ifBlank { email },
            email = email,
            displayName = displayName.ifBlank { email }.ifBlank { uid }
        )).let {
            it.copy(
                role = if (admin) "admin" else it.role,
                balance = it.balance ?: rtdbMember?.balance ?: fsMember?.balance
            )
        }

        if (resolvedProfile.displayName.isNotBlank()) {
            displayName = resolvedProfile.displayName
            prefs.edit {
                putString(KEY_DISPLAY_NAME, resolvedProfile.displayName)
                putBoolean(KEY_IS_ADMIN, admin)
            }
        }
        cachedProfile = resolvedProfile
        resolvedProfile
    }

    suspend fun updateProfile(newDisplayName: String, newEmail: String? = null): Result<CustomUserProfile> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(FirebaseProviderException(401, "Sign in to update profile"))
        }
        try {
            val cleanName = newDisplayName.trim()
            val payload = JSONObject().apply {
                put("displayName", cleanName)
                put("display_name", cleanName)
                if (!newEmail.isNullOrBlank()) put("email", newEmail.trim())
            }.toString().toRequestBody(jsonMediaType)

            val primaryRes = runCatching { XkMusicApiService.api().updateMe(payload) }.getOrNull()
            if (primaryRes != null && primaryRes.isSuccessful) {
                displayName = cleanName
                prefs.edit { putString(KEY_DISPLAY_NAME, cleanName) }
                return@withContext getProfile()
            }

            val updates = mutableMapOf<String, Any?>(
                "displayName" to cleanName,
                "name" to cleanName
            )
            if (!newEmail.isNullOrBlank()) updates["email"] = newEmail.trim()

            withTokenRefresh { token ->
                XuanKenFirebaseService.api().patchMember(uid, token, updates)
            }
            displayName = cleanName
            prefs.edit { putString(KEY_DISPLAY_NAME, cleanName) }
            getProfile()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserSettings(): Result<CustomUserSettings> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) return@withContext Result.success(CustomUserSettings())
        try {
            val primary = tryPrimary { XkMusicApiService.api().getMeSettings() }
            if (primary != null) {
                XkResponseParser.parseUserSettings(primary)?.let { return@withContext Result.success(it) }
            }
            val member = cachedMember ?: withTokenRefresh { token ->
                XuanKenFirebaseService.api().getMember(uid, token)
            }?.also { cachedMember = it }
            val s = member?.settings.orEmpty()
            Result.success(
                CustomUserSettings(
                    audioQuality = firstNonBlank(s["audioQuality"]?.toString(), "HIGH"),
                    pushNewMusic = (s["pushNewMusic"] as? Boolean) ?: true,
                    privateSession = (s["privateSession"] as? Boolean) ?: false,
                    explicitFilter = (s["explicitFilter"] as? Boolean) ?: false,
                    language = firstNonBlank(s["language"]?.toString(), "vi")
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateUserSettings(settings: CustomUserSettings): Result<CustomUserSettings> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(FirebaseProviderException(401, "Sign in to sync cloud settings"))
        }
        try {
            val payload = JSONObject().apply {
                put("audioQuality", settings.audioQuality)
                put("pushNewMusic", settings.pushNewMusic)
                put("privateSession", settings.privateSession)
                put("explicitFilter", settings.explicitFilter)
                put("language", settings.language)
            }.toString().toRequestBody(jsonMediaType)

            val primaryRes = runCatching { XkMusicApiService.api().updateMeSettings(payload) }.getOrNull()
            if (primaryRes != null && primaryRes.isSuccessful) {
                return@withContext Result.success(settings)
            }

            withTokenRefresh { token ->
                XuanKenFirebaseService.api().patchMember(
                    uid,
                    token,
                    mapOf(
                        "settings" to mapOf(
                            "audioQuality" to settings.audioQuality,
                            "pushNewMusic" to settings.pushNewMusic,
                            "privateSession" to settings.privateSession,
                            "explicitFilter" to settings.explicitFilter,
                            "language" to settings.language
                        )
                    )
                )
            }
            Result.success(settings)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // Account-Scoped Listening Statistics (RTDB + Firestore)
    // -------------------------------------------------------------------------

    fun getStatsAccountKey(): String {
        val cleanUid = uid.trim()
        return if (hasUserSession() && cleanUid.isNotBlank()) "user_$cleanUid" else "guest"
    }

    suspend fun fetchListeningStats(forceRefresh: Boolean = false): CloudListeningStats? = withContext(Dispatchers.IO) {
        if (!hasUserSession() || uid.isBlank()) return@withContext null
        if (!forceRefresh && cachedCloudListeningStats != null) {
            return@withContext cachedCloudListeningStats
        }
        runCatching {
            withTokenRefresh { token ->
                coroutineScope {
                    val rtdbDeferred = async {
                        runCatching { XuanKenFirebaseService.api().getMember(uid, token) }.getOrNull()
                    }
                    val fsDeferred = async {
                        runCatching {
                            XkFirestoreHelper.fetchMemberProfile(uid, token, email, displayName)
                        }.getOrNull()
                    }
                    val rtdbMember = rtdbDeferred.await()
                    val fsMember = fsDeferred.await()
                    rtdbMember?.let { cachedMember = it }

                    val rtdbStats = parseCloudListeningStatsFromAny(rtdbMember?.listeningStats)
                    val fsStats = fsMember?.listeningStatsJson?.let { raw ->
                        runCatching { JSONObject(raw) }.getOrNull()?.let { parseCloudListeningStatsFromJsonObject(it) }
                    }
                    val best = pickRicherCloudStats(
                        cachedCloudListeningStats,
                        pickRicherCloudStats(rtdbStats, fsStats)
                    )
                    if (best != null) {
                        cachedCloudListeningStats = best
                    }
                    best
                }
            }
        }.getOrNull()
    }

    suspend fun syncListeningStats(stats: CloudListeningStats): Boolean = withContext(Dispatchers.IO) {
        if (!hasUserSession() || uid.isBlank()) return@withContext false
        val statsMap = toCloudListeningStatsMap(stats)
        val statsJson = toCloudListeningStatsJson(stats).toString()
        runCatching {
            withTokenRefresh { token ->
                val rtdbOk = runCatching {
                    XuanKenFirebaseService.api().patchMember(
                        uid = uid,
                        idToken = token,
                        updates = mapOf("listeningStats" to statsMap)
                    )
                    true
                }.getOrDefault(false)
                val fsOk = runCatching {
                    XkFirestoreHelper.patchMemberListeningStats(uid, token, statsJson)
                }.getOrDefault(false)
                if (rtdbOk || fsOk) {
                    cachedCloudListeningStats = stats
                }
                rtdbOk || fsOk
            }
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------------------
    // Playlists (Primary /api/v1/playlists -> RTDB public + my + Firestore)
    // -------------------------------------------------------------------------

    suspend fun getPlaylists(): Result<List<ProviderPlaylist>> = withContext(Dispatchers.IO) {
        // Strictly private personal playlists per signed-in member: guests see no personal playlists.
        if (!hasUserSession()) {
            return@withContext Result.success(emptyList())
        }
        try {
            val songsLookup = cachedSongEntries.mapValues { it.value.song }
            val primary = tryPrimary { XkMusicApiService.api().getPlaylists() }
            if (primary != null) {
                val parsed = XkResponseParser.parsePlaylistsEntries(primary, songsLookup)
                // Only keep playlists created by this specific member
                val ownOnly = parsed.filter { entry ->
                    entry.userId == uid ||
                        (entry.userId.isNullOrBlank() &&
                            (entry.playlist.owner.equals(displayName, ignoreCase = true) ||
                                entry.playlist.owner.equals(email, ignoreCase = true)))
                }
                if (ownOnly.isNotEmpty()) {
                    cachedPlaylistsMap = ownOnly.associateBy { it.playlist.providerId }
                    return@withContext Result.success(ownOnly.map { it.playlist })
                }
            }

            val merged = linkedMapOf<String, ParsedPlaylistEntry>()
            // 1. RTDB strictly user-owned playlists (with quoted orderBy/equalTo)
            val myMap = runCatching {
                withTokenRefresh { token ->
                    XuanKenFirebaseService.api().getMyPlaylists(
                        orderBy = "\"userId\"",
                        equalTo = "\"$uid\"",
                        idToken = token
                    )
                }
            }.getOrNull().orEmpty()
            myMap.entries.forEach { (key, dto) ->
                if (!dto.userId.isNullOrBlank() && dto.userId != uid) return@forEach
                val pId = firstNonBlank(dto.id, key)
                val ids = extractIdSet(dto.songIds ?: dto.songs).toList()
                val pl = ProviderPlaylist(
                    providerId = pId,
                    name = firstNonBlank(dto.name, dto.title, "Playlist"),
                    description = dto.description,
                    artworkUrl = firstNonBlank(dto.coverUrl, dto.artworkUrl).takeIf { it.isNotBlank() },
                    songCount = ids.size,
                    owner = firstNonBlank(dto.owner, displayName, email),
                    isPublic = dto.isPublic ?: false
                )
                merged[pId] = ParsedPlaylistEntry(pl, uid, ids, emptyList())
            }

            // 2. Firestore playlists fallback strictly filtered to userId == uid (never admin-all)
            val fsPlaylists = runCatching {
                withTokenRefresh { token ->
                    XkFirestoreHelper.fetchUserPlaylists(uid, token, isAdmin = false, songsLookup)
                }
            }.getOrDefault(emptyList())
            fsPlaylists.forEach { (pl, ids) ->
                merged.putIfAbsent(
                    pl.providerId,
                    ParsedPlaylistEntry(pl, uid, ids, ids.mapNotNull { songsLookup[it] })
                )
            }

            // Never merge other members' or public playlists into this user's personal playlist collection.
            cachedPlaylistsMap = merged
            Result.success(merged.values.map { it.playlist })
        } catch (e: HttpException) {
            if (e.code() == 404) Result.success(emptyList()) else Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaylistDetail(playlistId: String): Result<CustomPlaylistDetail> = withContext(Dispatchers.IO) {
        val cleanId = stripProviderPrefix(playlistId)
        try {
            val songsLookup = cachedSongEntries.mapValues { it.value.song }
            val primary = tryPrimary { XkMusicApiService.api().getPlaylistById(cleanId) }
            if (primary != null) {
                val parsed = XkResponseParser.parsePlaylistDetail(primary, songsLookup)
                if (parsed != null) {
                    val songs = parsed.embeddedSongs.ifEmpty {
                        val allSongs = getPublicSongs().getOrDefault(emptyList())
                        parsed.songIds.mapNotNull { sid -> allSongs.firstOrNull { it.providerId == sid } }
                    }.map { enrichSongCommerce(it) }
                    return@withContext Result.success(CustomPlaylistDetail(parsed.playlist, songs))
                }
            }

            if (cleanId !in cachedPlaylistsMap) {
                getPlaylists()
            }
            val entry = cachedPlaylistsMap[cleanId]
                ?: return@withContext Result.failure(FirebaseProviderException(404, "Không tìm thấy playlist ($cleanId)"))

            val allSongs = getPublicSongs().getOrDefault(emptyList())
            val songs = entry.embeddedSongs.ifEmpty {
                entry.songIds.mapNotNull { sid -> allSongs.firstOrNull { it.providerId == sid } }
            }.map { enrichSongCommerce(it) }
            Result.success(
                CustomPlaylistDetail(
                    playlist = entry.playlist.copy(songCount = maxOf(entry.playlist.songCount, songs.size)),
                    songs = songs
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPlaylist(name: String, description: String? = null, isPublic: Boolean = false): Result<ProviderPlaylist> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(FirebaseProviderException(401, "Sign in to create playlists"))
        }
        val cleanName = name.trim()
        try {
            val primaryBody = JSONObject().apply {
                put("title", cleanName)
                put("name", cleanName)
                put("description", description.orEmpty().trim())
                put("isPublic", isPublic)
            }.toString().toRequestBody(jsonMediaType)

            val primary = tryPrimary { XkMusicApiService.api().createPlaylist(primaryBody) }
            if (primary != null) {
                val parsed = XkResponseParser.parsePlaylistDetail(primary)?.playlist
                    ?: ProviderPlaylist(
                        providerId = "pl_${System.currentTimeMillis()}",
                        name = cleanName,
                        description = description,
                        owner = getUsername(),
                        isPublic = isPublic,
                        songCount = 0
                    )
                return@withContext Result.success(parsed)
            }

            val playlistId = "pl_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val body = buildMap<String, Any?> {
                put("id", playlistId)
                put("userId", uid)
                put("owner", displayName.ifBlank { email })
                put("name", cleanName)
                put("title", cleanName)
                if (!description.isNullOrBlank()) put("description", description.trim())
                put("isPublic", isPublic)
                put("songIds", emptyList<String>())
                put("createdAt", Instant.now().toString())
            }
            withTokenRefresh { token ->
                XuanKenFirebaseService.api().putPlaylist(playlistId, token, body)
            }
            val created = ProviderPlaylist(
                providerId = playlistId,
                name = cleanName,
                description = description,
                owner = displayName.ifBlank { email },
                isPublic = isPublic,
                songCount = 0
            )
            cachedPlaylistsMap = cachedPlaylistsMap + (playlistId to ParsedPlaylistEntry(created, uid, emptyList(), emptyList()))
            Result.success(created)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePlaylist(
        playlistId: String,
        name: String? = null,
        addSongIds: List<String>? = null,
        removeSongIds: List<String>? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(FirebaseProviderException(401, "Sign in to update playlists"))
        }
        val cleanId = stripProviderPrefix(playlistId)
        try {
            val detail = runCatching { getPlaylistDetail(cleanId).getOrNull() }.getOrNull()
            val currentIds = detail?.songs?.map { it.providerId }?.toMutableList()
                ?: cachedPlaylistsMap[cleanId]?.songIds?.toMutableList()
                ?: mutableListOf()
            addSongIds?.map(::stripProviderPrefix)?.forEach { id ->
                if (id !in currentIds) currentIds.add(id)
            }
            removeSongIds?.map(::stripProviderPrefix)?.toSet()?.let { toRemove ->
                currentIds.removeAll(toRemove)
            }

            val primaryBody = JSONObject().apply {
                if (!name.isNullOrBlank()) {
                    put("name", name.trim())
                    put("title", name.trim())
                }
                put("songIds", JSONArray(currentIds))
            }.toString().toRequestBody(jsonMediaType)
            val primary = tryPrimary { XkMusicApiService.api().updatePlaylist(cleanId, primaryBody) }
            if (primary != null) {
                return@withContext Result.success(true)
            }

            val patchBody = mutableMapOf<String, Any?>(
                "updatedAt" to Instant.now().toString()
            )
            if (!name.isNullOrBlank()) {
                patchBody["name"] = name.trim()
                patchBody["title"] = name.trim()
            }
            if (addSongIds != null || removeSongIds != null) patchBody["songIds"] = currentIds

            withTokenRefresh { token ->
                XuanKenFirebaseService.api().patchPlaylist(cleanId, token, patchBody)
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(FirebaseProviderException(401, "Sign in to delete playlists"))
        }
        val cleanId = stripProviderPrefix(playlistId)
        try {
            val primaryRes = runCatching { XkMusicApiService.api().deletePlaylist(cleanId) }.getOrNull()
            if (primaryRes != null && primaryRes.isSuccessful && primaryRes.code() != 404) {
                cachedPlaylistsMap = cachedPlaylistsMap - cleanId
                return@withContext Result.success(true)
            }
            val resp = withTokenRefresh { token ->
                XuanKenFirebaseService.api().deletePlaylist(cleanId, token)
            }
            if (resp.isSuccessful) {
                cachedPlaylistsMap = cachedPlaylistsMap - cleanId
                Result.success(true)
            } else {
                Result.failure(FirebaseProviderException(resp.code(), "Failed to delete playlist (${resp.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleSongLike(songId: String, liked: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) return@withContext Result.success(true)
        val cleanId = stripProviderPrefix(songId)
        try {
            val body = JSONObject().apply {
                put("songId", cleanId)
                put("liked", liked)
            }.toString().toRequestBody(jsonMediaType)
            val primary = runCatching { XkMusicApiService.api().toggleLike(body) }.getOrNull()
            if (primary != null && primary.isSuccessful) {
                if (liked) cachedLikedSongIds.add(cleanId) else cachedLikedSongIds.remove(cleanId)
                return@withContext Result.success(true)
            }

            val member = cachedMember ?: withTokenRefresh { token ->
                XuanKenFirebaseService.api().getMember(uid, token)
            }
            val updated = updateIdCollection(member?.likedSongIds ?: cachedLikedSongIds.toList(), cleanId, add = liked)
            val listForLikedNode = if (liked) {
                (cachedLikedSongIds + cleanId).toList()
            } else {
                (cachedLikedSongIds - cleanId).toList()
            }
            val likedNodeBody = JSONObject().apply {
                put("likedSongIds", JSONArray(listForLikedNode))
            }.toString().toRequestBody(jsonMediaType)

            withTokenRefresh { token ->
                XuanKenFirebaseService.api().patchMember(uid, token, mapOf("likedSongIds" to updated))
                runCatching { XuanKenFirebaseService.api().putUserLikedSongs(uid, token, likedNodeBody) }
            }
            if (liked) cachedLikedSongIds.add(cleanId) else cachedLikedSongIds.remove(cleanId)
            cachedMember = member?.copy(likedSongIds = updated)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleAlbumLike(albumId: String, liked: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) return@withContext Result.success(true)
        val cleanId = stripProviderPrefix(albumId)
        try {
            val body = JSONObject().apply {
                put("albumId", cleanId)
                put("liked", liked)
            }.toString().toRequestBody(jsonMediaType)
            val primary = runCatching { XkMusicApiService.api().toggleAlbumLike(body) }.getOrNull()
            if (primary != null && primary.isSuccessful) {
                if (liked) cachedLikedAlbumIds.add(cleanId) else cachedLikedAlbumIds.remove(cleanId)
                return@withContext Result.success(true)
            }

            val member = cachedMember ?: withTokenRefresh { token ->
                XuanKenFirebaseService.api().getMember(uid, token)
            }
            val updated = updateIdCollection(member?.likedAlbumIds ?: cachedLikedAlbumIds.toList(), cleanId, add = liked)
            withTokenRefresh { token ->
                XuanKenFirebaseService.api().patchMember(uid, token, mapOf("likedAlbumIds" to updated))
            }
            if (liked) cachedLikedAlbumIds.add(cleanId) else cachedLikedAlbumIds.remove(cleanId)
            cachedMember = member?.copy(likedAlbumIds = updated)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBlockedSongs(): Result<List<BlockedSongItem>> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) return@withContext Result.success(emptyList())
        try {
            val songsLookup = cachedSongEntries.mapValues { it.value.song }
            val primary = tryPrimary { XkMusicApiService.api().getBlockedSongs() }
            if (primary != null) {
                val items = XkResponseParser.parseBlockedSongs(primary, songsLookup)
                cachedBlockedSongIds = items.map { it.songId }.toMutableSet()
                return@withContext Result.success(items)
            }

            val member = withTokenRefresh { token ->
                XuanKenFirebaseService.api().getMember(uid, token)
            }?.also { cachedMember = it }
            val blockedIds = extractIdSet(member?.blockedSongIds)
            cachedBlockedSongIds = blockedIds.toMutableSet()
            if (blockedIds.isEmpty()) return@withContext Result.success(emptyList())
            if (cachedSongEntries.isEmpty()) {
                refreshRtdbCatalogLocked(forceRefresh = false)
            }
            val items = blockedIds.map { sId ->
                val song = cachedSongEntries[sId]?.song
                BlockedSongItem(
                    songId = sId,
                    title = song?.title ?: "Song $sId",
                    artist = song?.artist.orEmpty(),
                    album = song?.album.orEmpty(),
                    artworkUrl = song?.artworkUrl
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun blockSong(songId: String, reason: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(FirebaseProviderException(401, "Sign in to block songs"))
        }
        val cleanId = stripProviderPrefix(songId)
        try {
            val body = JSONObject().apply {
                put("songId", cleanId)
                if (!reason.isNullOrBlank()) put("reason", reason.trim())
            }.toString().toRequestBody(jsonMediaType)
            val primary = runCatching { XkMusicApiService.api().blockSong(body) }.getOrNull()
            if (primary != null && primary.isSuccessful) {
                cachedBlockedSongIds.add(cleanId)
                return@withContext Result.success(true)
            }

            val member = cachedMember ?: withTokenRefresh { token ->
                XuanKenFirebaseService.api().getMember(uid, token)
            }
            val updated = updateIdCollection(member?.blockedSongIds, cleanId, add = true)
            withTokenRefresh { token ->
                XuanKenFirebaseService.api().patchMember(uid, token, mapOf("blockedSongIds" to updated))
            }
            cachedBlockedSongIds.add(cleanId)
            cachedMember = member?.copy(blockedSongIds = updated)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unblockSong(songId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(FirebaseProviderException(401, "Sign in to unblock songs"))
        }
        val cleanId = stripProviderPrefix(songId)
        try {
            val primary = runCatching { XkMusicApiService.api().unblockSong(cleanId) }.getOrNull()
            if (primary != null && primary.isSuccessful) {
                cachedBlockedSongIds.remove(cleanId)
                return@withContext Result.success(true)
            }

            val member = cachedMember ?: withTokenRefresh { token ->
                XuanKenFirebaseService.api().getMember(uid, token)
            }
            val updated = updateIdCollection(member?.blockedSongIds, cleanId, add = false)
            withTokenRefresh { token ->
                XuanKenFirebaseService.api().patchMember(uid, token, mapOf("blockedSongIds" to updated))
            }
            cachedBlockedSongIds.remove(cleanId)
            cachedMember = member?.copy(blockedSongIds = updated)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reportSong(songId: String, reason: String, details: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasUserSession()) {
            return@withContext Result.failure(FirebaseProviderException(401, "Sign in to submit song reports"))
        }
        val cleanId = stripProviderPrefix(songId)
        try {
            val body = JSONObject().apply {
                put("songId", cleanId)
                put("reason", reason.trim())
                put("detail", details.orEmpty().trim())
                put("message", details.orEmpty().trim())
            }.toString().toRequestBody(jsonMediaType)
            val primary = runCatching { XkMusicApiService.api().reportSong(cleanId, body) }.getOrNull()
            if (primary != null && primary.isSuccessful) {
                return@withContext Result.success(true)
            }

            val reportId = "rep_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val resp = withTokenRefresh { token ->
                XuanKenFirebaseService.api().putReport(
                    reportId = reportId,
                    idToken = token,
                    body = buildMap {
                        put("id", reportId)
                        put("userId", uid)
                        put("songId", cleanId)
                        put("reason", reason.trim())
                        if (!details.isNullOrBlank()) put("details", details.trim())
                        put("createdAt", Instant.now().toString())
                    }
                )
            }
            if (resp.isSuccessful) Result.success(true)
            else Result.failure(FirebaseProviderException(resp.code(), "Report failed (${resp.code()})"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // RTDB Snapshot Loader (Standalone songs + exclusive_albums tracks + bios)
    // -------------------------------------------------------------------------

    private suspend fun tryPrimary(call: suspend () -> Response<ResponseBody>): String? {
        if (!XkMusicApiService.isApiKeyConfigured() && XkMusicApiService.baseUrlOverride == null) {
            return null
        }
        return try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()?.string()?.trim().orEmpty()
                if (XkResponseParser.isJsonBody(body)) body else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun refreshRtdbCatalogLocked(forceRefresh: Boolean) {
        catalogMutex.withLock {
            if (!forceRefresh && cachedSongEntries.isNotEmpty()) return
            coroutineScope {
                val api = XuanKenFirebaseService.api()
                val songsDef = async { runCatching { api.getSongsRaw() }.getOrNull() }
                val albumsDef = async { runCatching { api.getExclusiveAlbumsRaw() }.getOrNull() }
                val playlistsDef = async { runCatching { api.getPlaylistsRaw() }.getOrNull() }
                val biosDef = async { runCatching { api.getArtistBios() }.getOrNull() }

                val songsRaw = songsDef.await()?.takeIf { it.isSuccessful }?.body()?.string().orEmpty()
                val albumsRaw = albumsDef.await()?.takeIf { it.isSuccessful }?.body()?.string().orEmpty()
                val playlistsRaw = playlistsDef.await()?.takeIf { it.isSuccessful }?.body()?.string().orEmpty()
                val biosRaw = biosDef.await()?.takeIf { it.isSuccessful }?.body()?.string().orEmpty()

                val standaloneEntries = XkResponseParser.parseSongsEntries(songsRaw)
                val albumEntries = XkResponseParser.parseAlbumsEntries(albumsRaw)

                val allSongsMap = LinkedHashMap<String, ParsedSongEntry>()
                standaloneEntries.forEach { allSongsMap[it.song.providerId] = it }
                albumEntries.forEach { albumEntry ->
                    albumEntry.embeddedSongs.forEach { embedded ->
                        if (embedded.providerId !in allSongsMap) {
                            allSongsMap[embedded.providerId] = ParsedSongEntry(
                                song = embedded,
                                price = embedded.price,
                                currency = embedded.currency.ifBlank { "VND" },
                                isPaid = embedded.isPaidTrack || embedded.price > 0.0,
                                isHidden = false,
                                playCount = 0L,
                                rawLyrics = null,
                                lrcUrl = null,
                                createdAtMs = embedded.createdAtMs
                            )
                        }
                    }
                }

                val songsLookup = allSongsMap.mapValues { it.value.song }
                val publicPlaylists = XkResponseParser.parsePlaylistsEntries(playlistsRaw, songsLookup)
                val bios = XkResponseParser.parseArtistBios(biosRaw)
                val artists = buildArtistsFromCatalog(allSongsMap.values.toList(), albumEntries, bios)

                if (allSongsMap.isNotEmpty()) cachedSongEntries = allSongsMap
                if (albumEntries.isNotEmpty()) cachedAlbumsEntries = albumEntries
                if (artists.isNotEmpty()) cachedArtistsList = artists
                if (publicPlaylists.isNotEmpty()) {
                    val byId = cachedPlaylistsMap.toMutableMap()
                    publicPlaylists.forEach { byId.putIfAbsent(it.playlist.providerId, it) }
                    cachedPlaylistsMap = byId
                }
            }
        }
    }

    private fun buildArtistsFromCatalog(
        entries: List<ParsedSongEntry>,
        albums: List<ParsedAlbumEntry>,
        bios: Map<String, Pair<String?, String?>>
    ): List<ProviderArtist> {
        val songsByArtist = linkedMapOf<String, MutableList<ParsedSongEntry>>()
        entries.forEach { entry ->
            val names = chromahub.rhythm.app.util.ArtistSeparator.splitArtistNames(
                entry.song.artist,
                delimiters = ",;/",
                enabled = true
            )
            names.ifEmpty { listOf(entry.song.artist) }.forEach { artistName ->
                songsByArtist.getOrPut(artistName) { mutableListOf() }.add(entry)
            }
        }
        val albumsByArtist = albums.groupBy { it.album.artist.trim() }
        val result = songsByArtist.entries
            .sortedByDescending { it.value.sumOf { e -> e.playCount } }
            .map { (name, artistSongs) ->
                val id = name.replace("/", "_slash_")
                val bioPair = bios[name] ?: bios.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                val avatar = bioPair?.second ?: artistSongs.firstNotNullOfOrNull { it.song.artworkUrl }
                ProviderArtist(
                    providerId = id,
                    name = name,
                    artworkUrl = avatar,
                    songCount = artistSongs.size,
                    albumCount = albumsByArtist[name]?.size ?: 0,
                    description = bioPair?.first
                )
            }
        return if (result.isNotEmpty()) {
            result
        } else {
            bios.entries.map { (name, pair) ->
                ProviderArtist(
                    providerId = name.replace("/", "_slash_"),
                    name = name,
                    artworkUrl = pair.second,
                    description = pair.first
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Collection & Error Helpers
    // -------------------------------------------------------------------------

    internal fun extractIdSet(value: Any?): Set<String> {
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.toSet()
            is Map<*, *> -> value.entries
                .filter { (_, v) -> v != null && v != false }
                .mapNotNull { (k, _) -> k?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .toSet()
            else -> emptySet()
        }
    }

    private fun updateIdCollection(existing: Any?, songId: String, add: Boolean): Any {
        return if (existing is Map<*, *>) {
            val map = existing.entries
                .mapNotNull { (k, v) -> k?.toString()?.let { it to v } }
                .toMap()
                .toMutableMap()
            if (add) map[songId] = true else map.remove(songId)
            map
        } else {
            val list = extractIdSet(existing).toMutableList()
            if (add) {
                if (songId !in list) list.add(songId)
            } else {
                list.removeAll { it == songId }
            }
            list
        }
    }

    private fun asStringAnyMap(value: Any?): Map<String, Any?>? {
        val raw = value as? Map<*, *> ?: return null
        return raw.entries.mapNotNull { (k, v) -> k?.toString()?.let { it to v } }.toMap()
    }

    private fun pickRicherCloudStats(
        first: CloudListeningStats?,
        second: CloudListeningStats?
    ): CloudListeningStats? {
        if (first == null) return second
        if (second == null) return first
        return when {
            second.totalDurationMs > first.totalDurationMs -> second
            second.totalDurationMs < first.totalDurationMs -> first
            second.totalPlayCount > first.totalPlayCount -> second
            second.updatedAtMs > first.updatedAtMs -> second
            else -> first
        }
    }

    internal fun toCloudListeningStatsMap(stats: CloudListeningStats): Map<String, Any?> {
        return mapOf(
            "totalDurationMs" to stats.totalDurationMs,
            "totalPlayCount" to stats.totalPlayCount,
            "uniqueArtists" to stats.uniqueArtists,
            "uniqueSongs" to stats.uniqueSongs,
            "archiveDurationMs" to stats.archiveDurationMs,
            "archivePlayCount" to stats.archivePlayCount,
            "archiveUniqueArtists" to stats.archiveUniqueArtists,
            "archiveUniqueSongs" to stats.archiveUniqueSongs,
            "dailyListeningMs" to stats.dailyListeningMs,
            "recentEvents" to stats.recentEvents.map { ev ->
                mapOf(
                    "songId" to ev.songId,
                    "timestamp" to ev.timestamp,
                    "durationMs" to ev.durationMs,
                    "startTimestamp" to ev.startTimestamp,
                    "endTimestamp" to ev.endTimestamp,
                    "songTitle" to ev.songTitle,
                    "artistName" to ev.artistName,
                    "albumName" to ev.albumName,
                    "genre" to ev.genre
                )
            },
            "updatedAtMs" to stats.updatedAtMs
        )
    }

    internal fun toCloudListeningStatsJson(stats: CloudListeningStats): JSONObject {
        val dailyObj = JSONObject()
        stats.dailyListeningMs.forEach { (k, v) -> dailyObj.put(k, v) }
        val eventsArr = JSONArray()
        stats.recentEvents.forEach { ev ->
            eventsArr.put(
                JSONObject().apply {
                    put("songId", ev.songId)
                    put("timestamp", ev.timestamp)
                    put("durationMs", ev.durationMs)
                    put("startTimestamp", ev.startTimestamp)
                    put("endTimestamp", ev.endTimestamp)
                    if (!ev.songTitle.isNullOrBlank()) put("songTitle", ev.songTitle)
                    if (!ev.artistName.isNullOrBlank()) put("artistName", ev.artistName)
                    if (!ev.albumName.isNullOrBlank()) put("albumName", ev.albumName)
                    if (!ev.genre.isNullOrBlank()) put("genre", ev.genre)
                }
            )
        }
        return JSONObject().apply {
            put("totalDurationMs", stats.totalDurationMs)
            put("totalPlayCount", stats.totalPlayCount)
            put("uniqueArtists", stats.uniqueArtists)
            put("uniqueSongs", stats.uniqueSongs)
            put("archiveDurationMs", stats.archiveDurationMs)
            put("archivePlayCount", stats.archivePlayCount)
            put("archiveUniqueArtists", stats.archiveUniqueArtists)
            put("archiveUniqueSongs", stats.archiveUniqueSongs)
            put("dailyListeningMs", dailyObj)
            put("recentEvents", eventsArr)
            put("updatedAtMs", stats.updatedAtMs)
        }
    }

    internal fun parseCloudListeningStatsFromAny(raw: Any?): CloudListeningStats? {
        val map = raw as? Map<*, *> ?: return null
        fun numLong(key: String): Long = (map[key] as? Number)?.toLong()
            ?: map[key]?.toString()?.toDoubleOrNull()?.toLong()
            ?: 0L
        fun numInt(key: String): Int = numLong(key).toInt()

        val dailyRaw = map["dailyListeningMs"] as? Map<*, *>
        val daily = buildMap {
            dailyRaw?.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val valMs = (v as? Number)?.toLong() ?: v?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
                if (key.isNotBlank() && valMs > 0L) put(key, valMs)
            }
        }

        val rawEvents: List<*> = when (val evNode = map["recentEvents"]) {
            is List<*> -> evNode
            is Map<*, *> -> evNode.values.toList()
            else -> emptyList<Any?>()
        }
        val events = rawEvents.mapNotNull { item ->
            val evMap = item as? Map<*, *> ?: return@mapNotNull null
            val songId = evMap["songId"]?.toString()?.trim().orEmpty()
            if (songId.isBlank()) return@mapNotNull null
            fun evLong(k: String): Long = (evMap[k] as? Number)?.toLong()
                ?: evMap[k]?.toString()?.toDoubleOrNull()?.toLong()
                ?: 0L
            CloudListeningEvent(
                songId = songId,
                timestamp = evLong("timestamp"),
                durationMs = evLong("durationMs"),
                startTimestamp = evLong("startTimestamp"),
                endTimestamp = evLong("endTimestamp"),
                songTitle = evMap["songTitle"]?.toString(),
                artistName = evMap["artistName"]?.toString(),
                albumName = evMap["albumName"]?.toString(),
                genre = evMap["genre"]?.toString()
            )
        }

        val stats = CloudListeningStats(
            totalDurationMs = numLong("totalDurationMs"),
            totalPlayCount = numInt("totalPlayCount"),
            uniqueArtists = numInt("uniqueArtists"),
            uniqueSongs = numInt("uniqueSongs"),
            archiveDurationMs = numLong("archiveDurationMs"),
            archivePlayCount = numInt("archivePlayCount"),
            archiveUniqueArtists = numInt("archiveUniqueArtists"),
            archiveUniqueSongs = numInt("archiveUniqueSongs"),
            dailyListeningMs = daily,
            recentEvents = events,
            updatedAtMs = numLong("updatedAtMs")
        )
        return if (stats.totalDurationMs == 0L && stats.totalPlayCount == 0 && stats.recentEvents.isEmpty()) {
            null
        } else {
            stats
        }
    }

    internal fun parseCloudListeningStatsFromJsonObject(obj: JSONObject): CloudListeningStats? {
        val dailyObj = obj.optJSONObject("dailyListeningMs")
        val daily = buildMap {
            if (dailyObj != null) {
                val keys = dailyObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = dailyObj.optLong(k, 0L)
                    if (k.isNotBlank() && v > 0L) put(k, v)
                }
            }
        }
        val eventsArr = obj.optJSONArray("recentEvents")
        val events = buildList {
            if (eventsArr != null) {
                for (i in 0 until eventsArr.length()) {
                    val evObj = eventsArr.optJSONObject(i) ?: continue
                    val songId = evObj.optString("songId", "").trim()
                    if (songId.isBlank()) continue
                    add(
                        CloudListeningEvent(
                            songId = songId,
                            timestamp = evObj.optLong("timestamp", 0L),
                            durationMs = evObj.optLong("durationMs", 0L),
                            startTimestamp = evObj.optLong("startTimestamp", 0L),
                            endTimestamp = evObj.optLong("endTimestamp", 0L),
                            songTitle = evObj.optString("songTitle", "").takeIf { it.isNotBlank() && it != "null" },
                            artistName = evObj.optString("artistName", "").takeIf { it.isNotBlank() && it != "null" },
                            albumName = evObj.optString("albumName", "").takeIf { it.isNotBlank() && it != "null" },
                            genre = evObj.optString("genre", "").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        }
        val stats = CloudListeningStats(
            totalDurationMs = obj.optLong("totalDurationMs", 0L),
            totalPlayCount = obj.optInt("totalPlayCount", 0),
            uniqueArtists = obj.optInt("uniqueArtists", 0),
            uniqueSongs = obj.optInt("uniqueSongs", 0),
            archiveDurationMs = obj.optLong("archiveDurationMs", 0L),
            archivePlayCount = obj.optInt("archivePlayCount", 0),
            archiveUniqueArtists = obj.optInt("archiveUniqueArtists", 0),
            archiveUniqueSongs = obj.optInt("archiveUniqueSongs", 0),
            dailyListeningMs = daily,
            recentEvents = events,
            updatedAtMs = obj.optLong("updatedAtMs", 0L)
        )
        return if (stats.totalDurationMs == 0L && stats.totalPlayCount == 0 && stats.recentEvents.isEmpty()) {
            null
        } else {
            stats
        }
    }

    private fun parseFirebaseAuthError(e: HttpException): String {
        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        val parsed = XkResponseParser.parseErrorMessage(raw)
        return mapFirebaseAuthErrorMessage(parsed) ?: "HTTP ${e.code()}: ${e.message()}"
    }

    private fun mapFirebaseAuthErrorMessage(raw: String?): String? {
        val upper = raw.orEmpty().uppercase()
        return when {
            "INVALID_LOGIN_CREDENTIALS" in upper || "INVALID_PASSWORD" in upper || "EMAIL_NOT_FOUND" in upper ->
                "Email hoặc mật khẩu không chính xác."
            "EMAIL_EXISTS" in upper -> "Email này đã được đăng ký tài khoản."
            "WEAK_PASSWORD" in upper -> "Mật khẩu quá yếu (cần ít nhất 6 ký tự)."
            "INVALID_EMAIL" in upper -> "Địa chỉ email không hợp lệ."
            "USER_DISABLED" in upper -> "Tài khoản này đã bị vô hiệu hóa."
            "TOO_MANY_ATTEMPTS" in upper -> "Thử đăng nhập quá nhiều lần. Vui lòng thử lại sau."
            !raw.isNullOrBlank() -> raw
            else -> null
        }
    }

    internal fun stripProviderPrefix(id: String): String {
        return if (id.contains("::")) id.substringAfterLast("::") else id
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() && it != "null" }?.trim().orEmpty()
    }

    companion object {
        private const val TAG = "XuanKenFirebaseClient"
        private const val PREFS_NAME = "xuanken_firebase_prefs"
        private const val KEY_UID = "firebase_uid"
        private const val KEY_EMAIL = "firebase_email"
        private const val KEY_DISPLAY_NAME = "firebase_display_name"
        private const val KEY_ID_TOKEN = "firebase_id_token"
        private const val KEY_REFRESH_TOKEN = "firebase_refresh_token"
        private const val KEY_EXPIRES_AT_MS = "firebase_expires_at_ms"
        private const val KEY_GUEST_CONNECTED = "firebase_guest_connected"
        private const val KEY_IS_ADMIN = "firebase_is_admin"
        private const val TOKEN_REFRESH_MARGIN_MS = 60_000L

        @Volatile
        private var instance: XuanKenFirebaseClient? = null

        fun getInstance(context: Context): XuanKenFirebaseClient {
            return instance ?: synchronized(this) {
                instance ?: XuanKenFirebaseClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
