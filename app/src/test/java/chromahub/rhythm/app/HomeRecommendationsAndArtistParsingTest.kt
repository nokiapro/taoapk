/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app

import chromahub.rhythm.app.core.domain.model.SourceType
import chromahub.rhythm.app.features.streaming.data.provider.XkResponseParser
import chromahub.rhythm.app.features.streaming.data.repository.CatalogCacheCodec
import chromahub.rhythm.app.features.streaming.data.repository.StreamingCatalogCache
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.features.streaming.presentation.model.StreamingHomeHelpers
import chromahub.rhythm.app.util.ArtistSeparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class HomeRecommendationsAndArtistParsingTest {

    @Test
    fun artistSeparator_preservesSlashedArtistNamesAndSplitsCollaborations() {
        assertEquals(
            listOf("W/n"),
            ArtistSeparator.splitArtistNames("W/n")
        )
        assertEquals(
            listOf("AC/DC"),
            ArtistSeparator.splitArtistNames("AC/DC")
        )
        assertEquals(
            listOf("W/n", "Sơn Tùng"),
            ArtistSeparator.splitArtistNames("W/n / Sơn Tùng")
        )
        assertEquals(
            listOf("W/n", "Binz"),
            ArtistSeparator.splitArtistNames("W/n; Binz")
        )
    }

    @Test
    fun xkResponseParser_flattensNestedRtdbArtistBiosWithSlash() {
        val raw = """
            {
              "W": {
                "n": {
                  "bio": "Indie producer",
                  "avatarUrl": "https://cdn.test/wn.jpg"
                }
              },
              "Sơn Tùng": {
                "bio": "V-Pop",
                "avatarUrl": "https://cdn.test/st.jpg"
              }
            }
        """.trimIndent()

        val parsed = XkResponseParser.parseArtistBios(raw)
        assertEquals("Indie producer" to "https://cdn.test/wn.jpg", parsed["W/n"])
        assertEquals("V-Pop" to "https://cdn.test/st.jpg", parsed["Sơn Tùng"])
    }

    @Test
    fun streamingHomeHelpers_newBadgeShownWithinSevenDaysOnly() {
        val now = 1_764_000_000_000L
        val sevenDays = StreamingHomeHelpers.SEVEN_DAYS_MILLIS

        assertFalse(StreamingHomeHelpers.isTrackNewWithinDays(0L, nowMs = now))
        assertTrue(StreamingHomeHelpers.isTrackNewWithinDays(now, nowMs = now))
        assertTrue(StreamingHomeHelpers.isTrackNewWithinDays(now - 3L * 86_400_000L, nowMs = now))
        assertTrue(StreamingHomeHelpers.isTrackNewWithinDays(now - sevenDays, nowMs = now))
        assertFalse(StreamingHomeHelpers.isTrackNewWithinDays(now - sevenDays - 1L, nowMs = now))
    }

    @Test
    fun streamingHomeHelpers_dailyRecommendationsDeterministicPerMemberAndChangesDaily() {
        val pool = (1..25).map { idx ->
            StreamingSong(
                id = "CUSTOM::song_$idx",
                title = "Track $idx",
                artist = "Artist $idx",
                album = "Album $idx",
                duration = 200_000L,
                artworkUri = "https://cdn.test/$idx.jpg",
                sourceType = SourceType.CUSTOM_API,
                streamingUrl = "https://cdn.test/$idx.mp3",
                previewUrl = "https://cdn.test/$idx.mp3",
                externalId = "song_$idx"
            )
        }

        val memberADay1First = StreamingHomeHelpers.pickDailyRecommendations(
            songs = pool,
            memberKey = "member_a",
            epochDay = 20400L,
            limit = 10
        ).map { it.id }
        val memberADay1Second = StreamingHomeHelpers.pickDailyRecommendations(
            songs = pool.reversed(),
            memberKey = "member_a",
            epochDay = 20400L,
            limit = 10
        ).map { it.id }

        assertEquals(10, memberADay1First.size)
        assertEquals(10, memberADay1First.distinct().size)
        assertEquals(memberADay1First, memberADay1Second)

        val memberADay2 = StreamingHomeHelpers.pickDailyRecommendations(
            songs = pool,
            memberKey = "member_a",
            epochDay = 20401L,
            limit = 10
        ).map { it.id }
        assertNotEquals(memberADay1First, memberADay2)

        val memberBDay1 = StreamingHomeHelpers.pickDailyRecommendations(
            songs = pool,
            memberKey = "member_b",
            epochDay = 20400L,
            limit = 10
        ).map { it.id }
        assertNotEquals(memberADay1First, memberBDay1)
    }

    @Test
    fun catalogCacheCodec_roundTripsCreatedAtMs() {
        val createdAt = 1_760_123_456_000L
        val song = StreamingSong(
            id = "CUSTOM::wn_track",
            title = "3107",
            artist = "W/n",
            album = "3107 Album",
            duration = 215_000L,
            artworkUri = "https://cdn.test/wn.jpg",
            sourceType = SourceType.CUSTOM_API,
            streamingUrl = "https://cdn.test/wn.mp3",
            previewUrl = "https://cdn.test/wn.mp3",
            externalId = "wn_track",
            createdAtMs = createdAt
        )
        val cache = StreamingCatalogCache(
            serviceId = "CUSTOM",
            songs = listOf(song),
            lastSyncTimestamp = 999L
        )
        val codec = CatalogCacheCodec()
        val writer = StringWriter()
        codec.write(writer, cache)

        val restored = codec.read(StringReader(writer.toString()))
        assertNotNull(restored)
        assertEquals(1, restored!!.songs.size)
        assertEquals("W/n", restored.songs[0].artist)
        assertEquals(createdAt, restored.songs[0].createdAtMs)
    }

    @Test
    fun xkResponseParser_ordersNewestAddedSongsImmediatelyAndPushesOldestOutOfTop5() {
        val initialRaw = """
            {
              "-Nxyz000000000000001": { "title": "Track 1", "artist": "A", "addedAt": "2026-11-01T10:00:00Z" },
              "-Nxyz000000000000002": { "title": "Track 2", "artist": "A", "addedAt": "2026-11-02T10:00:00Z" },
              "-Nxyz000000000000003": { "title": "Track 3", "artist": "A", "addedAt": "2026-11-03T10:00:00Z" },
              "-Nxyz000000000000004": { "title": "Track 4", "artist": "A", "addedAt": "2026-11-04T10:00:00Z" },
              "-Nxyz000000000000005": { "title": "Track 5", "artist": "A", "addedAt": "2026-11-05T10:00:00Z" }
            }
        """.trimIndent()
        val withNewTrackRaw = """
            {
              "-Nxyz000000000000001": { "title": "Track 1", "artist": "A", "addedAt": "2026-11-01T10:00:00Z" },
              "-Nxyz000000000000002": { "title": "Track 2", "artist": "A", "addedAt": "2026-11-02T10:00:00Z" },
              "-Nxyz000000000000003": { "title": "Track 3", "artist": "A", "addedAt": "2026-11-03T10:00:00Z" },
              "-Nxyz000000000000004": { "title": "Track 4", "artist": "A", "addedAt": "2026-11-04T10:00:00Z" },
              "-Nxyz000000000000005": { "title": "Track 5", "artist": "A", "addedAt": "2026-11-05T10:00:00Z" },
              "-OAbc000000000000006": { "title": "Track 6 Brand New", "artist": "A", "addedAt": "2026-11-25T09:00:00Z" }
            }
        """.trimIndent()

        val initialTop5 = XkResponseParser.parseSongsEntries(initialRaw)
            .sortedByDescending { it.createdAtMs }
            .take(5)
            .map { it.song.title }
        assertEquals(listOf("Track 5", "Track 4", "Track 3", "Track 2", "Track 1"), initialTop5)

        val updatedTop5 = XkResponseParser.parseSongsEntries(withNewTrackRaw)
            .sortedByDescending { it.createdAtMs }
            .take(5)
            .map { it.song.title }
        assertEquals(
            listOf("Track 6 Brand New", "Track 5", "Track 4", "Track 3", "Track 2"),
            updatedTop5
        )
    }

    @Test
    fun xkResponseParser_decodesFirebasePushIdTimestampWhenExplicitTimestampMissing() {
        val raw = """
            {
              "-N111111111111111111": { "title": "Older Push", "artist": "Artist" },
              "-O999999999999999999": { "title": "Newer Push", "artist": "Artist" }
            }
        """.trimIndent()
        val sorted = XkResponseParser.parseSongsEntries(raw).sortedByDescending { it.createdAtMs }
        assertEquals(listOf("Newer Push", "Older Push"), sorted.map { it.song.title })
        assertTrue(sorted[0].createdAtMs > sorted[1].createdAtMs)
    }
}
