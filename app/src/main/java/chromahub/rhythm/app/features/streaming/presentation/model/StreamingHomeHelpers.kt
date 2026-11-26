/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.presentation.model

import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import java.time.LocalDate
import java.util.Random

object StreamingHomeHelpers {

    const val SEVEN_DAYS_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

    /**
     * Picks up to [limit] daily recommended songs for a specific member [memberKey] on [epochDay].
     * Deterministic within the same day for the same member, while refreshing each new day and
     * differing across members.
     */
    fun pickDailyRecommendations(
        songs: List<StreamingSong>,
        memberKey: String,
        epochDay: Long = LocalDate.now().toEpochDay(),
        limit: Int = 10
    ): List<StreamingSong> {
        if (songs.isEmpty() || limit <= 0) return emptyList()
        val normalizedMember = memberKey.trim().ifBlank { "guest" }
        val canonicalPool = songs
            .filter { it.isPlayable }
            .distinctBy { it.id }
            .sortedBy { it.id }
        if (canonicalPool.isEmpty()) return emptyList()

        // FNV-1a style 64-bit mix so distinct users/days spread cleanly across seeds
        var seed = -3750763034362895579L
        val compositeKey = "$normalizedMember#$epochDay"
        for (ch in compositeKey) {
            seed = seed xor ch.code.toLong()
            seed *= 1099511628211L
        }
        return canonicalPool.shuffled(Random(seed)).take(limit)
    }

    /**
     * Returns true only when [createdAtMs] is within the last [maxAgeDays] days (inclusive of 0ms age).
     * Once a track exceeds [maxAgeDays], the NEW badge is hidden.
     */
    fun isTrackNewWithinDays(
        createdAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeDays: Long = 7L
    ): Boolean {
        if (createdAtMs <= 0L || maxAgeDays <= 0L) return false
        val maxWindowMs = maxAgeDays * 24L * 60L * 60L * 1000L
        val ageMs = nowMs - createdAtMs
        return ageMs in 0L..maxWindowMs
    }
}
