/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.presentation.screens

import chromahub.rhythm.app.shared.presentation.components.bottomsheets.AdaptiveSheetScrollContainer
import chromahub.rhythm.app.shared.presentation.components.bottomsheets.RhythmAdaptiveModalSheet
import chromahub.rhythm.app.shared.presentation.components.bottomsheets.SheetAdaptiveType
import chromahub.rhythm.app.shared.presentation.components.bottomsheets.StandardBottomSheetHeader
import chromahub.rhythm.app.shared.presentation.components.bottomsheets.groupedBottomSheetItemShape

import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.Icon

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import chromahub.rhythm.app.core.domain.model.StreamingQuality
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.features.streaming.presentation.viewmodel.StreamingMusicViewModel
import chromahub.rhythm.app.shared.presentation.components.common.CollapsibleHeaderScreen
import chromahub.rhythm.app.shared.presentation.components.Material3SettingsGroup
import chromahub.rhythm.app.shared.presentation.components.Material3SettingsItem
import chromahub.rhythm.app.shared.presentation.screens.settings.TunerAnimatedSwitch
import chromahub.rhythm.app.util.HapticUtils
import chromahub.rhythm.app.util.HapticType
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import chromahub.rhythm.app.features.streaming.presentation.model.StreamingServiceOptions
import chromahub.rhythm.app.core.utils.NetworkUtils
import chromahub.rhythm.app.shared.presentation.components.bottomsheets.StandardBottomSheetHeader
import androidx.compose.ui.text.style.TextOverflow
import chromahub.rhythm.app.features.streaming.presentation.model.StreamingServiceOption
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import chromahub.rhythm.app.R
import chromahub.rhythm.app.ui.LocalMiniPlayerPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoSettingsScreen(
    onBackClick: () -> Unit,
    onConfigureCurrentProvider: (String) -> Unit = {},
    onOpenAccountSettings: () -> Unit = {},
    onOpenPurchases: () -> Unit = {},
    onOpenBlockedSongs: () -> Unit = {},
    viewModel: StreamingMusicViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val appSettings = remember { AppSettings.getInstance(context) }
    val scope = rememberCoroutineScope()

    // Collect streaming settings
    val selectedService by appSettings.streamingService.collectAsState()
    val streamingQuality by appSettings.streamingQuality.collectAsState()
    val allowCellularStreaming by appSettings.allowCellularStreaming.collectAsState()
    val siteInfo by viewModel.siteInfo.collectAsState()

    // Entrance animation
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showContent = true
        viewModel.refreshSiteAndAccount()
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (showContent) 1f else 0f,
        animationSpec = tween(durationMillis = 350)
    )

    val contentOffset by animateFloatAsState(
        targetValue = if (showContent) 0f else 20f,
        animationSpec = tween(durationMillis = 380)
    )

    // Derived streaming state
    val sessions by viewModel.serviceSessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedSession = sessions[selectedService]
    val selectedServiceConnected = selectedSession?.isConnected == true

    LaunchedEffect(selectedServiceConnected) {
        if (!selectedServiceConnected) {
            viewModel.refreshCurrentSession()
        }
    }

    var showQualitySheet by remember { mutableStateOf(false) }

    CollapsibleHeaderScreen(
        title = stringResource(R.string.streaming_service_custom_api),
        showBackButton = true,
        onBackClick = {
            showContent = false
            scope.launch {
                delay(380)
                onBackClick()
            }
        }
    ) { modifier ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp)
                .graphicsLayer {
                    alpha = contentAlpha
                    translationY = contentOffset
                },
            contentPadding = PaddingValues(bottom = 24.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            siteInfo?.let { info ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = MaterialSymbolIcon("dns", filled = true),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = info.siteName.ifBlank { stringResource(R.string.custom_api_site_info_title) },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                if (info.maintenanceMode) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.custom_api_maintenance_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            if (info.description.isNotBlank()) {
                                Text(
                                    text = info.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                )
                            }
                            if (info.announcement.isNotBlank()) {
                                Text(
                                    text = info.announcement,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            item {
                Material3SettingsGroup(
                    title = stringResource(R.string.streaming_settings_group_services),
                    items = listOf(
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("high_quality"),
                            title = { Text(text = stringResource(R.string.streaming_settings_quality)) },
                            description = { Text(text = streamingQualityLabel(streamingQuality, context)) },
                            onClick = { showQualitySheet = true }
                        )
                    )
                )
            }

            item {
                Material3SettingsGroup(
                    title = stringResource(R.string.custom_api_account_section),
                    items = listOf(
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("manage_accounts"),
                            title = { Text(text = stringResource(R.string.custom_api_profile_title)) },
                            description = { Text(text = stringResource(R.string.custom_api_profile_desc)) },
                            onClick = onOpenAccountSettings
                        ),
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("shopping_bag"),
                            title = { Text(text = stringResource(R.string.custom_api_purchases_vouchers_title)) },
                            description = { Text(text = stringResource(R.string.custom_api_purchases_vouchers_desc)) },
                            onClick = onOpenPurchases
                        ),
                        Material3SettingsItem(
                            icon = RhythmIcons.Block,
                            title = { Text(text = stringResource(R.string.custom_api_blocked_songs_title)) },
                            description = { Text(text = stringResource(R.string.custom_api_blocked_songs_desc)) },
                            onClick = onOpenBlockedSongs
                        )
                    )
                )
            }

            item {
                Material3SettingsGroup(
                    title = stringResource(R.string.gosettingsscreen_network),
                    items = listOf(
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("mobile_friendly"),
                            title = { Text(text = stringResource(R.string.exp_cellular_streaming)) },
                            description = { Text(text = stringResource(R.string.gosettingsscreen_enable_streaming_over_mobile)) },
                            trailingContent = {
                                TunerAnimatedSwitch(
                                    checked = allowCellularStreaming,
                                    onCheckedChange = { appSettings.setAllowCellularStreaming(it) }
                                )
                            }
                        )
                    )
                )
            }

            item {
                StreamingStatusCard(
                    selectedServiceName = selectedServiceLabel(selectedService, context),
                    isConnected = selectedServiceConnected,
                    isLoading = isLoading,
                    username = selectedSession?.username.orEmpty(),
                    serverUrl = selectedSession?.serverUrl.orEmpty()
                )
            }

            item { Spacer(modifier = Modifier.height(18.dp)) }
        }
    }

    if (showQualitySheet) {
        QualitySelectionBottomSheet(
            selectedQuality = streamingQuality.uppercase(),
            onDismiss = { showQualitySheet = false },
            onSelect = { quality ->
                HapticUtils.performHapticFeedback(context, haptics, HapticType.LIGHT)
                viewModel.setStreamingQuality(StreamingQuality.valueOf(quality))
                showQualitySheet = false
            }
        )
    }
}

@Composable
private fun StreamingStatusCard(
    selectedServiceName: String,
    isConnected: Boolean,
    isLoading: Boolean,
    username: String,
    serverUrl: String
) {
    val badgeText = when {
        isLoading -> stringResource(id = chromahub.rhythm.app.R.string.streaming_status_badge_refreshing)
        isConnected -> stringResource(id = chromahub.rhythm.app.R.string.streaming_status_badge_connected)
        else -> stringResource(id = chromahub.rhythm.app.R.string.streaming_status_badge_pending)
    }

    val badgeContainerColor = when {
        isLoading -> MaterialTheme.colorScheme.primaryContainer
        isConnected -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val badgeContentColor = when {
        isLoading -> MaterialTheme.colorScheme.onPrimaryContainer
        isConnected -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = MaterialSymbolIcon("cloud_queue"),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.streaming_status_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Surface(
                    color = badgeContainerColor,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeContentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.gosettingsscreen_service_format, selectedServiceName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Text(
                text = stringResource(
                    R.string.gosettingsscreen_account_format,
                    if (username.isNotBlank()) username else stringResource(R.string.gosettingsscreen_not_connected)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
            )

            if (isConnected) {
                Text(
                    text = stringResource(R.string.gosettingsscreen_connection_is_healthy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                )
            } else {
                Text(
                    text = stringResource(id = chromahub.rhythm.app.R.string.streaming_status_connect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun QualitySelectionBottomSheet(
    selectedQuality: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))

    RhythmAdaptiveModalSheet(
        adaptiveType = SheetAdaptiveType.COMPACT_DIALOG,
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.primary) },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
    ) {
        StandardBottomSheetHeader(
            title = stringResource(R.string.streaming_settings_quality),
            subtitle = stringResource(R.string.streaming_settings_quality_sheet_desc),
            visible = true
        )

        val scrollState = rememberScrollState()

        AdaptiveSheetScrollContainer(
            scrollState = scrollState,
            modifier = Modifier.fillMaxWidth()
        ) { endPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(start = 24.dp, end = 24.dp + endPadding, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                streamingQualityOptions.forEachIndexed { index, option ->
                    val isSelected = selectedQuality == option.value

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = groupedBottomSheetItemShape(index, streamingQualityOptions.size),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ),
                        onClick = { onSelect(option.value) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = MaterialSymbolIcon("high_quality"),
                                contentDescription = null,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(if (isSelected) 30.dp else 26.dp)
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = option.titleRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = RhythmIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun selectedServiceLabel(selectedService: String, context: Context): String {
    val matching = StreamingServiceOptions.defaults.firstOrNull { it.id == selectedService }
    return matching?.let { context.getString(it.nameRes) } ?: context.getString(chromahub.rhythm.app.R.string.streaming_service_custom_api)
}
