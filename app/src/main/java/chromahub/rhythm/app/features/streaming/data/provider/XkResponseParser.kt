/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import chromahub.rhythm.app.shared.data.model.LyricsData
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.NumberFormat
import java.util.Locale

internal data class ParsedStreamInfo(
    val url: String?,
    val isDemo: Boolean = false
)

internal data class ParsedAuthSession(
    val idToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val uid: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val balance: Double? = null,
    val role: String? = null,
    val plan: String = "",
    val purchasedIds: Set<String> = emptySet(),
    val likedIds: Set<String> = emptySet()
)

internal data class ParsedMemberState(
    val profile: CustomUserProfile,
    val purchasedSongIds: Set<String>,
    val likedSongIds: Set<String>,
    val likedAlbumIds: Set<String>,
    val blockedSongIds: Set<String>,
    val rawPurchasedValue: Any? = null,
    val rawLikedValue: Any? = null,
    val rawLikedAlbumValue: Any? = null,
    val rawBlockedValue: Any? = null
)

internal data class ParsedAlbumEntry(
    val album: ProviderAlbum,
    val embeddedSongs: List<ProviderSong>,
    val embeddedSongIds: List<String>
)

internal data class ParsedPlaylistEntry(
    val playlist: ProviderPlaylist,
    val userId: String?,
    val songIds: List<String>,
    val embeddedSongs: List<ProviderSong>
)

internal data class ParsedSongEntry(
    val song: ProviderSong,
    val price: Double,
    val currency: String,
    val isPaid: Boolean,
    val isHidden: Boolean,
    val playCount: Long,
    val rawLyrics: String?,
    val lrcUrl: String?,
    val createdAtMs: Long
)

/**
 * Unified JSON parser for both primary `https://xkmu.xuanken.name.vn/api/v1/`
 * responses and Firebase RTDB (`songs.json`, `exclusive_albums.json`, `playlists.json`,
 * `weekly_plays.json`, `site_settings.json`, `artist_bios.json`, `vouchers.json`,
 * `members/{uid}.json`, `song_masters/{id}.json`, `user_purchases/{uid}.json`).
 */
internal object XkResponseParser {
    private const val BASE_HOST = "https://xkmu.xuanken.name.vn"
    private val DEMO_URL_REGEX =
        Regex("""(^|[/?&_=-])(demo|preview|sample)($|[/?&_=.])""", RegexOption.IGNORE_CASE)

    fun looksLikeDemoUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return DEMO_URL_REGEX.containsMatchIn(url)
    }

    fun normalizeUrl(raw: String?): String? {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$BASE_HOST$clean"
            else -> "$BASE_HOST/$clean"
        }
    }

    fun isJsonBody(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    fun parseErrorMessage(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return null
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val errObj = root.optJSONObject("error")
        return errObj?.firstString("message", "error", "detail")
            ?: root.firstString("message", "error", "detail", "msg")
    }

    fun parseAuthSession(
        rawJson: String,
        fallbackEmail: String = "",
        fallbackDisplayName: String = ""
    ): ParsedAuthSession? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val container = root.optJSONObject("data") ?: root
        val idToken = container.firstString("idToken", "id_token", "accessToken", "access_token", "token")
            ?: return null
        val refreshToken = container.firstString("refreshToken", "refresh_token").orEmpty()
        val expiresInSec = container.firstLong("expiresIn", "expires_in")
        val expiresAtMs = if (expiresInSec > 0L) {
            System.currentTimeMillis() + expiresInSec * 1000L
        } else {
            val rawExp = container.firstLong("expiresAt", "expires_at")
            if (rawExp > 0L) rawExp else System.currentTimeMillis() + 3_600_000L
        }
        val userObj = container.optJSONObject("user") ?: container
        val uid = userObj.firstString("uid", "localId", "user_id", "userId", "id")
            ?: container.firstString("localId", "user_id", "userId", "uid")
            ?: ""
        val email = userObj.firstString("email") ?: fallbackEmail
        val displayName = userObj.firstString("displayName", "display_name", "name", "username")
            ?: fallbackDisplayName.ifBlank { email.substringBefore("@").ifBlank { "Member" } }
        val avatar = normalizeUrl(userObj.firstString("avatarUrl", "avatar_url", "photoUrl", "avatar"))
        val balance = userObj.firstDoubleOrNull("balance", "wallet_balance", "coins")
        val role = userObj.firstString("role", "tier") ?: "user"
        val plan = userObj.firstString("plan", "tier").orEmpty()
        val purchasedIds = extractIdList(
            userObj,
            "purchasedSongIds", "purchased_song_ids", "unlockedSongIds",
            "unlocked_song_ids", "ownedSongIds", "boughtSongIds", "purchasedSongs"
        ).toSet()
        val likedIds = extractIdList(
            userObj,
            "likedSongIds", "liked_song_ids", "likedSongs", "likes"
        ).toSet()
        return ParsedAuthSession(
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAtMs,
            uid = uid,
            email = email,
            displayName = displayName,
            avatarUrl = avatar,
            balance = balance,
            role = role,
            plan = plan,
            purchasedIds = purchasedIds,
            likedIds = likedIds
        )
    }

    fun parseMemberState(
        rawJson: String,
        fallbackUid: String = "",
        fallbackEmail: String = "",
        fallbackDisplayName: String = ""
    ): ParsedMemberState? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val obj = root.optJSONObject("user")
            ?: root.optJSONObject("profile")
            ?: root.optJSONObject("data")
            ?: root.optJSONArray("users")?.optJSONObject(0)
            ?: root

        val uid = obj.firstString("uid", "localId", "userId", "id") ?: fallbackUid
        val email = obj.firstString("email") ?: fallbackEmail
        val displayName = obj.firstString("displayName", "display_name", "name", "username")
            ?: fallbackDisplayName.ifBlank { email.substringBefore("@").ifBlank { "Member" } }
        val username = obj.firstString("username", "displayName", "name") ?: displayName

        val purchasedIds = extractIdList(
            obj,
            "purchasedSongIds", "purchased_song_ids", "unlockedSongIds",
            "unlocked_song_ids", "ownedSongIds", "owned_song_ids",
            "boughtSongIds", "purchasedSongs", "purchases"
        ).toSet()
        val likedSongIds = extractIdList(
            obj,
            "likedSongIds", "liked_song_ids", "likedSongs", "likes"
        ).toSet()
        val likedAlbumIds = extractIdList(
            obj,
            "likedAlbumIds", "liked_album_ids", "likedAlbums"
        ).toSet()
        val blockedSongIds = extractIdList(
            obj,
            "blockedSongIds", "blocked_song_ids", "blocks", "blockedSongs"
        ).toSet()

        return ParsedMemberState(
            profile = CustomUserProfile(
                id = uid,
                username = username,
                email = email,
                displayName = displayName,
                avatarUrl = normalizeUrl(obj.firstString("avatarUrl", "avatar_url", "photoUrl", "avatar")),
                role = obj.firstString("role", "tier") ?: "user",
                plan = obj.firstString("plan", "tier").orEmpty(),
                balance = obj.firstDoubleOrNull("balance", "wallet_balance", "coins"),
                currency = obj.firstString("currency") ?: "VND"
            ),
            purchasedSongIds = purchasedIds,
            likedSongIds = likedSongIds,
            likedAlbumIds = likedAlbumIds,
            blockedSongIds = blockedSongIds,
            rawPurchasedValue = obj.opt("purchasedSongIds"),
            rawLikedValue = obj.opt("likedSongIds"),
            rawLikedAlbumValue = obj.opt("likedAlbumIds"),
            rawBlockedValue = obj.opt("blockedSongIds")
        )
    }

    fun parseSongsEntries(rawJson: String): List<ParsedSongEntry> {
        val root = parseTokener(rawJson) ?: return emptyList()
        val array = extractArray(root, listOf("songs", "tracks", "data", "items", "results"))
        if (array != null) {
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(mapSongEntry(obj))
                }
            }
        }
        if (root is JSONObject) {
            return mapObjectValues(root) { key, obj -> mapSongEntry(obj, fallbackId = key) }
        }
        return emptyList()
    }

    fun parseSongEntry(rawJson: String): ParsedSongEntry? {
        val root = parseTokener(rawJson) ?: return null
        val obj = extractObject(root, listOf("song", "track", "data", "item", "result")) ?: return null
        return mapSongEntry(obj)
    }

    private fun mapSongEntry(obj: JSONObject, fallbackId: String? = null): ParsedSongEntry {
        val artistObj = obj.optJSONObject("artist") ?: obj.optJSONArray("artists")?.optJSONObject(0)
        val albumObj = obj.optJSONObject("album")
        val genreObj = obj.optJSONObject("genre")

        val artistName = obj.firstString("artist_name", "artistName", "artists", "singer", "composer", "author")
            ?: artistObj?.firstString("name", "title")
            ?: (obj.opt("artist") as? String)?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist"

        val albumTitle = obj.firstString("album_title", "albumTitle", "album_name")
            ?: albumObj?.firstString("title", "name")
            ?: (obj.opt("album") as? String)?.takeIf { it.isNotBlank() }
            ?: "Single"

        val albumId = obj.firstString("album_id", "albumId")
            ?: albumObj?.firstString("id", "_id", "slug")

        val genre = obj.firstString("genre_name", "genreName", "category")
            ?: genreObj?.firstString("name", "title")
            ?: (obj.opt("genre") as? String)?.takeIf { it.isNotBlank() }

        val coverUrl = normalizeUrl(
            obj.firstString(
                "coverImage", "cover_url", "coverUrl", "cover",
                "artwork_url", "artworkUrl", "artwork", "thumbnail", "thumb",
                "image_url", "imageUrl", "image", "poster"
            ) ?: albumObj?.firstString("coverImage", "cover_url", "coverUrl", "cover", "image")
        )

        val publicStream = normalizeUrl(
            obj.firstString(
                "mp3_128Url", "mp3128Url", "audio_url", "audioUrl",
                "stream_url", "streamUrl", "file_url", "fileUrl",
                "music_url", "src", "url", "audio", "track_url",
                "mp3Url", "mp3_url", "masterUrl", "master_url", "fullUrl", "full_url"
            )
        )
        val preview = normalizeUrl(
            obj.firstString("demoUrl", "demo_url", "previewUrl", "preview_url")
        ) ?: publicStream

        val durationMs = normalizeDurationMs(
            obj.opt("durationMs"),
            obj.opt("duration_seconds") ?: obj.opt("durationSeconds"),
            obj.opt("duration") ?: obj.opt("length")
        )

        val price = (parsePriceDouble(
            obj.firstAny(
                "price", "songPrice", "song_price", "trackPrice", "track_price",
                "masterPrice", "cost", "gia", "amount", "fee"
            )
        ) ?: 0.0).coerceAtLeast(0.0)
        val explicitPaidRaw = obj.firstAny(
            "paid", "is_paid", "isPaid", "is_premium", "isPremium",
            "vip", "isVip", "is_vip", "requirePurchase", "requiresPurchase",
            "isLocked", "locked"
        )
        val freeFlagRaw = obj.firstAny("isFree", "free", "is_free")
        val tierLabel = obj.firstString("access", "tier", "type", "license")?.lowercase()
        val tierIndicatesPaid = tierLabel in setOf("paid", "premium", "vip", "pro", "master", "purchase", "commercial")
        val isPaid = price > 0.0 ||
            (explicitPaidRaw != null && parseBoolean(explicitPaidRaw)) ||
            (freeFlagRaw != null && !parseBoolean(freeFlagRaw)) ||
            tierIndicatesPaid
        val isHidden = parseBoolean(obj.opt("isHidden") ?: obj.opt("hidden"))

        val songId = obj.firstString("id", "_id", "song_id", "slug")
            ?: fallbackId
            ?: obj.hashCode().toString()
        val lrcRaw = obj.firstString("lrcRaw", "lyrics", "lyric", "lrc")
        val lrcUrl = normalizeUrl(obj.firstString("lrcUrl", "lrc_url"))

        val resolvedCurrency = obj.firstString("currency") ?: "VND"
        val resolvedPurchased = !isPaid ||
            parseBoolean(obj.opt("isPurchased") ?: obj.opt("is_purchased") ?: obj.opt("purchased") ?: obj.opt("unlocked"))

        val rawTimestamp = obj.firstAny(
            "addedAt", "added_at",
            "createdAt", "created_at",
            "publishedAt", "published_at",
            "uploadedAt", "uploaded_at",
            "dateAdded", "date_added",
            "createdDate", "releaseDate",
            "timestamp", "time",
            "updatedAt", "updated_at"
        )
        val createdAtMs = parseTimestampMs(rawTimestamp)
            .takeIf { it > 0L }
            ?: decodeFirebasePushIdTimestampMs(songId)

        val song = ProviderSong(
            providerId = songId,
            title = obj.firstString("title", "name", "song_name") ?: "Bài hát chưa đặt tên",
            artist = artistName,
            album = albumTitle,
            durationMs = durationMs,
            artworkUrl = coverUrl,
            albumId = albumId,
            trackNumber = obj.firstIntOrNull("trackNumber", "track_number", "track"),
            year = obj.firstIntOrNull("year", "releaseYear", "release_year"),
            genre = genre,
            bitrate = obj.firstIntOrNull("bitrate"),
            codec = obj.firstString("codec"),
            streamUrl = publicStream ?: preview,
            previewUrl = preview,
            price = price,
            isPaidTrack = isPaid,
            isPurchased = resolvedPurchased,
            currency = resolvedCurrency,
            createdAtMs = createdAtMs
        )

        return ParsedSongEntry(
            song = song,
            price = price,
            currency = resolvedCurrency,
            isPaid = isPaid,
            isHidden = isHidden,
            playCount = obj.firstLong("playCount", "play_count", "plays", "playsCount", "views", "listens"),
            rawLyrics = lrcRaw,
            lrcUrl = lrcUrl,
            createdAtMs = createdAtMs
        )
    }

    fun parseAlbumsEntries(rawJson: String): List<ParsedAlbumEntry> {
        val root = parseTokener(rawJson) ?: return emptyList()
        val array = extractArray(root, listOf("albums", "exclusiveAlbums", "data", "items", "results"))
        if (array != null) {
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(mapAlbumEntry(obj))
                }
            }
        }
        if (root is JSONObject) {
            return mapObjectValues(root) { key, obj -> mapAlbumEntry(obj, fallbackId = key) }
        }
        return emptyList()
    }

    fun parseAlbumDetail(rawJson: String): ParsedAlbumEntry? {
        val root = parseTokener(rawJson) ?: return null
        val obj = extractObject(root, listOf("album", "data", "item", "result")) ?: return null
        val base = mapAlbumEntry(obj)
        val songs = if (base.embeddedSongs.isNotEmpty()) {
            base.embeddedSongs
        } else if (root is JSONObject) {
            val rootSongs = root.optJSONArray("songs") ?: root.optJSONArray("tracks")
            if (rootSongs != null) parseSongsEntries(rootSongs.toString()).map { it.song } else emptyList()
        } else {
            emptyList()
        }
        return base.copy(
            album = base.album.copy(songCount = maxOf(base.album.songCount, songs.size)),
            embeddedSongs = songs
        )
    }

    private fun mapAlbumEntry(obj: JSONObject, fallbackId: String? = null): ParsedAlbumEntry {
        val artistObj = obj.optJSONObject("artist")
        val artistName = obj.firstString("artist_name", "artistName", "artists")
            ?: artistObj?.firstString("name", "title")
            ?: (obj.opt("artist") as? String)?.takeIf { it.isNotBlank() }
            ?: "Various Artists"

        val albumId = obj.firstString("id", "_id", "album_id", "slug")
            ?: fallbackId
            ?: obj.hashCode().toString()
        val albumTitle = obj.firstString("title", "name", "album_name") ?: "Album"
        val cover = normalizeUrl(
            obj.firstString(
                "coverImage", "cover_url", "coverUrl", "cover",
                "artworkUrl", "artwork_url", "thumbnail", "image_url", "image"
            )
        )

        val songsList = mutableListOf<ProviderSong>()
        val songsArr = obj.optJSONArray("songs") ?: obj.optJSONArray("tracks")
        if (songsArr != null) {
            songsList.addAll(parseSongsEntries(songsArr.toString()).map { it.song })
        } else {
            val songsObj = obj.optJSONObject("songs") ?: obj.optJSONObject("tracks")
            if (songsObj != null) {
                songsList.addAll(
                    mapObjectValues(songsObj) { k, sObj -> mapSongEntry(sObj, fallbackId = k).song }
                )
            }
        }
        val embeddedIds = extractIdList(obj, "songIds", "song_ids", "songs", "tracks")
        val hydrated = songsList.map { s ->
            s.copy(
                albumId = s.albumId ?: albumId,
                album = if (s.album == "Single" || s.album.isBlank()) albumTitle else s.album,
                artworkUrl = s.artworkUrl ?: cover
            )
        }
        val year = obj.firstIntOrNull("year", "releaseYear", "release_year")

        return ParsedAlbumEntry(
            album = ProviderAlbum(
                providerId = albumId,
                title = albumTitle,
                artist = artistName,
                artworkUrl = cover,
                songCount = maxOf(
                    obj.firstIntOrNull("songCount", "song_count", "trackCount", "track_count") ?: 0,
                    hydrated.size,
                    embeddedIds.size
                ),
                year = year,
                description = obj.firstString("description", "desc", "about")
            ),
            embeddedSongs = hydrated,
            embeddedSongIds = (embeddedIds + hydrated.map { it.providerId }).distinct()
        )
    }

    fun parseArtistsList(rawJson: String): List<ProviderArtist> {
        val root = parseTokener(rawJson) ?: return emptyList()
        val array = extractArray(root, listOf("artists", "data", "items", "results"))
        if (array != null) {
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(mapArtist(obj))
                }
            }
        }
        if (root is JSONObject) {
            return mapObjectValues(root) { key, obj -> mapArtist(obj, fallbackId = key) }
        }
        return emptyList()
    }

    fun parseArtistDetail(rawJson: String): CustomArtistDetail? {
        val root = parseTokener(rawJson) ?: return null
        val obj = extractObject(root, listOf("artist", "data", "item", "result")) ?: return null
        val artist = mapArtist(obj)
        val rootObj = root as? JSONObject
        val songsArr = obj.optJSONArray("songs") ?: obj.optJSONArray("top_songs")
            ?: rootObj?.optJSONArray("songs") ?: rootObj?.optJSONArray("top_songs")
        val topTracks = if (songsArr != null) parseSongsEntries(songsArr.toString()).map { it.song } else emptyList()

        val albumsArr = obj.optJSONArray("albums") ?: rootObj?.optJSONArray("albums")
        val albums = if (albumsArr != null) {
            parseAlbumsEntries(albumsArr.toString()).map { it.album }
        } else {
            emptyList()
        }
        return CustomArtistDetail(
            artist = artist.copy(
                songCount = maxOf(artist.songCount, topTracks.size),
                albumCount = maxOf(artist.albumCount, albums.size)
            ),
            topTracks = topTracks,
            albums = albums
        )
    }

    private fun mapArtist(obj: JSONObject, fallbackId: String? = null): ProviderArtist {
        val id = obj.firstString("id", "_id", "artist_id", "slug") ?: fallbackId ?: obj.hashCode().toString()
        val name = obj.firstString("name", "artistName", "artist_name", "title") ?: id
        return ProviderArtist(
            providerId = id,
            name = name,
            artworkUrl = normalizeUrl(
                obj.firstString("avatarUrl", "avatar_url", "avatar", "artworkUrl", "imageUrl", "coverImage", "image", "photoUrl")
            ),
            songCount = obj.firstIntOrNull("songCount", "song_count", "trackCount", "track_count") ?: 0,
            albumCount = obj.firstIntOrNull("albumCount", "album_count") ?: 0,
            description = obj.firstString("bio", "biography", "description", "about")
        )
    }

    fun parsePlaylistsEntries(
        rawJson: String,
        songsLookup: Map<String, ProviderSong> = emptyMap()
    ): List<ParsedPlaylistEntry> {
        val root = parseTokener(rawJson) ?: return emptyList()
        val array = extractArray(root, listOf("playlists", "data", "items", "results"))
        if (array != null) {
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(mapPlaylistEntry(obj, songsLookup = songsLookup))
                }
            }
        }
        if (root is JSONObject) {
            return mapObjectValues(root) { key, obj ->
                mapPlaylistEntry(obj, fallbackId = key, songsLookup = songsLookup)
            }
        }
        return emptyList()
    }

    fun parsePlaylistDetail(
        rawJson: String,
        songsLookup: Map<String, ProviderSong> = emptyMap()
    ): ParsedPlaylistEntry? {
        val root = parseTokener(rawJson) ?: return null
        val obj = extractObject(root, listOf("playlist", "data", "item", "result")) ?: return null
        val base = mapPlaylistEntry(obj, songsLookup = songsLookup)
        val extraSongs = if (base.embeddedSongs.isEmpty() && root is JSONObject) {
            val arr = root.optJSONArray("songs") ?: root.optJSONArray("tracks")
            if (arr != null) parseSongsEntries(arr.toString()).map { it.song } else emptyList()
        } else {
            base.embeddedSongs
        }
        val finalIds = (base.songIds + extraSongs.map { it.providerId }).distinct()
        return base.copy(
            playlist = base.playlist.copy(songCount = maxOf(base.playlist.songCount, finalIds.size, extraSongs.size)),
            songIds = finalIds,
            embeddedSongs = extraSongs
        )
    }

    private fun mapPlaylistEntry(
        obj: JSONObject,
        fallbackId: String? = null,
        songsLookup: Map<String, ProviderSong> = emptyMap()
    ): ParsedPlaylistEntry {
        val songIdsList = extractIdList(obj, "songIds", "song_ids", "trackIds", "tracks_ids", "songs")
        val embeddedSongs = mutableListOf<ProviderSong>()
        val songsArr = obj.optJSONArray("songs") ?: obj.optJSONArray("tracks")
        if (songsArr != null) {
            embeddedSongs.addAll(parseSongsEntries(songsArr.toString()).map { it.song })
        } else {
            val songsObj = obj.optJSONObject("songs") ?: obj.optJSONObject("tracks")
            if (songsObj != null) {
                embeddedSongs.addAll(
                    mapObjectValues(songsObj) { k, sObj -> mapSongEntry(sObj, fallbackId = k).song }
                )
            } else if (songsLookup.isNotEmpty() && songIdsList.isNotEmpty()) {
                songIdsList.forEach { sid -> songsLookup[sid]?.let { embeddedSongs.add(it) } }
            }
        }
        val mergedIds = (songIdsList + embeddedSongs.map { it.providerId }).distinct()
        val id = obj.firstString("id", "_id", "playlist_id", "slug") ?: fallbackId ?: obj.hashCode().toString()
        val cover = normalizeUrl(
            obj.firstString("coverImage", "cover_url", "coverUrl", "cover", "artworkUrl", "thumbnail", "image_url", "image")
        ) ?: embeddedSongs.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl

        return ParsedPlaylistEntry(
            playlist = ProviderPlaylist(
                providerId = id,
                name = obj.firstString("title", "name", "playlist_name") ?: "Playlist",
                description = obj.firstString("description", "desc", "subtitle"),
                artworkUrl = cover,
                songCount = maxOf(
                    obj.firstIntOrNull("song_count", "songCount", "tracks_count", "total") ?: 0,
                    mergedIds.size,
                    embeddedSongs.size
                ),
                owner = obj.firstString("curator", "owner", "author", "owner_name", "userName", "user_name") ?: "XuanKen Music",
                isPublic = parseBoolean(obj.opt("isPublic") ?: obj.opt("public") ?: true)
            ),
            userId = obj.firstString("userId", "user_id", "curatorId", "ownerId", "owner_id", "uid"),
            songIds = mergedIds,
            embeddedSongs = embeddedSongs
        )
    }

    fun parseStreamInfo(rawJson: String, qualityPref: String = "HIGH"): ParsedStreamInfo? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val container = root.optJSONObject("data") ?: root.optJSONObject("result") ?: root
        val streamUrlsObj = container.optJSONObject("streamUrls") ?: root.optJSONObject("streamUrls")

        val masterKeys = when (qualityPref.uppercase()) {
            "LOSSLESS" -> arrayOf(
                "wavUrl", "wav_url", "flacUrl", "flac_url", "best", "masterUrl", "master_url",
                "fullUrl", "full_url", "signedUrl", "signed_url", "mp3Url", "mp3_url",
                "mp3_320", "mp3_320Url", "mp3320Url"
            )
            else -> arrayOf(
                "best", "mp3_320", "mp3_320Url", "mp3320Url", "mp3Url", "mp3_url",
                "masterUrl", "master_url", "fullUrl", "full_url", "signedUrl", "signed_url",
                "wavUrl", "wav_url", "flacUrl", "flac_url"
            )
        }
        val streamKeys = arrayOf(
            "streamUrl", "stream_url", "audioUrl", "audio_url",
            "fileUrl", "file_url", "url", "src"
        )
        val demoKeys = arrayOf(
            "mp3_128", "mp3_128Url", "mp3128Url", "demo", "demoUrl", "demo_url",
            "previewUrl", "preview_url"
        )

        val masterUrl = normalizeUrl(
            streamUrlsObj?.firstString(*masterKeys)
                ?: container.firstString(*masterKeys)
                ?: root.firstString(*masterKeys)
        )
        val streamUrl = normalizeUrl(
            streamUrlsObj?.firstString(*streamKeys)
                ?: container.firstString(*streamKeys)
                ?: root.firstString(*streamKeys)
        )
        val demoUrl = normalizeUrl(
            streamUrlsObj?.firstString(*demoKeys)
                ?: container.firstString(*demoKeys)
                ?: root.firstString(*demoKeys)
        )

        val accessMode = (container.firstString("access", "type", "mode", "tier")
            ?: root.firstString("access", "type", "mode", "tier"))
            .orEmpty()
            .lowercase()

        val explicitDemo = hasTrueFlag(container, root, "isDemo", "is_demo", "isPreview", "is_preview", "demo") ||
            hasFalseFlag(container, root, "isFull", "is_full", "full", "unlocked", "purchased") ||
            accessMode == "demo" || accessMode == "preview"

        if (masterUrl != null && !looksLikeDemoUrl(masterUrl)) {
            return ParsedStreamInfo(url = masterUrl, isDemo = false)
        }
        if (streamUrl != null) {
            val isStreamDemo = explicitDemo || (demoUrl != null && streamUrl == demoUrl) || looksLikeDemoUrl(streamUrl)
            return ParsedStreamInfo(url = streamUrl, isDemo = isStreamDemo)
        }
        if (masterUrl != null) {
            return ParsedStreamInfo(url = masterUrl, isDemo = explicitDemo || looksLikeDemoUrl(masterUrl))
        }
        if (demoUrl != null) {
            return ParsedStreamInfo(url = demoUrl, isDemo = true)
        }
        return null
    }

    fun parseDownloadUrl(rawJson: String): String? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val container = root.optJSONObject("data") ?: root.optJSONObject("result") ?: root
        val streamUrlsObj = container.optJSONObject("streamUrls") ?: root.optJSONObject("streamUrls")
        val keys = arrayOf(
            "downloadUrl", "download_url", "masterUrl", "master_url",
            "fullUrl", "full_url", "signedUrl", "signed_url", "best",
            "fileUrl", "file_url", "mp3_320", "mp3_320Url", "mp3Url", "mp3_url",
            "wavUrl", "wav_url", "flacUrl", "flac_url", "streamUrl", "stream_url", "url"
        )
        return normalizeUrl(
            streamUrlsObj?.firstString(*keys)
                ?: container.firstString(*keys)
                ?: root.firstString(*keys)
        )
    }

    fun parseHomePayload(rawJson: String): CustomHomePayload? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val container = root.optJSONObject("data") ?: root
        val popularArr = container.optJSONArray("popularSongs")
            ?: container.optJSONArray("popular_songs")
            ?: container.optJSONArray("songs")
            ?: container.optJSONArray("tracks")
            ?: container.optJSONArray("trending")
        val newestArr = container.optJSONArray("latestSongs")
            ?: container.optJSONArray("latest_songs")
            ?: container.optJSONArray("newestSongs")
            ?: container.optJSONArray("newest_songs")
            ?: container.optJSONArray("newReleasesSongs")
            ?: container.optJSONArray("newReleases")
            ?: container.optJSONArray("newSongs")
            ?: container.optJSONArray("new_songs")
            ?: container.optJSONArray("recentSongs")
            ?: container.optJSONArray("recent_songs")
        val featuredArr = container.optJSONArray("featuredSongs")
            ?: container.optJSONArray("featured_songs")
            ?: container.optJSONArray("featured")

        val albumsArr = container.optJSONArray("featuredAlbums")
            ?: container.optJSONArray("albums")
            ?: container.optJSONArray("exclusiveAlbums")
        val artistsArr = container.optJSONArray("artists")
        val playlistsArr = container.optJSONArray("playlists")

        val popular = popularArr?.let { parseSongsEntries(it.toString()).map { e -> e.song } }.orEmpty()
        val rawNewest = newestArr?.let { parseSongsEntries(it.toString()).map { e -> e.song } }.orEmpty()
        val newest = if (rawNewest.any { it.createdAtMs > 0L }) {
            rawNewest.withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<ProviderSong>> { it.value.createdAtMs }
                        .thenBy { it.index }
                )
                .map { it.value }
        } else {
            rawNewest
        }
        val featured = featuredArr?.let { parseSongsEntries(it.toString()).map { e -> e.song } }.orEmpty()
        val albums = albumsArr?.let { parseAlbumsEntries(it.toString()).map { a -> a.album } }.orEmpty()
        val artists = artistsArr?.let { parseArtistsList(it.toString()) }.orEmpty()
        val lookup = (popular + newest + featured).associateBy { it.providerId }
        val playlists = playlistsArr?.let { parsePlaylistsEntries(it.toString(), lookup).map { p -> p.playlist } }.orEmpty()

        val recommendations = (featured + popular).distinctBy { it.providerId }
        if (recommendations.isEmpty() && newest.isEmpty() && albums.isEmpty() && playlists.isEmpty()) {
            return null
        }
        return CustomHomePayload(
            recommendations = recommendations,
            newestSongs = newest.ifEmpty { recommendations },
            charts = popular.ifEmpty { recommendations },
            newReleases = albums,
            featuredPlaylists = playlists,
            artists = artists
        )
    }

    fun parseSearchPayload(rawJson: String): CustomSearchPayload? {
        val root = parseTokener(rawJson) ?: return null
        if (root is JSONArray) {
            return CustomSearchPayload(songs = parseSongsEntries(root.toString()).map { it.song })
        }
        if (root !is JSONObject) return null
        val container = root.optJSONObject("data") ?: root.optJSONObject("results") ?: root
        val songs = (container.optJSONArray("songs") ?: container.optJSONArray("tracks"))
            ?.let { parseSongsEntries(it.toString()).map { e -> e.song } }.orEmpty()
        val lookup = songs.associateBy { it.providerId }
        val albums = container.optJSONArray("albums")
            ?.let { parseAlbumsEntries(it.toString()).map { a -> a.album } }.orEmpty()
        val artists = container.optJSONArray("artists")
            ?.let { parseArtistsList(it.toString()) }.orEmpty()
        val playlists = container.optJSONArray("playlists")
            ?.let { parsePlaylistsEntries(it.toString(), lookup).map { p -> p.playlist } }.orEmpty()
        return CustomSearchPayload(
            songs = songs,
            albums = albums,
            artists = artists,
            playlists = playlists
        )
    }

    fun parseChartsSongs(rawJson: String): List<ProviderSong> {
        val root = parseTokener(rawJson) ?: return emptyList()
        if (root is JSONArray) {
            return parseSongsEntries(root.toString()).map { it.song }
        }
        if (root !is JSONObject) return emptyList()
        val container = root.optJSONObject("data") ?: root
        val directArr = container.optJSONArray("songs")
            ?: container.optJSONArray("tracks")
            ?: container.optJSONArray("top")
            ?: container.optJSONArray("trending")
            ?: container.optJSONArray("items")
            ?: container.optJSONArray("charts")
            ?: root.optJSONArray("data")
        if (directArr != null && directArr.length() > 0) {
            val firstObj = directArr.optJSONObject(0)
            if (firstObj != null && (firstObj.has("songs") || firstObj.has("tracks"))) {
                val collected = mutableListOf<ProviderSong>()
                for (i in 0 until directArr.length()) {
                    val sec = directArr.optJSONObject(i) ?: continue
                    val arr = sec.optJSONArray("songs") ?: sec.optJSONArray("tracks") ?: continue
                    collected.addAll(parseSongsEntries(arr.toString()).map { it.song })
                }
                if (collected.isNotEmpty()) return collected.distinctBy { it.providerId }
            } else {
                return parseSongsEntries(directArr.toString()).map { it.song }
            }
        }
        val keys = container.keys()
        val collected = mutableListOf<ProviderSong>()
        while (keys.hasNext()) {
            val arr = container.optJSONArray(keys.next()) ?: continue
            collected.addAll(parseSongsEntries(arr.toString()).map { it.song })
        }
        return collected.distinctBy { it.providerId }
    }

    fun parseWeeklyPlays(rawJson: String, targetWeekKey: String? = null): Map<String, Long> {
        val root = parseTokener(rawJson) ?: return emptyMap()
        val counts = linkedMapOf<String, Long>()

        fun accumulateEntry(key: String, value: Any?) {
            val cleanId = key.trim()
            if (cleanId.isBlank()) return
            when (value) {
                is Number -> {
                    val v = value.toLong()
                    if (v > 0L) counts[cleanId] = maxOf(counts[cleanId] ?: 0L, v)
                }
                is String -> {
                    val v = value.trim().toLongOrNull()
                    if (v != null && v > 0L) counts[cleanId] = maxOf(counts[cleanId] ?: 0L, v)
                }
                is JSONObject -> {
                    val sid = value.firstString("songId", "song_id", "id") ?: cleanId
                    val v = value.firstLong("plays", "playCount", "play_count", "weeklyPlays", "weekly_plays", "count", "views")
                    if (v > 0L) counts[sid] = maxOf(counts[sid] ?: 0L, v)
                }
            }
        }

        if (root is JSONArray) {
            for (i in 0 until root.length()) {
                val obj = root.optJSONObject(i) ?: continue
                val sid = obj.firstString("songId", "song_id", "id") ?: continue
                val v = obj.firstLong("plays", "playCount", "play_count", "weeklyPlays", "weekly_plays", "count", "views")
                if (v > 0L) counts[sid] = maxOf(counts[sid] ?: 0L, v)
            }
            return counts
        }

        if (root !is JSONObject) return emptyMap()

        // Check if organized by weekKey (e.g., {"2026-W47": {"song1": 12}})
        val weekScopedObj = targetWeekKey?.let { weekKey ->
            root.optJSONObject(weekKey)
                ?: root.optJSONObject("weeks")?.optJSONObject(weekKey)
                ?: root.optJSONObject("weekly")?.optJSONObject(weekKey)
        }
        if (weekScopedObj != null) {
            val keys = weekScopedObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                accumulateEntry(k, weekScopedObj.opt(k))
            }
            return counts
        }

        val container = root.optJSONObject("data")
            ?: root.optJSONObject("songs")
            ?: root.optJSONObject("weekly")
            ?: root

        val arr = container.optJSONArray("songs")
            ?: container.optJSONArray("tracks")
            ?: container.optJSONArray("items")
            ?: container.optJSONArray("charts")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sid = obj.firstString("songId", "song_id", "id") ?: continue
                val v = obj.firstLong("plays", "playCount", "play_count", "weeklyPlays", "weekly_plays", "count", "views")
                if (v > 0L) counts[sid] = maxOf(counts[sid] ?: 0L, v)
            }
            return counts
        }

        val keys = container.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            // Skip unrelated week buckets when targetWeekKey is known and keys look like YYYY-Www
            if (targetWeekKey != null && Regex("^\\d{4}-W\\d{1,2}$").matches(k) && k != targetWeekKey) {
                continue
            }
            val value = container.opt(k)
            if (value is JSONObject && targetWeekKey != null && Regex("^\\d{4}-W\\d{1,2}$").matches(k)) {
                val innerKeys = value.keys()
                while (innerKeys.hasNext()) {
                    val ik = innerKeys.next()
                    accumulateEntry(ik, value.opt(ik))
                }
            } else {
                accumulateEntry(k, value)
            }
        }
        return counts
    }

    fun parseSiteInfo(rawJson: String): CustomSiteInfo? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val obj = root.optJSONObject("site") ?: root.optJSONObject("data") ?: root
        return CustomSiteInfo(
            siteName = obj.firstString("site_name", "siteName", "name", "title") ?: "XuanKen Music",
            description = obj.firstString("description", "tagline", "slogan", "subtitle", "about").orEmpty(),
            announcement = obj.firstString("announcement", "notice").orEmpty(),
            version = obj.firstString("version").orEmpty(),
            maintenanceMode = parseBoolean(obj.opt("maintenanceMode") ?: obj.opt("maintenance_mode")),
            bannerUrl = normalizeUrl(
                obj.firstString("banner_url", "bannerUrl", "banner", "hero_image", "cover", "coverImage")
            )
        )
    }

    fun parseUserSettings(rawJson: String): CustomUserSettings? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val obj = root.optJSONObject("settings") ?: root.optJSONObject("data") ?: root
        return CustomUserSettings(
            audioQuality = obj.firstString("audioQuality", "audio_quality", "quality") ?: "HIGH",
            pushNewMusic = if (obj.has("pushNewMusic")) parseBoolean(obj.opt("pushNewMusic")) else true,
            privateSession = parseBoolean(obj.opt("privateSession") ?: obj.opt("private_session")),
            explicitFilter = parseBoolean(obj.opt("explicitFilter") ?: obj.opt("explicit_filter")),
            language = obj.firstString("language", "lang") ?: "vi"
        )
    }

    fun parseBlockedSongs(rawJson: String, songsLookup: Map<String, ProviderSong> = emptyMap()): List<BlockedSongItem> {
        val root = parseTokener(rawJson) ?: return emptyList()
        if (root is JSONArray) {
            return buildList {
                for (i in 0 until root.length()) {
                    when (val item = root.opt(i)) {
                        is String -> item.trim().takeIf { it.isNotEmpty() }?.let { sid ->
                            val s = songsLookup[sid]
                            add(
                                BlockedSongItem(
                                    songId = sid,
                                    title = s?.title ?: "Song $sid",
                                    artist = s?.artist ?: "",
                                    album = s?.album.orEmpty(),
                                    artworkUrl = s?.artworkUrl
                                )
                            )
                        }
                        is JSONObject -> {
                            val sid = item.firstString("songId", "song_id", "id") ?: continue
                            val songObj = item.optJSONObject("song")
                            val s = songsLookup[sid]
                            add(
                                BlockedSongItem(
                                    songId = sid,
                                    title = item.firstString("title", "songTitle")
                                        ?: songObj?.firstString("title") ?: s?.title ?: "Song $sid",
                                    artist = item.firstString("artist", "artistName")
                                        ?: songObj?.firstString("artistName", "artist") ?: s?.artist.orEmpty(),
                                    album = item.firstString("album") ?: s?.album.orEmpty(),
                                    artworkUrl = normalizeUrl(item.firstString("artworkUrl", "coverUrl"))
                                        ?: s?.artworkUrl,
                                    reason = item.firstString("reason"),
                                    blockedAt = item.firstString("blockedAt", "createdAt")
                                )
                            )
                        }
                    }
                }
            }
        }
        if (root is JSONObject) {
            val container = root.optJSONObject("data") ?: root
            val arr = container.optJSONArray("blocks")
                ?: container.optJSONArray("blockedSongs")
                ?: container.optJSONArray("items")
                ?: container.optJSONArray("songs")
            if (arr != null) return parseBlockedSongs(arr.toString(), songsLookup)
            val ids = extractIdList(container, "blocks", "blockedSongIds", "blocked_song_ids", "songs")
            return ids.map { sid ->
                val s = songsLookup[sid]
                BlockedSongItem(
                    songId = sid,
                    title = s?.title ?: "Song $sid",
                    artist = s?.artist.orEmpty(),
                    album = s?.album.orEmpty(),
                    artworkUrl = s?.artworkUrl
                )
            }
        }
        return emptyList()
    }

    fun parseVouchersList(rawJson: String): List<VoucherInfo> {
        val root = parseTokener(rawJson) ?: return emptyList()
        val objects = mutableListOf<Pair<String?, JSONObject>>()
        val array = extractArray(root, listOf("vouchers", "data", "items", "results"))
        if (array != null) {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { objects.add(null to it) }
            }
        } else if (root is JSONObject) {
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                root.optJSONObject(k)?.let { objects.add(k to it) }
            }
        }
        return objects.mapNotNull { (key, obj) ->
            val code = (obj.firstString("code", "voucherCode") ?: key)?.trim()?.uppercase()
                ?: return@mapNotNull null
            val isActive = if (obj.has("isActive")) parseBoolean(obj.opt("isActive"))
            else if (obj.has("active")) parseBoolean(obj.opt("active"))
            else if (obj.has("is_active")) parseBoolean(obj.opt("is_active"))
            else true
            VoucherInfo(
                id = obj.firstString("id") ?: key ?: code,
                code = code,
                title = obj.firstString("title", "name") ?: code,
                description = obj.firstString("description", "desc").orEmpty(),
                discountType = obj.firstString("discountType", "discount_type", "type") ?: "percent",
                discountValue = obj.firstDoubleOrNull("discountValue", "discount_value", "value", "amount") ?: 0.0,
                minOrderAmount = obj.firstDoubleOrNull("minOrderAmount", "min_order_amount") ?: 0.0,
                expiresAt = obj.firstString("expiresAt", "validUntil", "expires_at"),
                isActive = isActive
            )
        }
    }

    fun parseVoucherValidation(
        rawJson: String,
        fallbackCode: String,
        originalPrice: Double,
        currency: String = "VND"
    ): VoucherValidationResult? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val container = root.optJSONObject("voucher") ?: root.optJSONObject("data") ?: root
        val valid = when {
            container.has("valid") -> parseBoolean(container.opt("valid"))
            root.has("valid") -> parseBoolean(root.opt("valid"))
            root.has("success") -> parseBoolean(root.opt("success"))
            else -> true
        }
        val discountType = container.firstString("discountType", "discount_type", "type") ?: "percent"
        val discountValue = container.firstDoubleOrNull("discountValue", "discount_value", "value", "amount") ?: 0.0
        val computedDiscount = when (discountType.lowercase()) {
            "fixed", "amount", "flat" -> discountValue
            else -> originalPrice * (discountValue / 100.0)
        }.coerceIn(0.0, originalPrice)
        val computedFinal = (originalPrice - computedDiscount).coerceAtLeast(0.0)
        val apiFinal = container.firstDoubleOrNull("finalPrice", "final_price", "discountedPrice", "discounted_price")
        val finalPrice = apiFinal ?: computedFinal
        val discountAmount = (originalPrice - finalPrice).coerceAtLeast(0.0)

        return VoucherValidationResult(
            valid = valid,
            code = container.firstString("code") ?: fallbackCode,
            message = root.firstString("message", "msg") ?: container.firstString("message").orEmpty(),
            originalPrice = originalPrice,
            discountAmount = discountAmount,
            finalPrice = finalPrice,
            currency = container.firstString("currency") ?: currency
        )
    }

    fun parsePurchasesList(
        rawJson: String,
        songsLookup: Map<String, ProviderSong> = emptyMap()
    ): List<PurchaseRecord> {
        val root = parseTokener(rawJson) ?: return emptyList()
        val array = extractArray(root, listOf("purchases", "items", "data", "results"))
        if (array != null) {
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(mapPurchase(obj, fallbackId = "p_$i", songsLookup = songsLookup))
                }
            }.sortedByDescending { it.purchasedAt.orEmpty() }
        }
        if (root is JSONObject) {
            return mapObjectValues(root) { key, obj ->
                mapPurchase(obj, fallbackId = key, songsLookup = songsLookup)
            }.sortedByDescending { it.purchasedAt.orEmpty() }
        }
        return emptyList()
    }

    fun parseSinglePurchase(
        rawJson: String,
        fallbackSongId: String,
        songsLookup: Map<String, ProviderSong> = emptyMap()
    ): PurchaseRecord? {
        val root = parseTokener(rawJson) as? JSONObject ?: return null
        val obj = root.optJSONObject("purchase") ?: root.optJSONObject("data") ?: root
        if (obj.optString("songId").isBlank() && obj.optString("song_id").isBlank() && !obj.has("finalAmount") && !obj.has("price")) {
            return null
        }
        return mapPurchase(obj, fallbackId = fallbackSongId, songsLookup = songsLookup)
    }

    private fun mapPurchase(
        obj: JSONObject,
        fallbackId: String,
        songsLookup: Map<String, ProviderSong>
    ): PurchaseRecord {
        val songObj = obj.optJSONObject("song")
        val songId = obj.firstString("songId", "song_id")
            ?: songObj?.firstString("id")
            ?: fallbackId
        val song = songsLookup[songId]
        val finalPrice = obj.firstDoubleOrNull("finalAmount", "final_amount", "finalPrice", "price", "paidAmount", "amount") ?: 0.0
        val amount = obj.firstDoubleOrNull("amount", "originalPrice") ?: finalPrice
        val discount = obj.firstDoubleOrNull("discountAmount", "discount_amount") ?: (amount - finalPrice).coerceAtLeast(0.0)
        return PurchaseRecord(
            id = obj.firstString("purchaseId", "purchase_id", "id") ?: fallbackId,
            songId = songId,
            songTitle = obj.firstString("songTitle", "song_title", "title")
                ?: songObj?.firstString("title")
                ?: song?.title
                ?: songId,
            artist = obj.firstString("artist", "artistName", "artist_name", "artists")
                ?: songObj?.firstString("artistName", "artist", "artists")
                ?: song?.artist
                ?: "XuanKen Music",
            artworkUrl = normalizeUrl(obj.firstString("coverImage", "coverUrl", "artworkUrl"))
                ?: song?.artworkUrl,
            amount = amount,
            discountAmount = discount,
            finalAmount = finalPrice,
            currency = obj.firstString("currency") ?: "VND",
            voucherCode = obj.firstString("voucherCode", "voucher_code"),
            status = obj.firstString("status") ?: "completed",
            purchasedAt = obj.firstString("purchasedAt", "purchased_at", "timestamp", "createdAt")
        )
    }

    fun parseArtistBios(rawJson: String): Map<String, Pair<String?, String?>> {
        val root = parseTokener(rawJson) as? JSONObject ?: return emptyMap()
        val result = mutableMapOf<String, Pair<String?, String?>>()

        fun collect(node: JSONObject, prefix: String?) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val fullKey = if (prefix.isNullOrBlank()) k else "$prefix/$k"
                when (val v = node.opt(k)) {
                    is String -> result[fullKey] = v to null
                    is JSONObject -> {
                        val bio = v.firstString("bio", "biography", "description", "about")
                        val avatar = normalizeUrl(
                            v.firstString("avatarUrl", "avatar_url", "avatar", "artworkUrl", "coverImage", "image", "photoUrl")
                        )
                        val explicitName = v.firstString("name", "artistName")
                        val looksLikeLeaf = bio != null || avatar != null || explicitName != null
                        if (looksLikeLeaf) {
                            result[explicitName ?: fullKey] = bio to avatar
                        } else {
                            // Firebase RTDB stores keys containing '/' (e.g., "W/n") as nested nodes ("W" -> "n")
                            collect(v, fullKey)
                        }
                    }
                }
            }
        }

        collect(root, prefix = null)
        return result
    }

    fun extractLikedSongIdsFromNode(rawJson: String): List<String> {
        val root = parseTokener(rawJson) ?: return emptyList()
        if (root is JSONArray) {
            return buildList {
                for (i in 0 until root.length()) {
                    root.optString(i).takeIf { it.isNotBlank() && it != "null" }?.let { add(it) }
                }
            }
        }
        if (root is JSONObject) {
            return extractIdList(root, "likedSongIds", "liked_song_ids", "songs", "ids")
        }
        return emptyList()
    }

    fun parseLyricsText(rawLyrics: String?): LyricsData? {
        val clean = rawLyrics?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val isSynced = clean.contains("[") && Regex("""\[\d{1,2}:\d{2}""").containsMatchIn(clean)
        return if (isSynced) {
            LyricsData(syncedLyrics = clean, plainLyrics = clean)
        } else {
            LyricsData(syncedLyrics = null, plainLyrics = clean)
        }
    }

    fun formatVnd(amount: Long): String {
        val nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
        return "${nf.format(amount)}đ"
    }

    internal fun parseTokener(raw: String): Any? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
    }

    private fun extractArray(root: Any, candidateKeys: List<String>): JSONArray? {
        if (root is JSONArray) return root
        if (root !is JSONObject) return null
        for (key in candidateKeys) {
            root.optJSONArray(key)?.let { return it }
        }
        val dataObj = root.optJSONObject("data") ?: root.optJSONObject("result")
        if (dataObj != null) {
            for (key in candidateKeys) {
                dataObj.optJSONArray(key)?.let { return it }
            }
        }
        return null
    }

    private fun extractObject(root: Any, candidateKeys: List<String>): JSONObject? {
        if (root is JSONArray) return root.optJSONObject(0)
        if (root !is JSONObject) return null
        for (key in candidateKeys) {
            root.optJSONObject(key)?.let { return it }
        }
        return root
    }

    private fun <T> mapObjectValues(obj: JSONObject, mapper: (String, JSONObject) -> T): List<T> {
        val result = mutableListOf<T>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val item = obj.optJSONObject(k) ?: continue
            result.add(mapper(k, item))
        }
        return result
    }

    internal fun extractIdList(obj: JSONObject, vararg keys: String): List<String> {
        val result = linkedSetOf<String>()
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            when (val raw = obj.opt(k)) {
                is JSONArray -> {
                    for (i in 0 until raw.length()) {
                        when (val item = raw.opt(i)) {
                            is String -> if (item.isNotBlank() && item != "null") result.add(item.trim())
                            is JSONObject -> item.firstString("songId", "song_id", "id")?.let { result.add(it) }
                        }
                    }
                }
                is JSONObject -> {
                    val subKeys = raw.keys()
                    while (subKeys.hasNext()) {
                        val sk = subKeys.next()
                        val sv = raw.opt(sk)
                        if (sv == false || sv == JSONObject.NULL) continue
                        if (sv is JSONObject) {
                            val idFromObj = sv.firstString("songId", "song_id", "id") ?: sk
                            if (idFromObj.isNotBlank()) result.add(idFromObj)
                        } else if (sk.isNotBlank()) {
                            result.add(sk)
                        }
                    }
                }
                is String -> {
                    raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { result.add(it) }
                }
            }
        }
        return result.toList()
    }

    private fun normalizeDurationMs(rawMs: Any?, rawSec: Any?, rawGeneric: Any?): Long {
        parseMsValue(rawMs, assumeSeconds = false)?.takeIf { it > 0L }?.let { return it }
        parseMsValue(rawSec, assumeSeconds = true)?.takeIf { it > 0L }?.let { return it }
        val generic = parseMsValue(rawGeneric, assumeSeconds = false) ?: 0L
        return if (generic in 1L..9999L) generic * 1000L else generic
    }

    private fun parseMsValue(value: Any?, assumeSeconds: Boolean): Long? {
        if (value == null || value == JSONObject.NULL) return null
        return when (value) {
            is Number -> {
                val d = value.toDouble()
                if (d <= 0.0) 0L else if (assumeSeconds) (d * 1000.0).toLong() else d.toLong()
            }
            is String -> {
                val s = value.trim()
                if (s.contains(":")) {
                    val parts = s.split(":").mapNotNull { it.toLongOrNull() }
                    when (parts.size) {
                        2 -> (parts[0] * 60 + parts[1]) * 1000L
                        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                        else -> null
                    }
                } else {
                    val d = s.toDoubleOrNull() ?: return null
                    if (d <= 0.0) 0L else if (assumeSeconds) (d * 1000.0).toLong() else d.toLong()
                }
            }
            else -> null
        }
    }

    internal fun parseBoolean(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    private fun hasTrueFlag(primary: JSONObject, secondary: JSONObject, vararg keys: String): Boolean {
        for (k in keys) {
            if ((primary.has(k) && !primary.isNull(k) && parseBoolean(primary.opt(k))) ||
                (secondary.has(k) && !secondary.isNull(k) && parseBoolean(secondary.opt(k)))
            ) {
                return true
            }
        }
        return false
    }

    private fun hasFalseFlag(primary: JSONObject, secondary: JSONObject, vararg keys: String): Boolean {
        for (k in keys) {
            if ((primary.has(k) && !primary.isNull(k) && !parseBoolean(primary.opt(k))) ||
                (secondary.has(k) && !secondary.isNull(k) && !parseBoolean(secondary.opt(k)))
            ) {
                return true
            }
        }
        return false
    }

    private fun parseTimestampMs(value: Any?): Long {
        if (value == null || value == JSONObject.NULL) return 0L
        return when (value) {
            is Number -> {
                val raw = value.toLong()
                if (raw in 1_000_000_000L..99_999_999_999L) raw * 1000L else raw.coerceAtLeast(0L)
            }
            is String -> {
                val clean = value.trim()
                if (clean.isEmpty()) return 0L
                clean.toLongOrNull()?.let { raw ->
                    return if (raw in 1_000_000_000L..99_999_999_999L) raw * 1000L else raw.coerceAtLeast(0L)
                }
                runCatching { java.time.Instant.parse(clean).toEpochMilli() }
                    .getOrNull()?.takeIf { it > 0L }?.let { return it }
                runCatching { java.time.OffsetDateTime.parse(clean).toInstant().toEpochMilli() }
                    .getOrNull()?.takeIf { it > 0L }?.let { return it }
                val patterns = listOf(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd"
                )
                for (pattern in patterns) {
                    val parsed = runCatching {
                        java.text.SimpleDateFormat(pattern, Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.parse(clean)?.time
                    }.getOrNull()
                    if (parsed != null && parsed > 0L) return parsed
                }
                0L
            }
            else -> 0L
        }
    }

    internal fun decodeFirebasePushIdTimestampMs(id: String?): Long {
        if (id.isNullOrBlank()) return 0L
        val clean = id.trim()
        if (clean.length < 20 || !clean.startsWith("-")) return 0L
        val alphabet = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"
        var timestamp = 0L
        for (i in 0 until 8) {
            val idx = alphabet.indexOf(clean[i])
            if (idx < 0) return 0L
            timestamp = timestamp * 64L + idx
        }
        return if (timestamp > 1_262_304_000_000L) timestamp else 0L
    }

    internal fun parsePriceDouble(value: Any?): Double? {
        if (value == null || value == JSONObject.NULL) return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> {
                val raw = value.trim()
                if (raw.isBlank() || raw.equals("free", true) || raw.equals("miễn phí", true)) {
                    return 0.0
                }
                val cleaned = raw.replace(Regex("[₫đ$€£]|vnd|usd|vnđ", RegexOption.IGNORE_CASE), "")
                    .replace(" ", "")
                    .trim()
                if (cleaned.isBlank()) return null
                when {
                    Regex("^\\d{1,3}(\\.\\d{3})+$").matches(cleaned) ->
                        cleaned.replace(".", "").toDoubleOrNull()
                    Regex("^\\d{1,3}(,\\d{3})+$").matches(cleaned) ->
                        cleaned.replace(",", "").toDoubleOrNull()
                    cleaned.contains('.') && cleaned.contains(',') ->
                        cleaned.replace(".", "").replace(",", ".").toDoubleOrNull()
                    else -> cleaned.replace(",", ".").toDoubleOrNull()
                }
            }
            else -> null
        }
    }

    internal fun JSONObject.firstAny(vararg keys: String): Any? {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = opt(k)
            if (v != null && v != JSONObject.NULL) return v
        }
        return null
    }

    internal fun JSONObject.firstString(vararg keys: String): String? {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = opt(k)
            if (v is String && v.isNotBlank() && v != "null") return v.trim()
            if (v is Number) return v.toString()
        }
        return null
    }

    internal fun JSONObject.firstLong(vararg keys: String): Long {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = opt(k)
            when (v) {
                is Number -> return v.toLong()
                is String -> v.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    internal fun JSONObject.firstDoubleOrNull(vararg keys: String): Double? {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            parsePriceDouble(opt(k))?.let { return it }
        }
        return null
    }

    internal fun JSONObject.firstIntOrNull(vararg keys: String): Int? {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = opt(k)
            when (v) {
                is Number -> return v.toInt()
                is String -> v.toIntOrNull()?.let { return it }
            }
        }
        return null
    }
}
