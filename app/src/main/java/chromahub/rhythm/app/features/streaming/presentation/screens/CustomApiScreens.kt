/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package chromahub.rhythm.app.features.streaming.presentation.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.streaming.data.provider.BlockedSongItem
import chromahub.rhythm.app.features.streaming.data.provider.CustomSongMeta
import chromahub.rhythm.app.features.streaming.data.provider.CustomUserSettings
import chromahub.rhythm.app.features.streaming.data.provider.PurchaseRecord
import chromahub.rhythm.app.features.streaming.data.provider.VoucherInfo
import chromahub.rhythm.app.features.streaming.data.provider.VoucherValidationResult
import chromahub.rhythm.app.features.streaming.presentation.viewmodel.StreamingMusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.Material3SettingsGroup
import chromahub.rhythm.app.shared.presentation.components.Material3SettingsItem
import chromahub.rhythm.app.shared.presentation.components.common.CollapsibleHeaderScreen
import chromahub.rhythm.app.shared.presentation.components.common.M3PlaceholderType
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.shared.presentation.screens.settings.TunerAnimatedSwitch
import chromahub.rhythm.app.ui.LocalMiniPlayerPadding
import chromahub.rhythm.app.util.ImageUtils
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun AccountAndCloudSettingsScreen(
    viewModel: StreamingMusicViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val userProfile by viewModel.userProfile.collectAsState()
    val cloudSettings by viewModel.cloudSettings.collectAsState()
    val siteInfo by viewModel.siteInfo.collectAsState()

    var displayName by remember(userProfile) { mutableStateOf(userProfile?.displayName.orEmpty()) }
    var email by remember(userProfile) { mutableStateOf(userProfile?.email.orEmpty()) }

    var audioQuality by remember(cloudSettings) { mutableStateOf(cloudSettings.audioQuality) }
    var pushNewMusic by remember(cloudSettings) { mutableStateOf(cloudSettings.pushNewMusic) }
    var privateSession by remember(cloudSettings) { mutableStateOf(cloudSettings.privateSession) }
    var explicitFilter by remember(cloudSettings) { mutableStateOf(cloudSettings.explicitFilter) }
    var language by remember(cloudSettings) { mutableStateOf(cloudSettings.language) }

    var isSavingProfile by remember { mutableStateOf(false) }
    var isSavingSettings by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshSiteAndAccount()
    }

    CollapsibleHeaderScreen(
        title = stringResource(R.string.custom_api_profile_title),
        showBackButton = true,
        onBackClick = onBackClick
    ) { modifier ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(
                top = 12.dp,
                bottom = 24.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = userProfile?.username?.ifBlank { stringResource(R.string.personal_role_member) } ?: stringResource(R.string.personal_role_member),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (!userProfile?.plan.isNullOrBlank()) {
                                    Text(
                                        text = stringResource(R.string.custom_api_plan_format, userProfile?.plan.orEmpty()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            userProfile?.balance?.let { balance ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(999.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.custom_api_balance_format,
                                            formatCurrencyAmount(balance, userProfile?.currency ?: "VND")
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text(stringResource(R.string.custom_api_display_name)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("account_display_name_input")
                        )

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(stringResource(R.string.custom_api_email)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("account_email_input")
                        )

                        statusError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Button(
                            onClick = {
                                isSavingProfile = true
                                statusError = null
                                viewModel.updateUserProfile(
                                    displayName = displayName.trim(),
                                    email = email.trim().ifBlank { null }
                                ) { success, errorMsg ->
                                    isSavingProfile = false
                                    if (!success) statusError = errorMsg
                                }
                            },
                            enabled = !isSavingProfile && displayName.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("save_profile_button")
                        ) {
                            if (isSavingProfile) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.custom_api_save_profile))
                            }
                        }
                    }
                }
            }

            item {
                Material3SettingsGroup(
                    title = stringResource(R.string.custom_api_save_settings),
                    items = listOf(
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("high_quality"),
                            title = { Text(stringResource(R.string.custom_api_cloud_quality)) },
                            description = { Text(audioQuality) },
                            onClick = {
                                val order = listOf("LOW", "MEDIUM", "HIGH", "LOSSLESS")
                                val nextIndex = (order.indexOf(audioQuality.uppercase()) + 1) % order.size
                                audioQuality = order[nextIndex]
                            }
                        ),
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("notifications_active"),
                            title = { Text(stringResource(R.string.custom_api_cloud_push_sync)) },
                            trailingContent = {
                                TunerAnimatedSwitch(
                                    checked = pushNewMusic,
                                    onCheckedChange = { pushNewMusic = it }
                                )
                            }
                        ),
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("visibility_off"),
                            title = { Text(stringResource(R.string.custom_api_cloud_private_session)) },
                            trailingContent = {
                                TunerAnimatedSwitch(
                                    checked = privateSession,
                                    onCheckedChange = { privateSession = it }
                                )
                            }
                        ),
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("explicit"),
                            title = { Text(stringResource(R.string.custom_api_explicit_filter)) },
                            trailingContent = {
                                TunerAnimatedSwitch(
                                    checked = explicitFilter,
                                    onCheckedChange = { explicitFilter = it }
                                )
                            }
                        )
                    )
                )
            }

            item {
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text(stringResource(R.string.custom_api_language_code)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_language_input")
                )
            }

            item {
                FilledTonalButton(
                    onClick = {
                        isSavingSettings = true
                        statusError = null
                        viewModel.updateCloudSettings(
                            CustomUserSettings(
                                audioQuality = audioQuality,
                                pushNewMusic = pushNewMusic,
                                privateSession = privateSession,
                                explicitFilter = explicitFilter,
                                language = language.trim().ifBlank { "en" }
                            )
                        ) { success, errorMsg ->
                            isSavingSettings = false
                            if (!success) statusError = errorMsg
                        }
                    },
                    enabled = !isSavingSettings,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_cloud_settings_button")
                ) {
                    if (isSavingSettings) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.custom_api_save_settings))
                    }
                }
            }
        }
    }
}

@Composable
fun PurchasesAndVouchersScreen(
    viewModel: StreamingMusicViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val purchases by viewModel.purchases.collectAsState()
    val vouchers by viewModel.vouchers.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.loadPurchases()
        viewModel.loadVouchers()
    }

    CollapsibleHeaderScreen(
        title = stringResource(R.string.custom_api_purchases_vouchers_title),
        showBackButton = true,
        onBackClick = onBackClick
    ) { modifier ->
        Column(modifier = modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.custom_api_purchases_tab)) },
                    modifier = Modifier.testTag("purchases_tab")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.custom_api_vouchers_tab)) },
                    modifier = Modifier.testTag("vouchers_tab")
                )
            }

            if (selectedTab == 0) {
                if (purchases.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.custom_api_no_purchases),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        contentPadding = PaddingValues(
                            top = 16.dp,
                            bottom = 24.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(purchases, key = { it.id }) { purchase ->
                            PurchaseRecordCard(
                                purchase = purchase,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            } else {
                if (vouchers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.custom_api_no_vouchers),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        contentPadding = PaddingValues(
                            top = 16.dp,
                            bottom = 24.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(vouchers, key = { it.id.ifBlank { it.code } }) { voucher ->
                            VoucherCard(
                                voucher = voucher,
                                onCopyCode = { code ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("voucher", code))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.custom_api_copied_voucher),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseRecordCard(
    purchase: PurchaseRecord,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageRequest = remember(purchase.artworkUrl, purchase.songTitle, context) {
        ImageRequest.Builder(context)
            .apply(
                ImageUtils.buildImageRequest(
                    purchase.artworkUrl,
                    purchase.songTitle,
                    context.cacheDir,
                    M3PlaceholderType.TRACK
                )
            )
            .build()
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = purchase.songTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = purchase.songTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (purchase.artist.isNotBlank()) {
                    Text(
                        text = purchase.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                purchase.purchasedAt?.takeIf { it.isNotBlank() }?.let { date ->
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatCurrencyAmount(purchase.finalAmount, purchase.currency),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = purchase.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoucherCard(
    voucher: VoucherInfo,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = MaterialSymbolIcon("confirmation_number", filled = true),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = voucher.title.ifBlank { voucher.code },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = voucher.code,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (voucher.description.isNotBlank()) {
                    Text(
                        text = voucher.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                voucher.expiresAt?.takeIf { it.isNotBlank() }?.let { exp ->
                    Text(
                        text = exp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = { onCopyCode(voucher.code) },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("copy_voucher_${voucher.code}")
            ) {
                Icon(
                    imageVector = MaterialSymbolIcon("content_copy"),
                    contentDescription = stringResource(R.string.custom_api_copied_voucher)
                )
            }
        }
    }
}

@Composable
fun BlockedSongsScreen(
    viewModel: StreamingMusicViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val blockedSongs by viewModel.blockedSongs.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadBlockedSongs()
    }

    CollapsibleHeaderScreen(
        title = stringResource(R.string.custom_api_blocked_songs_title),
        showBackButton = true,
        onBackClick = onBackClick
    ) { modifier ->
        if (blockedSongs.isEmpty()) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.custom_api_no_blocked_songs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = 24.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(blockedSongs, key = { it.songId }) { item ->
                    BlockedSongRow(
                        item = item,
                        context = context,
                        onUnblock = { viewModel.unblockSong(item.songId) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockedSongRow(
    item: BlockedSongItem,
    context: Context,
    onUnblock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageRequest = remember(item.artworkUrl, item.title, context) {
        ImageRequest.Builder(context)
            .apply(
                ImageUtils.buildImageRequest(
                    item.artworkUrl,
                    item.title,
                    context.cacheDir,
                    M3PlaceholderType.TRACK
                )
            )
            .build()
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = onUnblock,
                modifier = Modifier.testTag("unblock_song_${item.songId}")
            ) {
                Text(stringResource(R.string.custom_api_unblock))
            }
        }
    }
}

@Composable
fun SongPurchaseDialog(
    song: Song,
    viewModel: StreamingMusicViewModel,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var songMeta by remember { mutableStateOf<CustomSongMeta?>(null) }
    var isLoadingMeta by remember { mutableStateOf(true) }
    var voucherCode by remember { mutableStateOf("") }
    var voucherResult by remember { mutableStateOf<VoucherValidationResult?>(null) }
    var isValidating by remember { mutableStateOf(false) }
    var isPurchasing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(song.id) {
        isLoadingMeta = true
        viewModel.getSongMeta(song.id)
            .onSuccess { songMeta = it }
            .onFailure { errorMessage = it.message }
        isLoadingMeta = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = MaterialSymbolIcon("shopping_bag", filled = true),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.custom_api_purchase_song),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${song.title} — ${song.artist}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                if (isLoadingMeta) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else {
                    val meta = songMeta
                    if (meta != null) {
                        if (meta.isPurchased && meta.isPaidTrack) {
                            Text(
                                text = stringResource(R.string.custom_api_already_purchased),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            val priceLabel = if (meta.price <= 0.0) {
                                stringResource(R.string.custom_api_price_free)
                            } else {
                                formatCurrencyAmount(meta.price, meta.currency)
                            }
                            Text(
                                text = stringResource(R.string.custom_api_purchase_price_format, priceLabel),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = voucherCode,
                            onValueChange = {
                                voucherCode = it
                                voucherResult = null
                            },
                            label = { Text(stringResource(R.string.custom_api_voucher_code_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("voucher_code_input")
                        )
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    isValidating = true
                                    errorMessage = null
                                    viewModel.validateVoucher(voucherCode.trim(), song.id)
                                        .onSuccess { voucherResult = it }
                                        .onFailure { errorMessage = it.message }
                                    isValidating = false
                                }
                            },
                            enabled = voucherCode.isNotBlank() && !isValidating,
                            modifier = Modifier.testTag("validate_voucher_button")
                        ) {
                            if (isValidating) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.custom_api_validate_voucher))
                            }
                        }
                    }

                    voucherResult?.let { vr ->
                        val color = if (vr.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        val text = if (vr.valid) {
                            stringResource(
                                R.string.custom_api_discount_applied_format,
                                formatCurrencyAmount(vr.discountAmount, vr.currency),
                                formatCurrencyAmount(vr.finalPrice, vr.currency)
                            )
                        } else {
                            vr.message.ifBlank { stringResource(R.string.custom_api_invalid_voucher) }
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            val alreadyOwned = songMeta?.let { it.isPaidTrack && it.isPurchased } == true
            Button(
                onClick = {
                    isPurchasing = true
                    errorMessage = null
                    viewModel.purchaseSong(
                        songId = song.id,
                        voucherCode = voucherCode.trim().ifBlank { null }
                    ) { result ->
                        isPurchasing = false
                        result.onSuccess { onDismiss() }
                            .onFailure { errorMessage = it.message }
                    }
                },
                enabled = !isLoadingMeta && !isPurchasing && !alreadyOwned,
                modifier = Modifier.testTag("confirm_purchase_button")
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.custom_api_confirm_purchase))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun SongReportDialog(
    song: Song,
    viewModel: StreamingMusicViewModel,
    onDismiss: () -> Unit
) {
    val reasonAudio = stringResource(R.string.custom_api_report_reason_audio)
    val reasonMetadata = stringResource(R.string.custom_api_report_reason_metadata)
    val reasonCopyright = stringResource(R.string.custom_api_report_reason_copyright)
    val reasonInappropriate = stringResource(R.string.custom_api_report_reason_inappropriate)
    val reasons = remember(reasonAudio, reasonMetadata, reasonCopyright, reasonInappropriate) {
        listOf(reasonAudio, reasonMetadata, reasonCopyright, reasonInappropriate)
    }

    var selectedReason by remember { mutableStateOf(reasons.first()) }
    var details by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = MaterialSymbolIcon("flag", filled = true),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.custom_api_report_song),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${song.title} — ${song.artist}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.custom_api_select_reason),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                reasons.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .selectable(
                                selected = selectedReason == reason,
                                role = Role.RadioButton,
                                onClick = { selectedReason = reason }
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedReason == reason,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text(stringResource(R.string.custom_api_report_details_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("report_details_input")
                )

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSubmitting = true
                    errorMessage = null
                    viewModel.reportSong(
                        songId = song.id,
                        reason = selectedReason,
                        details = details.trim().ifBlank { null }
                    ) { success, err ->
                        isSubmitting = false
                        if (success) onDismiss() else errorMessage = err
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.testTag("submit_report_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.custom_api_submit_report))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private fun formatCurrencyAmount(amount: Double, currency: String): String {
    val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
    val formatted = if (amount % 1.0 == 0.0) {
        formatter.format(amount.toLong())
    } else {
        formatter.format(amount)
    }
    return if (currency.isBlank()) formatted else "$formatted $currency"
}
