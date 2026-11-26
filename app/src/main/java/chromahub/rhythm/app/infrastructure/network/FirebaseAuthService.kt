/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.network

import chromahub.rhythm.app.BuildConfig
import chromahub.rhythm.app.features.streaming.data.provider.UserTrustManager
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class FirebaseLoginReq(
    val email: String,
    val password: String,
    val returnSecureToken: Boolean = true
)

data class FirebaseLoginRes(
    val idToken: String = "",
    val email: String = "",
    val refreshToken: String = "",
    val expiresIn: String = "3600",
    val localId: String = "",
    val displayName: String? = null
)

data class FirebaseRefreshRes(
    @SerializedName("id_token")
    val idToken: String = "",
    @SerializedName("refresh_token")
    val refreshToken: String = "",
    @SerializedName("expires_in")
    val expiresIn: String = "3600",
    @SerializedName("user_id")
    val userId: String = ""
)

interface FirebaseAuthApi {
    @POST("./accounts:signInWithPassword")
    suspend fun login(
        @Query("key") apiKey: String = FirebaseAuthService.getApiKey(),
        @Body body: FirebaseLoginReq
    ): FirebaseLoginRes

    @POST("./accounts:signUp")
    suspend fun signUp(
        @Query("key") apiKey: String = FirebaseAuthService.getApiKey(),
        @Body body: RequestBody
    ): Response<ResponseBody>

    @POST("./accounts:update")
    suspend fun updateProfile(
        @Query("key") apiKey: String = FirebaseAuthService.getApiKey(),
        @Body body: RequestBody
    ): Response<ResponseBody>

    @POST("./accounts:lookup")
    suspend fun lookupAccount(
        @Query("key") apiKey: String = FirebaseAuthService.getApiKey(),
        @Body body: RequestBody
    ): Response<ResponseBody>
}

interface FirebaseSecureTokenApi {
    @FormUrlEncoded
    @POST("token")
    suspend fun refreshToken(
        @Query("key") apiKey: String = FirebaseAuthService.getApiKey(),
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String
    ): FirebaseRefreshRes
}

object FirebaseAuthService {
    const val BASE_URL = "https://identitytoolkit.googleapis.com/v1/"
    const val SECURE_TOKEN_BASE_URL = "https://securetoken.googleapis.com/v1/"
    const val PLACEHOLDER_FIREBASE_API_KEY = "YOUR_FIREBASE_WEB_API_KEY"

    @Volatile
    internal var authBaseUrlOverride: String? = null

    @Volatile
    internal var secureTokenBaseUrlOverride: String? = null

    @Volatile
    internal var apiKeyOverride: String? = null

    private val okHttpClient: OkHttpClient by lazy {
        UserTrustManager.buildUserTrustingHttpClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getApiKey(): String = (apiKeyOverride ?: BuildConfig.FIREBASE_WEB_API_KEY).trim()

    fun isApiKeyConfigured(): Boolean {
        val key = getApiKey()
        return key.isNotEmpty() && key != PLACEHOLDER_FIREBASE_API_KEY
    }

    fun authApi(): FirebaseAuthApi {
        val baseUrl = authBaseUrlOverride ?: BASE_URL
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FirebaseAuthApi::class.java)
    }

    fun secureTokenApi(): FirebaseSecureTokenApi {
        val baseUrl = secureTokenBaseUrlOverride ?: SECURE_TOKEN_BASE_URL
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FirebaseSecureTokenApi::class.java)
    }
}
