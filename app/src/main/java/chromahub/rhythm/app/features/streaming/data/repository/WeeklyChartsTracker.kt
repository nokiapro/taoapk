/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.repository

import android.content.Context
import androidx.core.content.edit
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

data class WeeklyChartEntry(
    val rank: Int,
    val song: StreamingSong,
    val weeklyPlays: Long
)

data class WeeklyChartPeriodInfo(
    val weekKey: String,
    val weekRangeLabel: String,
    val daysUntilReset: Int
)

/**
 * Tracks per-week play counts and builds the Weekly Top-10 ("BXH") leaderboard.
 * Automatically resets stored local weekly play counts every ISO week (Monday 00:00).
 */
class WeeklyChartsTracker internal constructor(
    context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val dateProvider: () -> LocalDate = { LocalDate.now(zoneId) }
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun getCurrentPeriodInfo(): WeeklyChartPeriodInfo {
        synchronized(lock) {
            val today = dateProvider()
            ensureCurrentWeekLocked(today)
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val sunday = monday.plusDays(6)
            val nextMonday = monday.plusWeeks(1)
            val daysLeft = ChronoUnit.DAYS.between(today, nextMonday).toInt().coerceIn(1, 7)
            val formatter = DateTimeFormatter.ofPattern("dd/MM")
            return WeeklyChartPeriodInfo(
                weekKey = weekKeyForDate(today),
                weekRangeLabel = "${monday.format(formatter)} - ${sunday.format(formatter)}",
                daysUntilReset = daysLeft
            )
        }
    }

    fun getCurrentWeekKey(): String {
        synchronized(lock) {
            val today = dateProvider()
            ensureCurrentWeekLocked(today)
            return weekKeyForDate(today)
        }
    }

    fun recordPlay(songId: String): Long {
        val cleanId = normalizeSongKey(songId)
        if (cleanId.isBlank()) return 0L
        synchronized(lock) {
            val today = dateProvider()
            ensureCurrentWeekLocked(today)
            val counts = readPlayCountsLocked().toMutableMap()
            val updated = (counts[cleanId] ?: 0L) + 1L
            counts[cleanId] = updated
            writePlayCountsLocked(counts)
            return updated
        }
    }

    fun getLocalPlayCounts(): Map<String, Long> {
        synchronized(lock) {
            val today = dateProvider()
            ensureCurrentWeekLocked(today)
            return readPlayCountsLocked()
        }
    }

    fun buildTopCharts(
        catalogSongs: List<StreamingSong>,
        serverWeeklyPlays: Map<String, Long> = emptyMap(),
        limit: Int = DEFAULT_TOP_LIMIT
    ): List<WeeklyChartEntry> {
        if (catalogSongs.isEmpty() || limit <= 0) return emptyList()
        val localCounts = getLocalPlayCounts()

        val normalizedServer = HashMap<String, Long>(serverWeeklyPlays.size)
        serverWeeklyPlays.forEach { (key, count) ->
            val cleanKey = normalizeSongKey(key)
            if (cleanKey.isNotBlank() && count > 0L) {
                normalizedServer[cleanKey] = maxOf(normalizedServer[cleanKey] ?: 0L, count)
            }
        }

        val dedupedSongs = LinkedHashMap<String, StreamingSong>(catalogSongs.size)
        catalogSongs.forEach { song ->
            val key = normalizeSongKey(song.externalId?.takeIf { it.isNotBlank() } ?: song.id)
            if (key.isNotBlank() && key !in dedupedSongs) {
                dedupedSongs[key] = song
            }
        }

        return dedupedSongs.entries
            .map { (key, song) ->
                val serverPlays = normalizedServer[key] ?: 0L
                val localPlays = localCounts[key] ?: 0L
                val totalWeekly = serverPlays + localPlays
                key to (song to totalWeekly)
            }
            .sortedWith(
                compareByDescending<Pair<String, Pair<StreamingSong, Long>>> { it.second.second }
                    .thenByDescending { it.second.first.popularity ?: 0 }
                    .thenBy { it.second.first.title.lowercase() }
            )
            .take(limit)
            .mapIndexed { index, pair ->
                WeeklyChartEntry(
                    rank = index + 1,
                    song = pair.second.first,
                    weeklyPlays = pair.second.second
                )
            }
    }

    private fun ensureCurrentWeekLocked(today: LocalDate) {
        val currentWeek = weekKeyForDate(today)
        val storedWeek = prefs.getString(KEY_WEEK_ID, null)
        if (storedWeek != currentWeek) {
            prefs.edit {
                putString(KEY_WEEK_ID, currentWeek)
                remove(KEY_PLAY_COUNTS_JSON)
            }
        }
    }

    private fun readPlayCountsLocked(): Map<String, Long> {
        val raw = prefs.getString(KEY_PLAY_COUNTS_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val result = LinkedHashMap<String, Long>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optLong(key, 0L)
                if (value > 0L) result[key] = value
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun writePlayCountsLocked(counts: Map<String, Long>) {
        val obj = JSONObject()
        counts.forEach { (k, v) ->
            if (v > 0L) obj.put(k, v)
        }
        prefs.edit {
            putString(KEY_PLAY_COUNTS_JSON, obj.toString())
        }
    }

    companion object {
        private const val PREFS_NAME = "xkmusic_weekly_charts_prefs"
        private const val KEY_WEEK_ID = "current_week_key"
        private const val KEY_PLAY_COUNTS_JSON = "weekly_plays_json"
        const val DEFAULT_TOP_LIMIT = 10

        fun weekKeyForDate(date: LocalDate): String {
            val isoFields = WeekFields.ISO
            val weekYear = date.get(isoFields.weekBasedYear())
            val weekNumber = date.get(isoFields.weekOfWeekBasedYear())
            return String.format("%04d-W%02d", weekYear, weekNumber)
        }

        fun normalizeSongKey(songId: String): String {
            return songId.substringAfterLast("::").trim()
        }
    }
}
