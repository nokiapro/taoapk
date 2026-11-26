/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import chromahub.rhythm.app.features.streaming.data.provider.XuanKenFirebaseClient
import chromahub.rhythm.app.network.FirebaseAuthService
import chromahub.rhythm.app.network.XkFirestoreHelper
import chromahub.rhythm.app.network.XkMusicApiService
import chromahub.rhythm.app.network.XuanKenFirebaseService
import chromahub.rhythm.app.shared.data.model.AppSettings
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XuanKenFirebaseIntegrationTest {

    private lateinit var app: Application
    private lateinit var xkServer: MockWebServer
    private lateinit var authServer: MockWebServer
    private lateinit var refreshServer: MockWebServer
    private lateinit var rtdbServer: MockWebServer
    private lateinit var firestoreServer: MockWebServer
    private lateinit var client: XuanKenFirebaseClient

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        xkServer = MockWebServer().apply { start() }
        authServer = MockWebServer().apply { start() }
        refreshServer = MockWebServer().apply { start() }
        rtdbServer = MockWebServer().apply { start() }
        firestoreServer = MockWebServer().apply { start() }

        // Default primary API behavior: return 404 HTML unless overridden per-test,
        // verifying that 404 HTML triggers clean Firebase + RTDB + Firestore fallback!
        val notFoundDispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse.Builder()
                    .code(404)
                    .addHeader("Content-Type", "text/html")
                    .body("<html><body>Not Found</body></html>")
                    .build()
            }
        }
        xkServer.dispatcher = notFoundDispatcher
        firestoreServer.dispatcher = notFoundDispatcher
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = jsonOk("{}")
        }

        XkMusicApiService.baseUrlOverride = xkServer.url("/").toString()
        XkMusicApiService.apiKeyOverride = "test-xk-api-key"
        XkMusicApiService.setUserToken(null)
        FirebaseAuthService.authBaseUrlOverride = authServer.url("/").toString()
        FirebaseAuthService.secureTokenBaseUrlOverride = refreshServer.url("/").toString()
        FirebaseAuthService.apiKeyOverride = "test-firebase-api-key"
        XuanKenFirebaseService.baseUrlOverride = rtdbServer.url("/").toString()
        XkFirestoreHelper.baseUrlOverride = firestoreServer.url("/").toString()

        AppSettings.getInstance(app).setStreamingQuality("HIGH")
        client = XuanKenFirebaseClient(app).apply { logout() }
    }

    @After
    fun tearDown() {
        client.logout()
        XkMusicApiService.baseUrlOverride = null
        XkMusicApiService.apiKeyOverride = null
        XkMusicApiService.setUserToken(null)
        FirebaseAuthService.authBaseUrlOverride = null
        FirebaseAuthService.secureTokenBaseUrlOverride = null
        FirebaseAuthService.apiKeyOverride = null
        XuanKenFirebaseService.baseUrlOverride = null
        XkFirestoreHelper.baseUrlOverride = null

        runCatching { xkServer.close() }
        runCatching { authServer.close() }
        runCatching { refreshServer.close() }
        runCatching { rtdbServer.close() }
        runCatching { firestoreServer.close() }
    }

    @Test
    fun primaryApi_loginHomeStreamAndPurchase_sendsXkHeadersAndParsesStreamUrls() = runBlocking {
        val capturedHeaders = mutableMapOf<String, Map<String, String>>()
        xkServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                capturedHeaders[path] = mapOf(
                    "x-api-key" to (request.headers["x-api-key"] ?: ""),
                    "x-user-token" to (request.headers["x-user-token"] ?: ""),
                    "Authorization" to (request.headers["Authorization"] ?: "")
                )
                return when {
                    path == "/api/v1/auth/login" && request.method == "POST" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "idToken": "xk_user_jwt",
                              "refreshToken": "xk_refresh_token",
                              "expiresIn": 3600,
                              "user": {
                                "uid": "uid_xk_1",
                                "email": "vip@xuanken.name.vn",
                                "displayName": "Ken VIP",
                                "balance": 120000,
                                "purchasedSongIds": ["song_paid_1"]
                              }
                            }
                            """.trimIndent()
                        )
                        .build()
                    path == "/api/v1/me" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "uid": "uid_xk_1",
                              "email": "vip@xuanken.name.vn",
                              "displayName": "Ken VIP",
                              "balance": 120000,
                              "purchasedSongIds": ["song_paid_1"]
                            }
                            """.trimIndent()
                        )
                        .build()
                    path == "/api/v1/home" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "site": { "siteName": "XuanKen Music", "description": "Master Audio" },
                              "newReleases": [
                                {
                                  "id": "song_paid_1",
                                  "title": "Dem Nhac Mua Thu",
                                  "artist": "Xuan Ken",
                                  "price": 20000,
                                  "isPaid": true,
                                  "demoUrl": "https://cdn.test/demo.mp3"
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                        .build()
                    path == "/api/v1/songs/song_paid_1/stream" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "streamUrls": {
                                "demo": "https://cdn.test/demo.mp3",
                                "mp3_128": "https://cdn.test/128.mp3",
                                "mp3_320": "https://xkmu.xuanken.name.vn/api/v1/stream/song_paid_1?sig=abc",
                                "best": "https://xkmu.xuanken.name.vn/api/v1/stream/song_paid_1?fmt=wav&sig=abc"
                              }
                            }
                            """.trimIndent()
                        )
                        .build()
                    path == "/api/v1/songs/song_new_2/purchase" && request.method == "POST" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "purchase": {
                                "id": "pur_xk_99",
                                "songId": "song_new_2",
                                "amount": 30000,
                                "discountAmount": 15000,
                                "finalAmount": 15000,
                                "voucherCode": "XUANKEN50",
                                "status": "completed"
                              }
                            }
                            """.trimIndent()
                        )
                        .build()
                    path == "/api/v1/songs/song_new_2" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "id": "song_new_2",
                              "title": "Bai Hat Moi",
                              "artist": "Xuan Ken",
                              "price": 30000,
                              "isPaid": true,
                              "demoUrl": "https://cdn.test/new_demo.mp3"
                            }
                            """.trimIndent()
                        )
                        .build()
                    path == "/api/v1/songs/song_new_2/stream" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "streamUrls": {
                                "mp3_320": "https://xkmu.xuanken.name.vn/api/v1/stream/song_new_2?sig=xyz"
                              }
                            }
                            """.trimIndent()
                        )
                        .build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val login = client.login("vip@xuanken.name.vn", "secret123", saveCredentials = true)
        assertTrue(login.isSuccess)
        assertEquals("uid_xk_1", client.getUid())
        assertEquals("Ken VIP", client.getUsername())
        assertEquals("https://xkmu.xuanken.name.vn", login.getOrThrow().serverUrl)

        val home = client.getHomeBundle().getOrThrow()
        assertEquals(1, home.newestSongs.size)
        assertEquals("Dem Nhac Mua Thu", home.newestSongs.first().title)

        val streamUrl = client.getStreamingUrl("song_paid_1")
        assertEquals("https://xkmu.xuanken.name.vn/api/v1/stream/song_paid_1?fmt=wav&sig=abc", streamUrl)

        // Verify ExoPlayer request helper attaches XK headers ONLY for XKMusic host and not third-party CDNs
        val xkPlayerReq = XkMusicApiService.buildAuthenticatedRequest(
            Request.Builder().url("https://xkmu.xuanken.name.vn/api/v1/stream/song_paid_1").build()
        )
        assertEquals("test-xk-api-key", xkPlayerReq.header("x-api-key"))
        assertEquals("xk_user_jwt", xkPlayerReq.header("x-user-token"))
        assertEquals("Bearer xk_user_jwt", xkPlayerReq.header("Authorization"))

        val cdnPlayerReq = XkMusicApiService.buildAuthenticatedRequest(
            Request.Builder().url("https://storage.googleapis.com/audio/track.mp3").build()
        )
        assertNull(cdnPlayerReq.header("x-api-key"))
        assertNull(cdnPlayerReq.header("x-user-token"))

        // Purchase with voucher through /api/v1/songs/:id/purchase
        val purchase = client.purchaseSong("song_new_2", voucherCode = "XUANKEN50").getOrThrow()
        assertEquals("pur_xk_99", purchase.id)
        assertEquals(15000.0, purchase.finalAmount, 0.01)
        assertEquals("XUANKEN50", purchase.voucherCode)

        val purchaseHeaders = capturedHeaders["/api/v1/songs/song_new_2/purchase"]!!
        assertEquals("test-xk-api-key", purchaseHeaders["x-api-key"])
        assertEquals("xk_user_jwt", purchaseHeaders["x-user-token"])
        assertEquals("Bearer xk_user_jwt", purchaseHeaders["Authorization"])
    }

    @Test
    fun loginAndRefreshToken_fallsBackToFirebaseAuthWhenPrimary404AndRefreshesExpiredIdToken() = runBlocking {
        val capturedRtdbAuths = mutableListOf<String>()
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                request.url.queryParameter("auth")?.let { capturedRtdbAuths.add(it) }
                return when (request.url.encodedPath) {
                    "/members/uid_abc.json" -> jsonOk("""{"uid":"uid_abc","displayName":"Xuan Ken","balance":50000}""")
                    "/admins/uid_abc.json" -> jsonOk("false")
                    else -> jsonOk("{}")
                }
            }
        }

        authServer.enqueue(
            jsonOk(
                """
                {
                  "idToken": "initial_id_token",
                  "email": "listener@xuanken.vn",
                  "refreshToken": "initial_refresh_token",
                  "expiresIn": "1",
                  "localId": "uid_abc"
                }
                """.trimIndent()
            )
        )

        val loginResult = client.login("listener@xuanken.vn", "secret123", saveCredentials = true)
        assertTrue("Login failed: ${loginResult.exceptionOrNull()?.stackTraceToString()}", loginResult.isSuccess)
        assertEquals("uid_abc", client.getUid())
        assertEquals("Xuan Ken", client.getUsername())
        assertTrue(client.hasUserSession())

        val authReq = authServer.takeRequest()
        assertEquals("POST", authReq.method)
        assertTrue(authReq.url.encodedPath.endsWith("/accounts:signInWithPassword"))
        assertEquals("test-firebase-api-key", authReq.url.queryParameter("key"))
        assertTrue(authReq.body!!.utf8().contains("\"returnSecureToken\":true"))
        capturedRtdbAuths.clear()

        // expiresIn = 1s is inside the 60s refresh window, so getProfile() triggers securetoken refresh
        refreshServer.enqueue(
            jsonOk(
                """
                {
                  "id_token": "refreshed_id_token",
                  "refresh_token": "rotated_refresh_token",
                  "expires_in": "3600",
                  "user_id": "uid_abc"
                }
                """.trimIndent()
            )
        )

        val profileResult = client.getProfile()
        assertTrue(profileResult.isSuccess)
        assertEquals(50000.0, profileResult.getOrNull()?.balance ?: 0.0, 0.01)

        val refreshReq = refreshServer.takeRequest()
        assertEquals("POST", refreshReq.method)
        assertTrue(refreshReq.url.encodedPath.endsWith("/token"))
        val refreshBody = refreshReq.body!!.utf8()
        assertTrue(refreshBody.contains("grant_type=refresh_token"))
        assertTrue(refreshBody.contains("refresh_token=initial_refresh_token"))

        assertTrue("RTDB must receive refreshed idToken", capturedRtdbAuths.contains("refreshed_id_token"))
    }

    @Test
    fun getMyPlaylists_quotesOrderByAndEqualToPerRtdbRules() = runBlocking {
        var playlistRequest: RecordedRequest? = null
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/members/uid_pl_1.json" -> jsonOk("""{"uid":"uid_pl_1","displayName":"User","balance":50000}""")
                    "/admins/uid_pl_1.json" -> jsonOk("false")
                    "/playlists.json" -> {
                        if (request.url.queryParameter("orderBy") != null) {
                            playlistRequest = request
                            jsonOk(
                                """
                                {
                                  "pl_1": {
                                    "id": "pl_1",
                                    "userId": "uid_pl_1",
                                    "name": "Nhac Yeu Thich",
                                    "songIds": ["s1", "s2"]
                                  }
                                }
                                """.trimIndent()
                            )
                        } else {
                            jsonOk("{}")
                        }
                    }
                    else -> jsonOk("{}")
                }
            }
        }

        enqueueValidLogin(uid = "uid_pl_1")
        val playlists = client.getPlaylists().getOrThrow()
        assertEquals(1, playlists.size)
        assertEquals("Nhac Yeu Thich", playlists.first().name)
        assertEquals(2, playlists.first().songCount)

        val playlistsReq = playlistRequest!!
        assertEquals("/playlists.json", playlistsReq.url.encodedPath)
        assertEquals("\"userId\"", playlistsReq.url.queryParameter("orderBy"))
        assertEquals("\"uid_pl_1\"", playlistsReq.url.queryParameter("equalTo"))
        assertEquals("valid_id_token", playlistsReq.url.queryParameter("auth"))
    }

    @Test
    fun firestoreFallback_loadsPlaylistsAndSongMasterWhenRtdbReturnsNull() = runBlocking {
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/members/uid_fs.json" -> jsonOk("""{"uid":"uid_fs","displayName":"FS","balance":10000,"purchasedSongIds":["song_fs_1"]}""")
                    "/admins/uid_fs.json" -> jsonOk("false")
                    "/playlists.json" -> jsonOk("null")
                    "/songs.json" -> jsonOk("""{"song_fs_1":{"id":"song_fs_1","title":"FS Track","artist":"Ken","price":10000,"isPaid":true,"demoUrl":"https://cdn.test/demo.mp3"}}""")
                    "/song_masters/song_fs_1.json" -> jsonOk("null")
                    else -> jsonOk("{}")
                }
            }
        }
        firestoreServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/documents:runQuery") && request.body?.utf8()?.contains("playlists") == true -> jsonOk(
                        """
                        [
                          {
                            "document": {
                              "name": "projects/newmusic-5625b/databases/(default)/documents/playlists/fs_pl_1",
                              "fields": {
                                "name": { "stringValue": "Firestore Mix" },
                                "userId": { "stringValue": "uid_fs" },
                                "songIds": {
                                  "arrayValue": {
                                    "values": [ { "stringValue": "song_fs_1" } ]
                                  }
                                }
                              }
                            }
                          }
                        ]
                        """.trimIndent()
                    )
                    path.endsWith("/documents/song_masters/song_fs_1") -> jsonOk(
                        """
                        {
                          "fields": {
                            "mp3_320Url": { "stringValue": "https://cdn.firestore/fs_320.mp3" },
                            "wavUrl": { "stringValue": "https://cdn.firestore/fs.wav" }
                          }
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        enqueueValidLogin(uid = "uid_fs")
        val playlists = client.getPlaylists().getOrThrow()
        assertEquals(1, playlists.size)
        assertEquals("Firestore Mix", playlists.first().name)
        assertEquals(1, playlists.first().songCount)

        val detail = client.getPlaylistDetail("fs_pl_1").getOrThrow()
        assertEquals(listOf("song_fs_1"), detail.songs.map { it.providerId })

        val streamUrl = client.getStreamingUrl("song_fs_1")
        assertEquals("https://cdn.firestore/fs_320.mp3", streamUrl)
    }

    @Test
    fun getStreamingUrl_unlocksMasterForFreePurchasedAndAdmin_andFallsBackForUnpurchased() = runBlocking {
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/members/uid_stream.json" -> jsonOk("""{"uid":"uid_stream","displayName":"User","balance":50000,"purchasedSongIds":["song_owned"]}""")
                    "/admins/uid_stream.json" -> jsonOk("false")
                    "/members/uid_admin.json" -> jsonOk("""{"uid":"uid_admin","displayName":"Admin","balance":0,"purchasedSongIds":[]}""")
                    "/admins/uid_admin.json" -> jsonOk("true")
                    "/songs.json" -> jsonOk(
                        """
                        {
                          "song_free": {
                            "id": "song_free",
                            "title": "Free Track",
                            "artist": "Artist A",
                            "price": 0,
                            "mp3_128Url": "https://cdn.test/free_128.mp3",
                            "demoUrl": "https://cdn.test/free_demo.mp3"
                          },
                          "song_owned": {
                            "id": "song_owned",
                            "title": "Owned Track",
                            "artist": "Artist B",
                            "price": 20000,
                            "isPaid": true,
                            "mp3_128Url": "https://cdn.test/owned_128.mp3",
                            "demoUrl": "https://cdn.test/owned_demo.mp3"
                          },
                          "song_locked": {
                            "id": "song_locked",
                            "title": "Locked Track",
                            "artist": "Artist C",
                            "price": 30000,
                            "isPaid": true,
                            "mp3_128Url": "https://cdn.test/locked_128.mp3",
                            "demoUrl": "https://cdn.test/locked_demo.mp3"
                          }
                        }
                        """.trimIndent()
                    )
                    "/song_masters/song_free.json" -> jsonOk("""{"mp3Url":"https://cdn.test/free_320.mp3","wavUrl":"https://cdn.test/free.wav"}""")
                    "/song_masters/song_owned.json" -> jsonOk("""{"mp3Url":"https://cdn.test/owned_320.mp3","wavUrl":"https://cdn.test/owned.wav"}""")
                    "/song_masters/song_locked.json" -> jsonOk("""{"mp3Url":"https://cdn.test/locked_320.mp3","wavUrl":"https://cdn.test/locked.wav"}""")
                    else -> jsonOk("{}")
                }
            }
        }

        enqueueValidLogin(uid = "uid_stream")

        assertEquals("https://cdn.test/free_320.mp3", client.getStreamingUrl("song_free"))
        assertEquals("https://cdn.test/owned_320.mp3", client.getStreamingUrl("song_owned"))
        assertEquals("https://cdn.test/locked_128.mp3", client.getStreamingUrl("song_locked"))

        // Admin role (admins/{uid}.json == true) unlocks unpurchased paid tracks
        enqueueValidLogin(uid = "uid_admin")
        assertTrue(client.isAdmin())
        assertEquals("https://cdn.test/locked_320.mp3", client.getStreamingUrl("song_locked"))

        // Switching to guest restricts access back to public 128k / demo only
        assertTrue(client.connectAsGuest().isSuccess)
        assertFalse(client.hasUserSession())
        assertEquals("https://cdn.test/locked_128.mp3", client.getStreamingUrl("song_locked"))
    }

    @Test
    fun purchaseSong_appliesVoucherPatchesMemberWritesPurchasesAndUnlocksMaster() = runBlocking {
        val recordedRequests = mutableListOf<Pair<String, String>>()
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val bodyStr = request.body?.utf8().orEmpty()
                recordedRequests.add(request.method to "$path|$bodyStr")
                return when (path) {
                    "/members/uid_buyer.json" -> {
                        if (request.method == "PATCH") {
                            jsonOk("""{"balance":40000,"purchasedSongIds":["song_vip"]}""")
                        } else {
                            jsonOk("""{"uid":"uid_buyer","balance":50000,"purchasedSongIds":[]}""")
                        }
                    }
                    "/admins/uid_buyer.json" -> jsonOk("false")
                    "/songs.json" -> jsonOk(
                        """
                        {
                          "song_vip": {
                            "id": "song_vip",
                            "title": "VIP Master",
                            "artist": "Ken",
                            "price": 20000,
                            "isPaid": true,
                            "currency": "VND",
                            "mp3_128Url": "https://cdn.test/vip_128.mp3"
                          }
                        }
                        """.trimIndent()
                    )
                    "/vouchers.json" -> jsonOk(
                        """
                        {
                          "v1": {
                            "id": "v1",
                            "code": "XUANK50",
                            "discountType": "percent",
                            "discountValue": 50,
                            "isActive": true
                          }
                        }
                        """.trimIndent()
                    )
                    "/user_purchases/uid_buyer/song_vip.json" -> jsonOk("""{"songId":"song_vip","price":10000}""")
                    "/song_masters/song_vip.json" -> jsonOk("""{"mp3Url":"https://cdn.test/vip_320.mp3","wavUrl":"https://cdn.test/vip.wav"}""")
                    else -> {
                        if (path.startsWith("/purchases/")) jsonOk("""{"status":"completed"}""")
                        else jsonOk("{}")
                    }
                }
            }
        }

        enqueueValidLogin(uid = "uid_buyer")

        val purchaseResult = client.purchaseSong("song_vip", voucherCode = "XUANK50")
        assertTrue("Purchase should succeed: ${purchaseResult.exceptionOrNull()}", purchaseResult.isSuccess)
        val record = purchaseResult.getOrThrow()
        assertEquals("song_vip", record.songId)
        assertEquals(20000.0, record.amount, 0.01)
        assertEquals(10000.0, record.discountAmount, 0.01)
        assertEquals(10000.0, record.finalAmount, 0.01)
        assertEquals("XUANK50", record.voucherCode)

        // Next stream resolution immediately uses unlocked master URL
        assertEquals("https://cdn.test/vip_320.mp3", client.getStreamingUrl("song_vip"))

        val patchReq = recordedRequests.firstOrNull { (m, info) ->
            m == "PATCH" && info.startsWith("/members/uid_buyer.json|")
        }
        assertTrue("PATCH members/{uid}.json must be called", patchReq != null)
        assertTrue(patchReq!!.second.contains("40000"))
        assertTrue(patchReq.second.contains("song_vip"))

        val userPurchasePut = recordedRequests.firstOrNull { (m, info) ->
            m == "PUT" && info.startsWith("/user_purchases/uid_buyer/song_vip.json|")
        }
        assertTrue("PUT user_purchases/{uid}/{songId}.json must be called", userPurchasePut != null)
        assertTrue(userPurchasePut!!.second.contains("10000"))

        val masterGet = recordedRequests.firstOrNull { (m, info) ->
            m == "GET" && info.startsWith("/song_masters/song_vip.json|")
        }
        assertTrue("GET song_masters/{songId}.json must unlock immediately", masterGet != null)
    }

    @Test
    fun personalPlaylists_strictlyIsolatesByCurrentMemberUid_evenIfBackendReturnsOtherUsers() = runBlocking {
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/members/uid_member_a.json" -> jsonOk("""{"uid":"uid_member_a","displayName":"Member A","balance":10000}""")
                    "/admins/uid_member_a.json" -> jsonOk("false")
                    "/playlists.json" -> jsonOk(
                        """
                        {
                          "pl_a": {
                            "id": "pl_a",
                            "userId": "uid_member_a",
                            "name": "Cua Thanh Vien A",
                            "isPublic": false,
                            "songIds": ["s1"]
                          },
                          "pl_b_public": {
                            "id": "pl_b_public",
                            "userId": "uid_member_b",
                            "name": "Cua Thanh Vien B",
                            "isPublic": true,
                            "songIds": ["s2"]
                          }
                        }
                        """.trimIndent()
                    )
                    else -> jsonOk("{}")
                }
            }
        }

        // Guest sees zero personal playlists
        assertTrue(client.connectAsGuest().isSuccess)
        assertTrue(client.getPlaylists().getOrThrow().isEmpty())

        // Signed-in Member A sees ONLY pl_a, never pl_b_public
        enqueueValidLogin(uid = "uid_member_a")
        val memberPlaylists = client.getPlaylists().getOrThrow()
        assertEquals(listOf("pl_a"), memberPlaylists.map { it.providerId })
        assertEquals("Cua Thanh Vien A", memberPlaylists.first().name)
    }

    @Test
    fun qualityToggle_320kbpsVsWav_appliesOnlyToPurchasedOrFreeMasterTracks() = runBlocking {
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/members/uid_q.json" -> jsonOk("""{"uid":"uid_q","displayName":"Q","balance":50000,"purchasedSongIds":["song_owned"]}""")
                    "/admins/uid_q.json" -> jsonOk("false")
                    "/songs.json" -> jsonOk(
                        """
                        {
                          "song_owned": {
                            "id": "song_owned",
                            "title": "Owned Track",
                            "artist": "Ken",
                            "price": 20000,
                            "isPaid": true,
                            "mp3_128Url": "https://cdn.test/owned_128.mp3",
                            "demoUrl": "https://cdn.test/owned_demo.mp3"
                          },
                          "song_unpaid": {
                            "id": "song_unpaid",
                            "title": "Unpaid Track",
                            "artist": "Ken",
                            "price": 30000,
                            "isPaid": true,
                            "mp3_128Url": "https://cdn.test/unpaid_128.mp3",
                            "demoUrl": "https://cdn.test/unpaid_demo.mp3"
                          }
                        }
                        """.trimIndent()
                    )
                    "/song_masters/song_owned.json" -> jsonOk("""{"mp3Url":"https://cdn.test/owned_320.mp3","wavUrl":"https://cdn.test/owned.wav"}""")
                    "/song_masters/song_unpaid.json" -> jsonOk("""{"mp3Url":"https://cdn.test/unpaid_320.mp3","wavUrl":"https://cdn.test/unpaid.wav"}""")
                    else -> jsonOk("{}")
                }
            }
        }

        enqueueValidLogin(uid = "uid_q")

        // HIGH -> 320kbps for purchased, still 128/demo for unpurchased
        AppSettings.getInstance(app).setStreamingQuality("HIGH")
        assertEquals("https://cdn.test/owned_320.mp3", client.getStreamingUrl("song_owned"))
        assertEquals("https://cdn.test/unpaid_128.mp3", client.getStreamingUrl("song_unpaid"))

        // LOSSLESS -> WAV for purchased, still 128/demo for unpurchased
        AppSettings.getInstance(app).setStreamingQuality("LOSSLESS")
        assertEquals("https://cdn.test/owned.wav", client.getStreamingUrl("song_owned"))
        assertEquals("https://cdn.test/unpaid_128.mp3", client.getStreamingUrl("song_unpaid"))
    }

    @Test
    fun accountScopedListeningStats_isolatedPerAccountAndRestoredAcrossReinstallViaCloud() = runBlocking {
        var storedListeningStatsJson = "{}"
        rtdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/members/uid_stats_1.json" && request.method == "PATCH" -> {
                        val body = org.json.JSONObject(request.body?.utf8().orEmpty())
                        if (body.has("listeningStats")) {
                            storedListeningStatsJson = body.getJSONObject("listeningStats").toString()
                        }
                        jsonOk("""{"ok":true}""")
                    }
                    path == "/members/uid_stats_1.json" && request.method == "GET" -> {
                        val memberPayload = org.json.JSONObject().apply {
                            put("uid", "uid_stats_1")
                            put("email", "user@xuanken.vn")
                            put("displayName", "Account One")
                            if (storedListeningStatsJson != "{}") {
                                put("listeningStats", org.json.JSONObject(storedListeningStatsJson))
                            }
                        }
                        jsonOk(memberPayload.toString())
                    }
                    else -> jsonOk("{}")
                }
            }
        }

        enqueueValidLogin(uid = "uid_stats_1")
        assertEquals("user_uid_stats_1", client.getStatsAccountKey())

        val statsRepo = chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository.getInstance(app)
        val settings = AppSettings.getInstance(app)
        statsRepo.setActiveAccount("user_uid_stats_1")
        statsRepo.clearHistory()
        settings.switchStatsAccount("user_uid_stats_1")

        val now = System.currentTimeMillis()
        statsRepo.recordSimplePlayback(
            songId = "CUSTOM::track_1",
            title = "3107",
            artist = "W/n",
            album = "3107 Album",
            genre = "Indie",
            durationMs = 4_200_000L, // 70 minutes (>1h), [now - 6_200_000 .. now - 2_000_000]
            timestamp = now - 2_000_000L
        )
        statsRepo.recordSimplePlayback(
            songId = "CUSTOM::track_2",
            title = "Sau Tat Ca",
            artist = "ERIK",
            album = "Hits",
            genre = "V-Pop",
            durationMs = 1_800_000L, // 30 minutes, [now - 1_800_000 .. now]
            timestamp = now
        )

        val beforeLogout = statsRepo.loadSummary(chromahub.rhythm.app.shared.data.repository.StatsTimeRange.ALL_TIME, nowMillis = now)
        assertEquals(6_000_000L, beforeLogout.totalDurationMs)
        assertEquals(2, beforeLogout.totalPlayCount)
        assertEquals(2, beforeLogout.uniqueArtists)

        // Sync account 1 snapshot to cloud
        val exported = statsRepo.exportCloudSnapshot(nowMillis = now)
        assertTrue(client.syncListeningStats(exported))

        // Switch to a second account & guest: must be isolated (0 duration, 0 plays)
        statsRepo.setActiveAccount("user_uid_stats_2")
        settings.switchStatsAccount("user_uid_stats_2")
        val account2Summary = statsRepo.loadSummary(chromahub.rhythm.app.shared.data.repository.StatsTimeRange.ALL_TIME, nowMillis = now)
        assertEquals(0L, account2Summary.totalDurationMs)
        assertEquals(0, account2Summary.totalPlayCount)

        // Simulate app reinstall on account 1 (wipe local history + baseline + clear prefs)
        statsRepo.setActiveAccount("user_uid_stats_1")
        statsRepo.clearHistory()
        settings.applyMergedStatsSnapshot("user_uid_stats_1", 0L, 0, 0, emptyMap())
        val wiped = statsRepo.loadSummary(chromahub.rhythm.app.shared.data.repository.StatsTimeRange.ALL_TIME, nowMillis = now)
        assertEquals(0L, wiped.totalDurationMs)

        // Log back into account 1 -> fetch cloud listening stats & merge
        val fetched = client.fetchListeningStats(forceRefresh = true)
        assertTrue(fetched != null)
        val restored = statsRepo.mergeCloudSnapshot("user_uid_stats_1", fetched!!, nowMillis = now)
        assertEquals(6_000_000L, restored.totalDurationMs)
        assertEquals(2, restored.totalPlayCount)
        assertEquals(2, restored.uniqueArtists)
    }

    private fun jsonOk(body: String): MockResponse {
        return MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()
    }

    private suspend fun enqueueValidLogin(uid: String) {
        authServer.enqueue(
            jsonOk(
                """
                {
                  "idToken": "valid_id_token",
                  "email": "user@xuanken.vn",
                  "refreshToken": "valid_refresh_token",
                  "expiresIn": "3600",
                  "localId": "$uid"
                }
                """.trimIndent()
            )
        )
        val res = client.login("user@xuanken.vn", "pass1234", saveCredentials = true)
        assertTrue("enqueueValidLogin failed: ${res.exceptionOrNull()?.stackTraceToString()}", res.isSuccess)
    }
}
