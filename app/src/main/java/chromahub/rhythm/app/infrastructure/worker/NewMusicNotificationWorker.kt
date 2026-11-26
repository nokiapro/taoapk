/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.features.streaming.data.provider.ProviderSong
import chromahub.rhythm.app.features.streaming.data.provider.UserTrustManager
import chromahub.rhythm.app.features.streaming.data.provider.XuanKenFirebaseClient
import chromahub.rhythm.app.shared.data.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Periodic and on-demand worker that checks the Custom Music API for newly
 * released tracks and pushes rich notifications with artwork and deep-link playback.
 */
class NewMusicNotificationWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "NewMusicNotifWorker"
        const val WORK_NAME = "new_music_notification_work"
        const val CHANNEL_ID = "new_music_releases"
        private const val NOTIFICATION_ID = 1205
        private const val PREF_NAME = "new_music_notification_cache"
        private const val KEY_SEEN_IDS = "seen_newest_song_ids"
        private const val KEY_IS_MANUAL = "is_manual"
        private const val MAX_SEEN_IDS = 150

        fun checkNow(context: Context, isManual: Boolean = false) {
            val input = Data.Builder()
                .putBoolean(KEY_IS_MANUAL, isManual)
                .build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<NewMusicNotificationWorker>()
                .setInputData(input)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }

    private val prefs = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val isManual = inputData.getBoolean(KEY_IS_MANUAL, false)
            val appSettings = AppSettings.getInstance(applicationContext)
            if (!isManual && !appSettings.newMusicNotificationsEnabled.value) {
                Log.d(TAG, "New music notifications disabled locally, skipping")
                return@withContext Result.success()
            }

            val client = XuanKenFirebaseClient.getInstance(applicationContext)
            if (!client.isConnected()) {
                Log.d(TAG, "Xuanken Music not connected, skipping new music check")
                return@withContext Result.success()
            }

            // Respect server-side notification setting if available
            if (!isManual && client.hasUserSession()) {
                val cloudSettings = client.getUserSettings().getOrNull()
                if (cloudSettings != null && !cloudSettings.pushNewMusic) {
                    Log.d(TAG, "Server pushNewMusic setting disabled, skipping")
                    return@withContext Result.success()
                }
            }

            val latestSongs = client.getPublicSongs(forceRefresh = true)
                .getOrNull()
                ?.reversed()
                ?.take(20)
                .orEmpty()

            if (latestSongs.isEmpty()) {
                Log.d(TAG, "No newest songs returned from Firebase RTDB")
                return@withContext Result.success()
            }

            val existingSeen = loadSeenIds()
            val newlyDiscovered = latestSongs.filter { it.providerId !in existingSeen }

            val tracksToNotify: List<ProviderSong> = when {
                existingSeen.isEmpty() -> {
                    // First check: save full list and highlight the single freshest release
                    latestSongs.take(1)
                }
                newlyDiscovered.isNotEmpty() -> newlyDiscovered
                isManual -> latestSongs.take(1)
                else -> emptyList()
            }

            val updatedSeen = (latestSongs.map { it.providerId } + existingSeen)
                .distinct()
                .take(MAX_SEEN_IDS)
            saveSeenIds(updatedSeen)

            if (tracksToNotify.isNotEmpty()) {
                val artworkBitmap = tracksToNotify.firstOrNull()?.artworkUrl?.let { url ->
                    fetchArtworkBitmap(url)
                }
                postNewMusicNotification(tracksToNotify, artworkBitmap)
            } else {
                Log.d(TAG, "No unseen new tracks found")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for newest music", e)
            Result.retry()
        }
    }

    private fun loadSeenIds(): Set<String> {
        val raw = prefs.getString(KEY_SEEN_IDS, "").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun saveSeenIds(ids: List<String>) {
        prefs.edit {
            putString(KEY_SEEN_IDS, ids.joinToString(","))
        }
    }

    private fun fetchArtworkBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder().url(url).get().build()
            UserTrustManager.buildUserTrustingHttpClientBuilder().build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return null
                    response.body?.byteStream()?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download notification artwork", e)
            null
        }
    }

    private fun postNewMusicNotification(
        tracks: List<ProviderSong>,
        artwork: Bitmap?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping notification display")
                return
            }
        }

        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notifications_new_music_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = applicationContext.getString(R.string.notifications_new_music_channel_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)

        val leadTrack = tracks.first()
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            putExtra(MainActivity.EXTRA_PLAY_SONG_ID, "CUSTOM_API::${leadTrack.providerId}")
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (tracks.size == 1) {
            val subtitle = buildString {
                append(leadTrack.artist)
                if (leadTrack.album.isNotBlank()) {
                    append(" • ")
                    append(leadTrack.album)
                }
            }
            builder
                .setContentTitle(leadTrack.title)
                .setContentText(subtitle)

            if (artwork != null) {
                builder
                    .setLargeIcon(artwork)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(artwork)
                            .bigLargeIcon(null as Bitmap?)
                            .setBigContentTitle(leadTrack.title)
                            .setSummaryText(subtitle)
                    )
            } else {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            }
        } else {
            val summaryTitle = "${tracks.size} new tracks released"
            val summaryText = tracks.take(3).joinToString(", ") { it.title }
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle(summaryTitle)
            tracks.take(5).forEach { song ->
                inboxStyle.addLine("${song.title} — ${song.artist}")
            }
            if (tracks.size > 5) {
                inboxStyle.setSummaryText("+${tracks.size - 5} more new tracks")
            }

            builder
                .setContentTitle(summaryTitle)
                .setContentText(summaryText)
                .setStyle(inboxStyle)
            if (artwork != null) {
                builder.setLargeIcon(artwork)
            }
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (se: SecurityException) {
            Log.w(TAG, "Unable to post new music notification", se)
        }
    }
}
