/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.network

import chromahub.rhythm.app.BuildConfig
import chromahub.rhythm.app.features.streaming.data.provider.UserTrustManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.net.URI
import java.util.concurrent.TimeUnit

interface XkMusicApi {
    // Auth endpoints
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body body: RequestBody): Response<ResponseBody>

    // Home & Search
    @GET("api/v1/home")
    suspend fun getHome(): Response<ResponseBody>

    @GET("api/v1/search")
    suspend fun search(@Query("q") query: String): Response<ResponseBody>

    // User profile, settings, likes & blocks
    @GET("api/v1/me")
    suspend fun getMe(): Response<ResponseBody>

    @PATCH("api/v1/me")
    suspend fun updateMe(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/v1/me/settings")
    suspend fun getMeSettings(): Response<ResponseBody>

    @PATCH("api/v1/me/settings")
    suspend fun updateMeSettings(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/v1/me/likes")
    suspend fun toggleLike(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/v1/me/album-likes")
    suspend fun toggleAlbumLike(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/v1/me/blocks")
    suspend fun getBlockedSongs(): Response<ResponseBody>

    @POST("api/v1/me/blocks")
    suspend fun blockSong(@Body body: RequestBody): Response<ResponseBody>

    @DELETE("api/v1/me/blocks/{songId}")
    suspend fun unblockSong(@Path("songId") songId: String): Response<ResponseBody>

    // Songs, streaming, download, play count & report
    @GET("api/v1/songs")
    suspend fun getSongs(
        @Query("q") query: String? = null,
        @Query("genre") genre: String? = null,
        @Query("paid") paid: String? = null,
        @Query("sort") sort: String? = "popular"
    ): Response<ResponseBody>

    @GET("api/v1/songs/{id}")
    suspend fun getSongById(@Path("id") id: String): Response<ResponseBody>

    @GET("api/v1/songs/{id}/stream")
    suspend fun getSongStream(@Path("id") id: String): Response<ResponseBody>

    @GET("api/v1/songs/{id}/download")
    suspend fun getSongDownload(@Path("id") id: String): Response<ResponseBody>

    @POST("api/v1/songs/{id}/play")
    suspend fun recordSongPlay(
        @Path("id") id: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @POST("api/v1/songs/{id}/report")
    suspend fun reportSong(
        @Path("id") id: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    // Purchases & vouchers
    @POST("api/v1/songs/{id}/purchase")
    suspend fun purchaseSong(
        @Path("id") id: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @GET("api/v1/purchases")
    suspend fun getPurchases(): Response<ResponseBody>

    @POST("api/v1/purchases")
    suspend fun createPurchase(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/v1/vouchers")
    suspend fun getVouchers(): Response<ResponseBody>

    @GET("api/v1/vouchers/validate")
    suspend fun validateVoucher(
        @Query("code") code: String,
        @Query("songId") songId: String? = null
    ): Response<ResponseBody>

    // Playlists CRUD
    @GET("api/v1/playlists")
    suspend fun getPlaylists(): Response<ResponseBody>

    @POST("api/v1/playlists")
    suspend fun createPlaylist(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/v1/playlists/{id}")
    suspend fun getPlaylistById(@Path("id") id: String): Response<ResponseBody>

    @PATCH("api/v1/playlists/{id}")
    suspend fun updatePlaylist(
        @Path("id") id: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @DELETE("api/v1/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): Response<ResponseBody>

    // Albums, Artists, Charts, Site
    @GET("api/v1/albums")
    suspend fun getAlbums(): Response<ResponseBody>

    @GET("api/v1/albums/{id}")
    suspend fun getAlbumById(@Path("id") id: String): Response<ResponseBody>

    @GET("api/v1/artists")
    suspend fun getArtists(): Response<ResponseBody>

    @GET("api/v1/artists/{id}")
    suspend fun getArtistById(@Path("id") id: String): Response<ResponseBody>

    @GET("api/v1/charts")
    suspend fun getCharts(): Response<ResponseBody>

    @GET("api/v1/site")
    suspend fun getSiteInfo(): Response<ResponseBody>

    @GET
    suspend fun getTextFromUrl(@Url url: String): Response<ResponseBody>
}

object XkMusicApiService {
    const val BASE_URL = "https://xkmu.xuanken.name.vn/"
    private const val PLACEHOLDER_KEY_1 = "YOUR_XKMU_API_KEY"
    private const val PLACEHOLDER_KEY_2 = "MY_XKMU_API_KEY"

    @Volatile
    internal var baseUrlOverride: String? = null

    @Volatile
    internal var apiKeyOverride: String? = null

    @Volatile
    private var activeUserToken: String? = null

    fun setUserToken(token: String?) {
        activeUserToken = token?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun currentUserToken(): String? = activeUserToken

    fun currentApiKey(): String = (apiKeyOverride ?: BuildConfig.XKMU_API_KEY).trim()

    fun isApiKeyConfigured(): Boolean {
        val key = currentApiKey()
        return key.isNotEmpty() && key != PLACEHOLDER_KEY_1 && key != PLACEHOLDER_KEY_2
    }

    fun shouldAttachXkHeaders(request: Request): Boolean {
        val host = request.url.host.lowercase()
        if (host.contains("xuanken.name.vn")) return true
        val overrideUrl = baseUrlOverride ?: return false
        val overrideUri = runCatching { URI(overrideUrl) }.getOrNull() ?: return false
        val overrideHost = overrideUri.host?.lowercase() ?: return false
        val overridePort = if (overrideUri.port != -1) overrideUri.port else request.url.port
        return host == overrideHost && request.url.port == overridePort
    }

    fun buildAuthenticatedRequest(request: Request, acceptJson: Boolean = false): Request {
        val builder = request.newBuilder()
        if (acceptJson) {
            builder.header("Accept", "application/json")
        }
        if (shouldAttachXkHeaders(request)) {
            val key = currentApiKey()
            val hasValidKey = key.isNotEmpty() && key != PLACEHOLDER_KEY_1 && key != PLACEHOLDER_KEY_2
            if (hasValidKey) {
                builder.header("x-api-key", key)
            }
            val userToken = activeUserToken
            if (!userToken.isNullOrBlank()) {
                builder
                    .header("x-user-token", userToken)
                    .header("Authorization", "Bearer $userToken")
            } else if (hasValidKey) {
                builder.header("Authorization", "Bearer $key")
            }
        }
        return builder.build()
    }

    val authInterceptor = Interceptor { chain ->
        chain.proceed(buildAuthenticatedRequest(chain.request(), acceptJson = true))
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        UserTrustManager.buildUserTrustingHttpClientBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun api(): XkMusicApi {
        val baseUrl = baseUrlOverride ?: BASE_URL
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .build()
            .create(XkMusicApi::class.java)
    }
}
