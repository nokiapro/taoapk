/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package chromahub.rhythm.app.features.streaming.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.streaming.data.repository.WeeklyChartEntry
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.features.streaming.presentation.viewmodel.StreamingMusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.ui.LocalMiniPlayerPadding
import chromahub.rhythm.app.util.HapticType
import chromahub.rhythm.app.util.HapticUtils
import chromahub.rhythm.app.util.M3ImageUtils
import java.text.NumberFormat
import java.util.Locale

private val BXH_PRICE_FORMATTER: NumberFormat =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))

@Composable
fun WeeklyChartsTabScreen(
    viewModel: StreamingMusicViewModel,
    currentSong: Song?,
    isPlaying: Boolean,
    onPurchaseSong: (StreamingSong) -> Unit,
    onAddToPlaylist: (StreamingSong) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val weeklyCharts by viewModel.weeklyCharts.collectAsState()
    val weeklyChartPeriod by viewModel.weeklyChartPeriod.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshWeeklyCharts(fetchRemote = true)
    }

    val topSongs = remember(weeklyCharts) { weeklyCharts.map { it.song } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 12.dp,
                bottom = 28.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "bxh_header") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(999.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.weekly_charts_top_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(999.dp)
                                ) {
                                    Text(
                                        text = weeklyChartPeriod.weekRangeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            FilledTonalIconButton(
                                onClick = {
                                    HapticUtils.performHapticFeedback(context, haptics, HapticType.MEDIUM)
                                    viewModel.refreshWeeklyCharts(fetchRemote = true)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("bxh_refresh_button")
                            ) {
                                Icon(
                                    imageVector = MaterialSymbolIcon("refresh"),
                                    contentDescription = stringResource(R.string.action_refresh)
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.weekly_charts_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Text(
                            text = stringResource(R.string.weekly_charts_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbolIcon("schedule", filled = true),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.weekly_charts_reset_countdown,
                                            weeklyChartPeriod.daysUntilReset
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            if (topSongs.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        HapticUtils.performHapticFeedback(context, haptics, HapticType.HEAVY)
                                        viewModel.playQueue(topSongs, startIndex = 0, shuffle = false)
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("bxh_play_all_button")
                                ) {
                                    Icon(
                                        imageVector = RhythmIcons.Play,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.weekly_charts_play_all),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading && weeklyCharts.isEmpty()) {
                item(key = "bxh_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (weeklyCharts.isEmpty()) {
                item(key = "bxh_empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = MaterialSymbolIcon("leaderboard", filled = true),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = stringResource(R.string.weekly_charts_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.weekly_charts_empty_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = weeklyCharts,
                    key = { _, entry -> "bxh_${entry.song.id}" },
                    contentType = { _, _ -> "bxh_song" }
                ) { index, entry ->
                    WeeklyChartRowCard(
                        entry = entry,
                        isCurrentSong = currentSong?.id == entry.song.id,
                        isPlaying = currentSong?.id == entry.song.id && isPlaying,
                        onPlay = {
                            HapticUtils.performHapticFeedback(context, haptics, HapticType.HEAVY)
                            viewModel.playQueue(topSongs, startIndex = index, shuffle = false)
                        },
                        onPurchase = { onPurchaseSong(entry.song) },
                        onAddToPlaylist = { onAddToPlaylist(entry.song) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyChartRowCard(
    entry: WeeklyChartEntry,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPurchase: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = entry.song
    val rank = entry.rank
    val isTopThree = rank <= 3

    val rankContainerColor = when (rank) {
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val rankContentColor = when (rank) {
        1 -> MaterialTheme.colorScheme.onPrimary
        2 -> MaterialTheme.colorScheme.onSecondary
        3 -> MaterialTheme.colorScheme.onTertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick = onPlay,
        modifier = modifier
            .fillMaxWidth()
            .testTag("bxh_song_$rank"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentSong) {
                MaterialTheme.colorScheme.primaryContainer
            } else if (isTopThree) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = if (isTopThree && !isCurrentSong) {
            BorderStroke(1.dp, rankContainerColor.copy(alpha = 0.45f))
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = rankContainerColor,
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "#$rank",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = rankContentColor
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(56.dp),
                border = if (isCurrentSong) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                M3ImageUtils.TrackImage(
                    imageUrl = song.artworkUri,
                    trackName = song.title,
                    modifier = Modifier.fillMaxSize(),
                    applyExpressiveShape = false
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrentSong) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrentSong) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val playsLabel = if (entry.weeklyPlays > 0L) {
                        stringResource(R.string.weekly_charts_plays_format, entry.weeklyPlays)
                    } else {
                        stringResource(R.string.weekly_charts_trending_badge)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = playsLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    if (song.isPaidTrack || song.price > 0.0) {
                        if (song.isPurchased) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.weekly_charts_purchased_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        } else {
                            val formattedPrice = remember(song.price) {
                                "${BXH_PRICE_FORMATTER.format(song.price.toLong())} đ"
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = formattedPrice,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                onClick = onPurchase,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.testTag("bxh_buy_${song.id}")
                            ) {
                                Text(
                                    text = stringResource(R.string.library_song_buy_action),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.weekly_charts_free_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onAddToPlaylist,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("bxh_more_$rank")
            ) {
                Icon(
                    imageVector = RhythmIcons.AddToPlaylist,
                    contentDescription = stringResource(R.string.content_desc_add_to_playlist),
                    tint = if (isCurrentSong) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
