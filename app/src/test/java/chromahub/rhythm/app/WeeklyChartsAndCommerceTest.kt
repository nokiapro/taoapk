/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chromahub.rhythm.app.core.domain.model.SourceType
import chromahub.rhythm.app.features.streaming.data.provider.XkResponseParser
import chromahub.rhythm.app.features.streaming.data.repository.CatalogCacheCodec
import chromahub.rhythm.app.features.streaming.data.repository.StreamingCatalogCache
import chromahub.rhythm.app.features.streaming.data.repository.WeeklyChartsTracker
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.io.StringWriter
import java.time.DayOfWeek
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeeklyChartsAndCommerceTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("xkmusic_weekly_charts_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun catalogCacheCodec_roundTripsPaidFreeAndPurchasedFields() {
        val paidUnpurchased = StreamingSong(
            id = "CUSTOM::song_paid",
            title = "Paid Unpurchased",
            artist = "Artist A",
            album = "Album A",
            duration = 210_000L,
            artworkUri = "https://xkmu.xuanken.name.vn/cover1.jpg",
            sourceType = SourceType.CUSTOM_API,
            streamingUrl = "https://xkmu.xuanken.name.vn/demo1.mp3",
            previewUrl = "https://xkmu.xuanken.name.vn/demo1.mp3",
            externalId = "song_paid",
            price = 25000.0,
            isPaidTrack = true,
            isPurchased = false,
            currency = "VND"
        )
        val paidPurchased = StreamingSong(
            id = "CUSTOM::song_bought",
            title = "Paid Purchased",
            artist = "Artist B",
            album = "Album B",
            duration = 195_000L,
            artworkUri = "https://xkmu.xuanken.name.vn/cover2.jpg",
            sourceType = SourceType.CUSTOM_API,
            streamingUrl = "https://xkmu.xuanken.name.vn/master2.wav",
            previewUrl = "https://xkmu.xuanken.name.vn/demo2.mp3",
            externalId = "song_bought",
            price = 19.99,
            isPaidTrack = true,
            isPurchased = true,
            currency = "USD"
        )
        val freeTrack = StreamingSong(
            id = "CUSTOM::song_free",
            title = "Free Track",
            artist = "Artist C",
            album = "Single",
            duration = 180_000L,
            artworkUri = null,
            sourceType = SourceType.CUSTOM_API,
            streamingUrl = "https://xkmu.xuanken.name.vn/free.mp3",
            previewUrl = "https://xkmu.xuanken.name.vn/free.mp3",
            externalId = "song_free",
            price = 0.0,
            isPaidTrack = false,
            isPurchased = true,
            currency = "VND"
        )

        val cache = StreamingCatalogCache(
            serviceId = "CUSTOM",
            songs = listOf(paidUnpurchased, paidPurchased, freeTrack),
            lastSyncTimestamp = 123456789L
        )
        val codec = CatalogCacheCodec()
        val writer = StringWriter()
        codec.write(writer, cache)

        val restored = codec.read(StringReader(writer.toString()))
        assertNotNull(restored)
        assertEquals(3, restored!!.songs.size)

        val r1 = restored.songs[0]
        assertEquals(25000.0, r1.price, 0.001)
        assertTrue(r1.isPaidTrack)
        assertFalse(r1.isPurchased)
        assertEquals("VND", r1.currency)

        val r2 = restored.songs[1]
        assertEquals(19.99, r2.price, 0.001)
        assertTrue(r2.isPaidTrack)
        assertTrue(r2.isPurchased)
        assertEquals("USD", r2.currency)

        val r3 = restored.songs[2]
        assertEquals(0.0, r3.price, 0.001)
        assertFalse(r3.isPaidTrack)
        assertTrue(r3.isPurchased)
    }

    @Test
    fun xkResponseParser_parsesVndPricesPaidFlagsAndEmbeddedAlbumPricing() {
        val songsJson = """
            {
              "s_vnd_dot": {
                "title": "VND Dot Song",
                "artist": "Ca Si 1",
                "price": "25.000",
                "demoUrl": "https://xkmu.xuanken.name.vn/demo1.mp3"
              },
              "s_vnd_comma": {
                "title": "VND Comma Song",
                "artist": "Ca Si 2",
                "price": "19,000đ",
                "isPaid": true
              },
              "s_free": {
                "title": "Free Song",
                "artist": "Ca Si 3",
                "price": 0,
                "isFree": true
              }
            }
        """.trimIndent()

        val parsedSongs = XkResponseParser.parseSongsEntries(songsJson).associateBy { it.song.providerId }
        val dotSong = parsedSongs["s_vnd_dot"]!!
        assertEquals(25000.0, dotSong.price, 0.01)
        assertTrue(dotSong.isPaid)
        assertFalse(dotSong.song.isPurchased)

        val commaSong = parsedSongs["s_vnd_comma"]!!
        assertEquals(19000.0, commaSong.price, 0.01)
        assertTrue(commaSong.isPaid)
        assertFalse(commaSong.song.isPurchased)

        val freeSong = parsedSongs["s_free"]!!
        assertEquals(0.0, freeSong.price, 0.01)
        assertFalse(freeSong.isPaid)
        assertTrue(freeSong.song.isPurchased)

        val albumJson = """
            {
              "alb_1": {
                "title": "Exclusive Album",
                "artist": "Ca Si X",
                "songs": [
                  {
                    "id": "emb_1",
                    "title": "Embedded Paid",
                    "price": "35.000đ"
                  },
                  {
                    "id": "emb_2",
                    "title": "Embedded Free",
                    "price": 0
                  }
                ]
              }
            }
        """.trimIndent()
        val parsedAlbums = XkResponseParser.parseAlbumsEntries(albumJson)
        assertEquals(1, parsedAlbums.size)
        val embedded = parsedAlbums[0].embeddedSongs.associateBy { it.providerId }
        assertTrue(embedded["emb_1"]!!.isPaidTrack)
        assertEquals(35000.0, embedded["emb_1"]!!.price, 0.01)
        assertFalse(embedded["emb_1"]!!.isPurchased)
        assertFalse(embedded["emb_2"]!!.isPaidTrack)
        assertTrue(embedded["emb_2"]!!.isPurchased)
    }

    @Test
    fun xkResponseParser_parsesWeeklyPlaysPayloads() {
        val weekScopedJson = """
            {
              "2026-W47": {
                "song_a": 42,
                "song_b": { "plays": 15 }
              },
              "2026-W46": {
                "song_old": 999
              }
            }
        """.trimIndent()
        val scoped = XkResponseParser.parseWeeklyPlays(weekScopedJson, targetWeekKey = "2026-W47")
        assertEquals(42L, scoped["song_a"])
        assertEquals(15L, scoped["song_b"])
        assertFalse(scoped.containsKey("song_old"))

        val arrayJson = """
            [
              { "songId": "s1", "weeklyPlays": 120 },
              { "id": "s2", "play_count": 88 }
            ]
        """.trimIndent()
        val arrayParsed = XkResponseParser.parseWeeklyPlays(arrayJson)
        assertEquals(120L, arrayParsed["s1"])
        assertEquals(88L, arrayParsed["s2"])
    }

    @Test
    fun weeklyChartsTracker_ranksTop10AndResetsEveryMonday() {
        var currentDate = LocalDate.of(2026, 11, 22) // Sunday of week 47
        assertEquals(DayOfWeek.SUNDAY, currentDate.dayOfWeek)

        val tracker = WeeklyChartsTracker(app, dateProvider = { currentDate })

        val sundayPeriod = tracker.getCurrentPeriodInfo()
        assertEquals("2026-W47", sundayPeriod.weekKey)
        assertEquals(1, sundayPeriod.daysUntilReset)

        // Create 12 songs
        val songs = (1..12).map { i ->
            StreamingSong(
                id = "CUSTOM::s$i",
                title = "Track $i",
                artist = "Artist $i",
                album = "Album",
                duration = 200_000L,
                artworkUri = null,
                sourceType = SourceType.CUSTOM_API,
                streamingUrl = null,
                previewUrl = null,
                externalId = "s$i"
            )
        }

        // Record local plays on Sunday
        repeat(5) { tracker.recordPlay("CUSTOM::s3") }
        repeat(2) { tracker.recordPlay("s1") }

        val serverPlays = mapOf(
            "s1" to 10L,   // total 12
            "s2" to 25L,   // total 25
            "s3" to 3L,    // total 8
            "s4" to 40L    // total 40
        )

        val top10 = tracker.buildTopCharts(songs, serverPlays, limit = 10)
        assertEquals(10, top10.size)
        assertEquals("s4", top10[0].song.externalId)
        assertEquals(40L, top10[0].weeklyPlays)
        assertEquals(1, top10[0].rank)
        assertEquals("s2", top10[1].song.externalId)
        assertEquals(25L, top10[1].weeklyPlays)
        assertEquals("s1", top10[2].song.externalId)
        assertEquals(12L, top10[2].weeklyPlays)
        assertEquals("s3", top10[3].song.externalId)
        assertEquals(8L, top10[3].weeklyPlays)

        // Advance to Monday of next ISO week -> local plays must auto-reset to empty
        currentDate = LocalDate.of(2026, 11, 23)
        assertEquals(DayOfWeek.MONDAY, currentDate.dayOfWeek)

        val mondayPeriod = tracker.getCurrentPeriodInfo()
        assertEquals("2026-W48", mondayPeriod.weekKey)
        assertEquals(7, mondayPeriod.daysUntilReset)
        assertTrue(tracker.getLocalPlayCounts().isEmpty())

        // Recording a new play starts fresh at 1
        assertEquals(1L, tracker.recordPlay("CUSTOM::s3"))
    }
}
