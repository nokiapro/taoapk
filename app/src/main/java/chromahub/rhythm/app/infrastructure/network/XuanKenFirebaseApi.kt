/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.network

import chromahub.rhythm.app.features.streaming.data.provider.UserTrustManager
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class XuanKenSongDto(
    val id: String? = null,
    val title: String? = null,
    val name: String? = null,
    val artist: Any? = null,
    val artistName: String? = null,
    val album: Any? = null,
    val albumTitle: String? = null,
    val albumId: String? = null,
    val duration: Double? = null,
    val durationMs: Long? = null,
    val durationSeconds: Double? = null,
    val coverUrl: String? = null,
    val artworkUrl: String? = null,
    val imageUrl: String? = null,
    val cover: String? = null,
    val thumbnail: String? = null,
    val demoUrl: String? = null,
    @SerializedName("mp3_128Url")
    val mp3_128Url: String? = null,
    val mp3128Url: String? = null,
    val previewUrl: String? = null,
    val streamUrl: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val playCount: Long? = null,
    val plays: Long? = null,
    val lyrics: Any? = null,
    val lrc: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    val createdAt: Any? = null,
    val isPaid: Boolean? = null,
    val isHidden: Boolean? = null
)

data class SongMasterDto(
    val songId: String? = null,
    val wavUrl: String? = null,
    val flacUrl: String? = null,
    val mp3Url: String? = null,
    @SerializedName("mp3_320Url")
    val mp3_320Url: String? = null,
    val mp3320Url: String? = null,
    val bestUrl: String? = null,
    val streamUrl: String? = null,
    val url: String? = null,
    @SerializedName("mp3_128Url")
    val mp3_128Url: String? = null,
    val demoUrl: String? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null
)

data class MemberDto(
    val uid: String? = null,
    val id: String? = null,
    val email: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
    val avatar: String? = null,
    val role: String? = null,
    val plan: String? = null,
    val tier: String? = null,
    val balance: Double? = null,
    val currency: String? = null,
    val purchasedSongIds: Any? = null,
    val likedSongIds: Any? = null,
    val likedAlbumIds: Any? = null,
    val blockedSongIds: Any? = null,
    val settings: Map<String, Any?>? = null,
    val listeningStats: Map<String, Any?>? = null
)

data class PlaylistDto(
    val id: String? = null,
    val userId: String? = null,
    val owner: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val artworkUrl: String? = null,
    val isPublic: Boolean? = null,
    val songIds: Any? = null,
    val songs: Any? = null,
    val createdAt: Any? = null,
    val updatedAt: Any? = null
)

data class UserPurchaseReq(
    val songId: String,
    val purchaseId: String,
    val price: Double,
    val purchasedAt: String,
    val voucherCode: String? = null
)

interface XuanKenFirebaseApi {
    // 1. Công khai: Danh sách bài hát (chỉ có demoUrl & mp3_128Url)
    @GET("songs.json")
    suspend fun getPublicSongs(): Map<String, XuanKenSongDto>?

    // 2. Bảo mật: Lấy link 320kbps / WAV (chỉ đọc được nếu miễn phí hoặc đã mua)
    @GET("song_masters/{songId}.json")
    suspend fun getSongMaster(
        @Path("songId") songId: String,
        @Query("auth") idToken: String
    ): SongMasterDto?

    // 3. Album & Ca sĩ & Voucher & Cài đặt web
    @GET("exclusive_albums.json")
    suspend fun getAlbums(): Map<String, Any>?

    @GET("artist_bios.json")
    suspend fun getArtists(): Map<String, Any>?

    @GET("vouchers.json")
    suspend fun getVouchers(): Map<String, Any>?

    // 4. Thông tin tài khoản (balance, purchasedSongIds, likedSongIds...)
    @GET("members/{uid}.json")
    suspend fun getMember(
        @Path("uid") uid: String,
        @Query("auth") idToken: String
    ): MemberDto?

    // 5. Playlist cá nhân riêng của tài khoản đang đăng nhập
    @GET("playlists.json")
    suspend fun getMyPlaylists(
        @Query("orderBy") orderBy: String = "\"userId\"",
        @Query("equalTo") equalTo: String,
        @Query("auth") idToken: String
    ): Map<String, PlaylistDto>?

    // 6. Cập nhật thành viên (số dư, bài đã mua, thích, chặn, hồ sơ, cài đặt)
    @PATCH("members/{uid}.json")
    suspend fun patchMember(
        @Path("uid") uid: String,
        @Query("auth") idToken: String,
        @Body updates: Map<String, @JvmSuppressWildcards Any?>
    ): Map<String, Any>?

    // 7. Lưu lịch sử mua bài hát & quyền sở hữu
    @PUT("user_purchases/{uid}/{songId}.json")
    suspend fun putUserPurchase(
        @Path("uid") uid: String,
        @Path("songId") songId: String,
        @Query("auth") idToken: String,
        @Body body: UserPurchaseReq
    ): Map<String, Any>?

    @GET("user_purchases/{uid}.json")
    suspend fun getUserPurchases(
        @Path("uid") uid: String,
        @Query("auth") idToken: String
    ): Map<String, Map<String, Any?>>?

    @PUT("purchases/{purchaseId}.json")
    suspend fun putPurchase(
        @Path("purchaseId") purchaseId: String,
        @Query("auth") idToken: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Map<String, Any>?

    // 8. CRUD Playlist cá nhân & Báo cáo bài hát
    @PUT("playlists/{playlistId}.json")
    suspend fun putPlaylist(
        @Path("playlistId") playlistId: String,
        @Query("auth") idToken: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): PlaylistDto?

    @PATCH("playlists/{playlistId}.json")
    suspend fun patchPlaylist(
        @Path("playlistId") playlistId: String,
        @Query("auth") idToken: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): PlaylistDto?

    @DELETE("playlists/{playlistId}.json")
    suspend fun deletePlaylist(
        @Path("playlistId") playlistId: String,
        @Query("auth") idToken: String
    ): Response<ResponseBody>

    @PUT("reports/{reportId}.json")
    suspend fun putReport(
        @Path("reportId") reportId: String,
        @Query("auth") idToken: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<ResponseBody>

    // 9. Endpoint bổ sung khớp xk-music.zip (raw JSON + admin + weekly charts + site settings + liked_songs)
    @GET("songs.json")
    suspend fun getSongsRaw(): Response<ResponseBody>

    @GET("exclusive_albums.json")
    suspend fun getExclusiveAlbumsRaw(): Response<ResponseBody>

    @GET("playlists.json")
    suspend fun getPlaylistsRaw(): Response<ResponseBody>

    @GET("artist_bios.json")
    suspend fun getArtistBios(): Response<ResponseBody>

    @GET("playlists.json")
    suspend fun getAllPlaylistsAuthed(@Query("auth") authToken: String?): Response<ResponseBody>

    @GET("admins/{uid}.json")
    suspend fun getAdminRecord(
        @Path("uid") uid: String,
        @Query("auth") authToken: String?
    ): Response<ResponseBody>

    @GET("song_masters.json")
    suspend fun getAllSongMasters(@Query("auth") authToken: String?): Response<ResponseBody>

    @GET("weekly_plays.json")
    suspend fun getWeeklyPlaysRaw(): Response<ResponseBody>

    @GET("site_settings.json")
    suspend fun getSiteSettingsRaw(): Response<ResponseBody>

    @GET("users/{uid}/liked_songs.json")
    suspend fun getUserLikedSongs(
        @Path("uid") uid: String,
        @Query("auth") authToken: String?
    ): Response<ResponseBody>

    @PUT("users/{uid}/liked_songs.json")
    suspend fun putUserLikedSongs(
        @Path("uid") uid: String,
        @Query("auth") authToken: String?,
        @Body body: okhttp3.RequestBody
    ): Response<ResponseBody>
}

object XuanKenFirebaseService {
    const val BASE_URL = "https://newmusic-5625b-default-rtdb.asia-southeast1.firebasedatabase.app/"

    @Volatile
    internal var baseUrlOverride: String? = null

    private val gson by lazy {
        GsonBuilder().setLenient().create()
    }

    private val okHttpClient: OkHttpClient by lazy {
        UserTrustManager.buildUserTrustingHttpClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun api(): XuanKenFirebaseApi {
        val baseUrl = baseUrlOverride ?: BASE_URL
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(XuanKenFirebaseApi::class.java)
    }
}
