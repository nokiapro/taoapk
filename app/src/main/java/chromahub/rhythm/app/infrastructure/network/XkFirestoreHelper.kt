/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.network

import chromahub.rhythm.app.features.streaming.data.provider.ProviderPlaylist
import chromahub.rhythm.app.features.streaming.data.provider.ProviderSong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class FirestoreMemberRecord(
    val uid: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val balance: Double = 0.0,
    val role: String? = null,
    val purchasedSongIds: List<String> = emptyList(),
    val likedSongIds: List<String> = emptyList(),
    val likedAlbumIds: List<String> = emptyList(),
    val blockedSongIds: List<String> = emptyList(),
    val listeningStatsJson: String? = null
)

/**
 * Lightweight Firestore REST fallback for project `newmusic-5625b` so that
 * playlists, member profiles, purchases, and `song_masters` stored in Firestore
 * sync seamlessly alongside Firebase RTDB.
 */
object XkFirestoreHelper {
    const val DEFAULT_BASE_URL =
        "https://firestore.googleapis.com/v1/projects/newmusic-5625b/databases/(default)/documents"

    @Volatile
    internal var baseUrlOverride: String? = null

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String {
        val raw = (baseUrlOverride ?: DEFAULT_BASE_URL).trimEnd('/')
        return if (raw.endsWith("/documents")) raw else "$raw/documents"
    }

    fun fetchUserPlaylists(
        uid: String,
        idToken: String,
        isAdmin: Boolean,
        songsLookup: Map<String, ProviderSong> = emptyMap()
    ): List<Pair<ProviderPlaylist, List<String>>> {
        if (idToken.isBlank()) return emptyList()
        val structuredQuery = JSONObject().apply {
            put("from", JSONArray().put(JSONObject().put("collectionId", "playlists")))
            if (!isAdmin && uid.isNotBlank()) {
                put(
                    "where",
                    JSONObject().put(
                        "fieldFilter",
                        JSONObject().apply {
                            put("field", JSONObject().put("fieldPath", "userId"))
                            put("op", "EQUAL")
                            put("value", JSONObject().put("stringValue", uid))
                        }
                    )
                )
            }
        }
        val payload = JSONObject().put("structuredQuery", structuredQuery).toString()
        val bodyStr = executePostJson("${baseUrl()}:runQuery", idToken, payload)
        if (!bodyStr.trim().startsWith("[")) return emptyList()

        val result = mutableListOf<Pair<ProviderPlaylist, List<String>>>()
        val arr = runCatching { JSONArray(bodyStr) }.getOrNull() ?: return emptyList()
        for (i in 0 until arr.length()) {
            val doc = arr.optJSONObject(i)?.optJSONObject("document") ?: continue
            val fields = doc.optJSONObject("fields") ?: continue
            val docId = doc.optString("name").substringAfterLast("/")
            val id = fields.fsString("id") ?: docId
            val title = fields.fsString("title") ?: fields.fsString("name") ?: "Playlist"
            val desc = fields.fsString("description")
            val songIds = fields.fsStringArray("songIds")
            val matchedSongs = songIds.mapNotNull { songsLookup[it] }
            val cover = fields.fsString("coverImage")
                ?: fields.fsString("coverUrl")
                ?: matchedSongs.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
            val curator = fields.fsString("curator")
                ?: fields.fsString("userName")
                ?: "XuanKen Music"

            val playlist = ProviderPlaylist(
                providerId = id,
                name = title,
                description = desc,
                artworkUrl = cover,
                songCount = maxOf(songIds.size, matchedSongs.size),
                owner = curator,
                isPublic = fields.fsBoolean("isPublic") ?: false
            )
            result.add(playlist to songIds)
        }
        return result
    }

    fun fetchUserPurchases(uid: String, idToken: String): List<String> {
        if (uid.isBlank() || idToken.isBlank()) return emptyList()
        val structuredQuery = JSONObject().apply {
            put("from", JSONArray().put(JSONObject().put("collectionId", "purchases")))
            put(
                "where",
                JSONObject().put(
                    "fieldFilter",
                    JSONObject().apply {
                        put("field", JSONObject().put("fieldPath", "userId"))
                        put("op", "EQUAL")
                        put("value", JSONObject().put("stringValue", uid))
                    }
                )
            )
        }
        val payload = JSONObject().put("structuredQuery", structuredQuery).toString()
        val bodyStr = executePostJson("${baseUrl()}:runQuery", idToken, payload)
        if (!bodyStr.trim().startsWith("[")) return emptyList()

        val ids = linkedSetOf<String>()
        val arr = runCatching { JSONArray(bodyStr) }.getOrNull() ?: return emptyList()
        for (i in 0 until arr.length()) {
            val fields = arr.optJSONObject(i)?.optJSONObject("document")?.optJSONObject("fields") ?: continue
            fields.fsString("songId")?.let { ids.add(it) }
        }
        return ids.toList()
    }

    fun fetchMemberProfile(
        uid: String,
        idToken: String,
        fallbackEmail: String,
        fallbackName: String
    ): FirestoreMemberRecord? {
        if (uid.isBlank() || idToken.isBlank()) return null
        val bodyStr = executeGet("${baseUrl()}/members/$uid", idToken)
        val doc = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return null
        val fields = doc.optJSONObject("fields") ?: return null
        return FirestoreMemberRecord(
            uid = fields.fsString("uid") ?: uid,
            email = fields.fsString("email") ?: fallbackEmail,
            displayName = fields.fsString("displayName") ?: fallbackName,
            avatarUrl = fields.fsString("avatarUrl"),
            balance = fields.fsDouble("balance"),
            role = fields.fsString("role"),
            purchasedSongIds = fields.fsStringArray("purchasedSongIds"),
            likedSongIds = fields.fsStringArray("likedSongIds"),
            likedAlbumIds = fields.fsStringArray("likedAlbumIds"),
            blockedSongIds = fields.fsStringArray("blockedSongIds"),
            listeningStatsJson = fields.fsString("listeningStatsJson")
                ?: fields.fsMapToJsonString("listeningStats")
        )
    }

    fun patchMemberListeningStats(
        uid: String,
        idToken: String,
        statsJson: String
    ): Boolean {
        if (uid.isBlank() || idToken.isBlank() || statsJson.isBlank()) return false
        return runCatching {
            val payload = JSONObject().put(
                "fields",
                JSONObject().put(
                    "listeningStatsJson",
                    JSONObject().put("stringValue", statsJson)
                )
            ).toString()
            val req = Request.Builder()
                .url("${baseUrl()}/members/$uid?updateMask.fieldPaths=listeningStatsJson")
                .header("Authorization", "Bearer $idToken")
                .patch(payload.toRequestBody(jsonType))
                .build()
            XkMusicApiService.okHttpClient.newCall(req).execute().use { res ->
                res.isSuccessful
            }
        }.getOrDefault(false)
    }

    fun fetchSongMasterUrl(songId: String, idToken: String, qualityPref: String = "HIGH"): String? {
        if (songId.isBlank() || idToken.isBlank()) return null
        val bodyStr = executeGet("${baseUrl()}/song_masters/$songId", idToken)
        val doc = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return null
        val fields = doc.optJSONObject("fields") ?: return null
        val keys = when (qualityPref.uppercase()) {
            "LOSSLESS" -> listOf(
                "wavUrl", "wav_url", "flacUrl", "flac_url", "mp3Url", "mp3_url",
                "mp3_320Url", "masterUrl", "fullUrl", "streamUrl", "audioUrl", "url"
            )
            else -> listOf(
                "mp3Url", "mp3_url", "mp3_320Url", "masterUrl", "fullUrl",
                "wavUrl", "wav_url", "flacUrl", "streamUrl", "audioUrl", "url"
            )
        }
        for (key in keys) {
            val value = fields.fsString(key)?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun executeGet(url: String, idToken: String): String {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()
            XkMusicApiService.okHttpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body.string() else ""
            }
        }.getOrDefault("")
    }

    private fun executePostJson(url: String, idToken: String, payload: String): String {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $idToken")
                .post(payload.toRequestBody(jsonType))
                .build()
            XkMusicApiService.okHttpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body.string() else ""
            }
        }.getOrDefault("")
    }

    private fun JSONObject.fsString(key: String): String? {
        val valObj = optJSONObject(key) ?: return null
        val s = valObj.optString("stringValue", "")
        return s.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.fsDouble(key: String): Double {
        val valObj = optJSONObject(key) ?: return 0.0
        if (valObj.has("doubleValue")) return valObj.optDouble("doubleValue", 0.0)
        if (valObj.has("integerValue")) return valObj.optString("integerValue").toDoubleOrNull() ?: 0.0
        return 0.0
    }

    private fun JSONObject.fsBoolean(key: String): Boolean? {
        val valObj = optJSONObject(key) ?: return null
        return if (valObj.has("booleanValue")) valObj.optBoolean("booleanValue") else null
    }

    private fun JSONObject.fsStringArray(key: String): List<String> {
        val arr = optJSONObject(key)?.optJSONObject("arrayValue")?.optJSONArray("values") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)?.optString("stringValue", "")
                if (!item.isNullOrBlank()) add(item)
            }
        }
    }

    private fun JSONObject.fsMapToJsonString(key: String): String? {
        val mapFields = optJSONObject(key)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: return null
        return fsFieldsToJsonObject(mapFields).toString()
    }

    private fun fsFieldsToJsonObject(fields: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val valueObj = fields.optJSONObject(k) ?: continue
            fsValueToAny(valueObj)?.let { out.put(k, it) }
        }
        return out
    }

    private fun fsValueToAny(valueObj: JSONObject): Any? {
        return when {
            valueObj.has("stringValue") -> valueObj.optString("stringValue")
            valueObj.has("integerValue") -> valueObj.optString("integerValue").toLongOrNull() ?: 0L
            valueObj.has("doubleValue") -> valueObj.optDouble("doubleValue", 0.0)
            valueObj.has("booleanValue") -> valueObj.optBoolean("booleanValue")
            valueObj.has("mapValue") -> {
                val mapFields = valueObj.optJSONObject("mapValue")?.optJSONObject("fields") ?: JSONObject()
                fsFieldsToJsonObject(mapFields)
            }
            valueObj.has("arrayValue") -> {
                val values = valueObj.optJSONObject("arrayValue")?.optJSONArray("values") ?: JSONArray()
                val arr = JSONArray()
                for (i in 0 until values.length()) {
                    values.optJSONObject(i)?.let { elem -> fsValueToAny(elem)?.let { arr.put(it) } }
                }
                arr
            }
            else -> null
        }
    }
}
