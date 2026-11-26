/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package chromahub.rhythm.app.features.streaming.presentation.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.core.domain.model.StreamingQuality
import chromahub.rhythm.app.features.streaming.data.provider.CustomUserProfile
import chromahub.rhythm.app.features.streaming.data.provider.XuanKenFirebaseClient
import chromahub.rhythm.app.features.streaming.domain.model.StreamingPlaylist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingServiceId
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.features.streaming.presentation.viewmodel.StreamingMusicViewModel
import chromahub.rhythm.app.network.XkMusicApiService
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.presentation.components.Material3SettingsGroup
import chromahub.rhythm.app.shared.presentation.components.Material3SettingsItem
import chromahub.rhythm.app.shared.presentation.components.common.M3PlaceholderType
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.ui.LocalMiniPlayerPadding
import chromahub.rhythm.app.util.ImageUtils
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.NumberFormat
import java.util.Locale

/**
 * Strictly personal playlist tab showing ONLY playlists created by the currently signed-in member.
 */
@Composable
fun PersonalPlaylistsTabScreen(
    viewModel: StreamingMusicViewModel,
    playlists: List<StreamingPlaylist>,
    userProfile: CustomUserProfile?,
    onPlaylistClick: (StreamingPlaylist) -> Unit,
    onNavigateToPersonal: () -> Unit
) {
    val context = LocalContext.current
    val client = remember(context) { XuanKenFirebaseClient.getInstance(context) }
    val isLoading by viewModel.isLoading.collectAsState()
    val isSignedIn = remember(userProfile, playlists) {
        client.hasUserSession() || (userProfile != null && !userProfile.role.equals("guest", ignoreCase = true) && userProfile.id.isNotBlank())
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToRename by remember { mutableStateOf<StreamingPlaylist?>(null) }
    var renameText by remember { mutableStateOf("") }
    var playlistToDelete by remember { mutableStateOf<StreamingPlaylist?>(null) }

    LaunchedEffect(isSignedIn) {
        if (isSignedIn) {
            viewModel.loadLibrary(forceSync = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.personal_playlists_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (isSignedIn) {
                            val memberLabel = userProfile?.displayName?.ifBlank { userProfile.username }.orEmpty()
                            if (memberLabel.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.personal_playlists_owner_subtitle, memberLabel, playlists.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isSignedIn) {
                        IconButton(
                            onClick = { viewModel.refreshLibrary() },
                            modifier = Modifier.testTag("personal_playlists_refresh_button")
                        ) {
                            Icon(
                                imageVector = MaterialSymbolIcon("refresh"),
                                contentDescription = stringResource(R.string.action_refresh)
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                newPlaylistName = ""
                                showCreateDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("create_personal_playlist_button")
                        ) {
                            Icon(
                                imageVector = RhythmIcons.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.personal_playlists_create_action))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
            ) {
                if (!isSignedIn) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = MaterialSymbolIcon("lock_person", filled = true),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.personal_playlists_guest_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.personal_playlists_guest_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onNavigateToPersonal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .testTag("playlists_go_to_personal_login_button")
                            ) {
                                Text(stringResource(R.string.personal_playlists_sign_in_cta))
                            }
                        }
                    }
                } else if (isLoading && playlists.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (playlists.isEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
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
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = MaterialSymbolIcon("queue_music", filled = true),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.personal_playlists_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.personal_playlists_empty_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    newPlaylistName = ""
                                    showCreateDialog = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("empty_create_playlist_button")
                            ) {
                                Icon(
                                    imageVector = RhythmIcons.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.personal_playlists_create_action))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 12.dp,
                            bottom = 24.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbolIcon("verified_user", filled = true),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.personal_playlists_private_notice),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        items(playlists, key = { it.id }) { playlist ->
                            PersonalPlaylistCard(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist) },
                                onRename = {
                                    renameText = playlist.name
                                    playlistToRename = playlist
                                },
                                onDelete = { playlistToDelete = playlist },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.personal_playlists_create_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text(stringResource(R.string.personal_playlists_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_playlist_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newPlaylistName.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.createPlaylist(trimmed)
                            showCreateDialog = false
                        }
                    },
                    enabled = newPlaylistName.trim().isNotEmpty(),
                    modifier = Modifier.testTag("confirm_create_playlist_button")
                ) {
                    Text(stringResource(R.string.personal_playlists_create_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    playlistToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
            title = { Text(stringResource(R.string.personal_playlists_rename_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.personal_playlists_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.renamePlaylist(target, trimmed)
                            playlistToRename = null
                        }
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    playlistToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text(stringResource(R.string.personal_playlists_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.personal_playlists_delete_confirm, target.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePlaylist(target)
                        playlistToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun PersonalPlaylistCard(
    playlist: StreamingPlaylist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageRequest = remember(playlist.artworkUri, playlist.name, context) {
        ImageRequest.Builder(context)
            .apply(
                ImageUtils.buildImageRequest(
                    playlist.artworkUri,
                    playlist.name,
                    context.cacheDir,
                    M3PlaceholderType.PLAYLIST
                )
            )
            .build()
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("personal_playlist_card_${playlist.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(14.dp))
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.personal_playlists_song_count_format, playlist.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRename) {
                Icon(
                    imageVector = MaterialSymbolIcon("edit"),
                    contentDescription = stringResource(R.string.personal_playlists_rename_title)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = MaterialSymbolIcon("delete"),
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Personal member profile screen ("Cá nhân") with clean login/register (no API config UI),
 * purchased song breakdown (Full vs Demo), and 320kbps vs WAV Lossless toggle for purchased tracks.
 */
@Composable
fun PersonalProfileTabScreen(
    viewModel: StreamingMusicViewModel,
    allSongs: List<StreamingSong>,
    onPlayPurchasedSong: (List<StreamingSong>, Int) -> Unit,
    onOpenBrowsePaidSongs: () -> Unit,
    onOpenPurchasesVouchers: () -> Unit,
    onOpenBlockedSongs: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings.getInstance(context) }
    val client = remember(context) { XuanKenFirebaseClient.getInstance(context) }
    val focusManager = LocalFocusManager.current

    val userProfile by viewModel.userProfile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val streamingQuality by appSettings.streamingQuality.collectAsState()

    val currentProfile = userProfile
    val isSignedIn = remember(currentProfile) {
        client.hasUserSession() || (currentProfile != null && !currentProfile.role.equals("guest", ignoreCase = true) && currentProfile.id.isNotBlank())
    }

    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshSiteAndAccount()
        viewModel.loadPurchases()
    }

    val purchasedPaidSongs = remember(allSongs, userProfile, isSignedIn) {
        if (!isSignedIn) emptyList() else allSongs.filter { it.isPaidTrack && it.isPurchased }
    }
    val unpurchasedPaidCount = remember(allSongs, isSignedIn) {
        if (!isSignedIn) allSongs.count { it.isPaidTrack } else allSongs.count { it.isPaidTrack && !it.isPurchased }
    }
    val freeSongsCount = remember(allSongs) {
        allSongs.count { !it.isPaidTrack && it.price <= 0.0 }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(
                top = 12.dp,
                bottom = 28.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Visual Banner Header with User Badge & Settings Shortcut
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(176.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_xkmusic_banner),
                            contentDescription = stringResource(R.string.app_name),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.25f),
                                            Color.Black.copy(alpha = 0.78f)
                                        )
                                    )
                                )
                        )

                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                                .testTag("personal_settings_button")
                        ) {
                            Icon(
                                imageVector = RhythmIcons.Settings,
                                contentDescription = stringResource(R.string.nav_settings),
                                tint = Color.White
                            )
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = if (isSignedIn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.22f),
                                    shape = RoundedCornerShape(999.dp)
                                ) {
                                    val badgeText = when {
                                        !isSignedIn -> stringResource(R.string.personal_role_guest)
                                        userProfile?.role.equals("admin", true) -> stringResource(R.string.personal_role_admin)
                                        !userProfile?.plan.isNullOrBlank() -> userProfile?.plan.orEmpty()
                                        else -> stringResource(R.string.personal_role_member)
                                    }
                                    Text(
                                        text = badgeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSignedIn) MaterialTheme.colorScheme.onPrimary else Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }

                                if (isSignedIn && userProfile?.balance != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Text(
                                            text = formatPersonalCurrency(
                                                userProfile?.balance ?: 0.0,
                                                userProfile?.currency ?: "VND"
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = if (isSignedIn) {
                                    userProfile?.displayName?.ifBlank { userProfile?.username }.orEmpty()
                                        .ifBlank { client.getUsername().ifBlank { stringResource(R.string.personal_role_member) } }
                                } else {
                                    stringResource(R.string.personal_guest_headline)
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            val subtitleText = if (isSignedIn) {
                                userProfile?.email?.ifBlank { client.getEmail() }.orEmpty()
                                    .ifBlank { stringResource(R.string.personal_signed_in_subtitle) }
                            } else {
                                stringResource(R.string.personal_guest_subtitle)
                            }
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.88f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 2. Account Login / Register OR Signed-In Actions
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
                        if (!isSignedIn) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = MaterialSymbolIcon("login", filled = true),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = stringResource(R.string.personal_auth_tab_login),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.personal_auth_login_only_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }


                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text(stringResource(R.string.personal_auth_email_label)) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("personal_email_input")
                            )

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text(stringResource(R.string.personal_auth_password_label)) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = MaterialSymbolIcon(
                                                if (passwordVisible) "visibility_off" else "visibility"
                                            ),
                                            contentDescription = stringResource(R.string.personal_toggle_password)
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { focusManager.clearFocus() }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("personal_password_input")
                            )

                            if (!error.isNullOrBlank()) {
                                Text(
                                    text = error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.connectService(
                                        serviceId = StreamingServiceId.CUSTOM_API,
                                        serverUrl = XkMusicApiService.BASE_URL,
                                        username = emailInput.trim(),
                                        password = passwordInput
                                    )
                                },
                                enabled = emailInput.isNotBlank() && passwordInput.length >= 6 && !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("personal_auth_submit_button")
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.personal_auth_tab_login),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                MetricStatChip(
                                    title = stringResource(R.string.personal_stat_purchased_full),
                                    count = purchasedPaidSongs.size,
                                    highlighted = true,
                                    modifier = Modifier.weight(1f)
                                )
                                MetricStatChip(
                                    title = stringResource(R.string.personal_stat_unpurchased),
                                    count = unpurchasedPaidCount,
                                    highlighted = false,
                                    modifier = Modifier.weight(1f)
                                )
                                MetricStatChip(
                                    title = stringResource(R.string.personal_stat_free),
                                    count = freeSongsCount,
                                    highlighted = false,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedButton(
                                onClick = { viewModel.disconnectService(StreamingServiceId.CUSTOM_API) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("personal_sign_out_button")
                            ) {
                                Icon(
                                    imageVector = MaterialSymbolIcon("logout"),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.personal_sign_out_action))
                            }
                        }
                    }
                }
            }

            // 3. Audio Quality Selection ONLY for Purchased Songs (320kbps vs WAV)
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = MaterialSymbolIcon("high_quality", filled = true),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.personal_quality_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = stringResource(R.string.personal_quality_purchased_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val isWavSelected = streamingQuality.equals(StreamingQuality.LOSSLESS.name, ignoreCase = true)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            QualityOptionCard(
                                title = stringResource(R.string.personal_quality_mp3_320),
                                subtitle = stringResource(R.string.personal_quality_mp3_320_desc),
                                selected = !isWavSelected,
                                onClick = { viewModel.setStreamingQuality(StreamingQuality.HIGH) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quality_320kbps_option")
                            )
                            QualityOptionCard(
                                title = stringResource(R.string.personal_quality_wav),
                                subtitle = stringResource(R.string.personal_quality_wav_desc),
                                selected = isWavSelected,
                                onClick = { viewModel.setStreamingQuality(StreamingQuality.LOSSLESS) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quality_wav_option")
                            )
                        }
                    }
                }
            }

            // 4. All Purchased Songs Grouped Into 1 Compact Section Card
            item(key = "personal_purchased_group_card") {
                var showPurchasedList by remember { mutableStateOf(false) }
                val activeQualityLabel = if (streamingQuality.equals(StreamingQuality.LOSSLESS.name, true)) "WAV" else "320kbps"

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("personal_purchased_summary_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = MaterialSymbolIcon("library_music", filled = true),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = stringResource(R.string.personal_purchased_songs_section_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.personal_purchased_summary_count,
                                            purchasedPaidSongs.size
                                        ) + " • " + activeQualityLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            TextButton(onClick = onOpenBrowsePaidSongs) {
                                Text(stringResource(R.string.personal_browse_paid_songs_cta))
                            }
                        }

                        if (purchasedPaidSongs.isEmpty()) {
                            Text(
                                text = if (isSignedIn) {
                                    stringResource(R.string.personal_purchased_empty_signed_in)
                                } else {
                                    stringResource(R.string.personal_purchased_empty_guest)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = onOpenBrowsePaidSongs,
                                modifier = Modifier.testTag("personal_explore_paid_songs_button")
                            ) {
                                Text(stringResource(R.string.library_tab_paid_songs))
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onPlayPurchasedSong(purchasedPaidSongs, 0) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("personal_play_all_purchased_button")
                                ) {
                                    Icon(
                                        imageVector = RhythmIcons.Play,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.personal_purchased_play_all),
                                        maxLines = 1
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { showPurchasedList = !showPurchasedList },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("personal_toggle_purchased_list_button")
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbolIcon(
                                            if (showPurchasedList) "expand_less" else "expand_more"
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(
                                            if (showPurchasedList) R.string.personal_purchased_collapse
                                            else R.string.personal_purchased_view_list
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }

                            if (showPurchasedList) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    purchasedPaidSongs.forEachIndexed { index, song ->
                                        PurchasedSongRow(
                                            song = song,
                                            qualityLabel = activeQualityLabel,
                                            onPlay = { onPlayPurchasedSong(purchasedPaidSongs, index) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Account & Store Shortcuts (No API management items!)
            item {
                Material3SettingsGroup(
                    title = stringResource(R.string.custom_api_account_section),
                    items = listOf(
                        Material3SettingsItem(
                            icon = MaterialSymbolIcon("shopping_bag"),
                            title = { Text(stringResource(R.string.custom_api_purchases_vouchers_title)) },
                            description = { Text(stringResource(R.string.custom_api_purchases_vouchers_desc)) },
                            onClick = onOpenPurchasesVouchers
                        ),
                        Material3SettingsItem(
                            icon = RhythmIcons.Block,
                            title = { Text(stringResource(R.string.custom_api_blocked_songs_title)) },
                            description = { Text(stringResource(R.string.custom_api_blocked_songs_desc)) },
                            onClick = onOpenBlockedSongs
                        ),
                        Material3SettingsItem(
                            icon = RhythmIcons.Settings,
                            title = { Text(stringResource(R.string.nav_settings)) },
                            description = { Text(stringResource(R.string.personal_app_settings_desc)) },
                            onClick = onOpenSettings
                        )
                    )
                )
            }
        }
    }
}

@Composable
private fun MetricStatChip(
    title: String,
    count: Int,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun QualityOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick),
        color = containerColor,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                if (selected) {
                    Icon(
                        imageVector = RhythmIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun PurchasedSongRow(
    song: StreamingSong,
    qualityLabel: String,
    onPlay: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        onClick = onPlay
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .apply(
                        ImageUtils.buildImageRequest(
                            song.artworkUri,
                            song.title,
                            context.cacheDir,
                            M3PlaceholderType.TRACK
                        )
                    )
                    .build(),
                contentDescription = song.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.personal_purchased_full_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = qualityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            FilledIconButton(
                onClick = onPlay,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = RhythmIcons.Play,
                    contentDescription = stringResource(R.string.action_play)
                )
            }
        }
    }
}

private fun formatPersonalCurrency(amount: Double, currency: String): String {
    val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
    val num = if (amount % 1.0 == 0.0) formatter.format(amount.toLong()) else formatter.format(amount)
    return if (currency.isBlank()) num else "$num $currency"
}
