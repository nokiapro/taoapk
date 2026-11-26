/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.streaming.data.provider.XuanKenFirebaseClient
import chromahub.rhythm.app.features.streaming.domain.model.StreamingServiceId
import chromahub.rhythm.app.features.streaming.presentation.model.StreamingServiceOptions
import chromahub.rhythm.app.features.streaming.presentation.viewmodel.StreamingMusicViewModel
import chromahub.rhythm.app.network.XkMusicApiService
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.presentation.components.common.CollapsibleHeaderScreen
import chromahub.rhythm.app.shared.presentation.components.common.M3FourColorCircularLoader
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.screens.settings.TunerAnimatedSwitch

@Composable
fun StreamingServiceSetupScreen(
    serviceId: String,
    viewModel: StreamingMusicViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appSettings = remember(context) { AppSettings.getInstance(context) }
    val sessions by viewModel.serviceSessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val rememberStreamingPasswords by appSettings.rememberStreamingPasswords.collectAsState()
    val isBusy = isLoading

    val resolvedServiceId = StreamingServiceId.CUSTOM_API
    val option = remember(resolvedServiceId) {
        StreamingServiceOptions.defaults.firstOrNull { it.id == resolvedServiceId }
            ?: StreamingServiceOptions.defaults.firstOrNull()
    }
    val optionName = option?.let { stringResource(id = it.nameRes) } ?: resolvedServiceId
    val session = sessions[resolvedServiceId] ?: viewModel.getServiceSession(resolvedServiceId)
    val firebaseClient = remember(context) { XuanKenFirebaseClient.getInstance(context) }
    val isApiKeyConfigured = remember(firebaseClient) { firebaseClient.isApiKeyConfigured() }

    var email by rememberSaveable(resolvedServiceId) {
        mutableStateOf(if (firebaseClient.hasUserSession()) firebaseClient.getEmail() else "")
    }
    var password by rememberSaveable(resolvedServiceId) { mutableStateOf("") }

    val hasValidAccountPair = (email.isBlank() && password.isBlank()) || (email.isNotBlank() && password.isNotBlank())
    val primaryActionText = when {
        isBusy && session.isConnected -> R.string.streaming_service_setup_disconnecting
        isBusy -> R.string.streaming_service_setup_connecting
        session.isConnected -> R.string.streaming_service_setup_reconnect
        else -> R.string.streaming_connect
    }
    val statusText = when {
        isBusy && session.isConnected -> stringResource(id = R.string.streaming_service_setup_disconnecting)
        isBusy -> stringResource(id = R.string.streaming_service_setup_connecting)
        session.isConnected -> stringResource(id = R.string.streaming_status_connected)
        else -> stringResource(id = R.string.streaming_status_not_connected)
    }
    val oneLinerStatus = if (session.isConnected && session.username.isNotBlank()) {
        stringResource(id = R.string.streaming_service_setup_connected_as, session.username)
    } else {
        statusText
    }

    CollapsibleHeaderScreen(
        title = oneLinerStatus,
        showBackButton = true,
        onBackClick = onBackClick,
        headerDisplayMode = 1
    ) { contentModifier ->
        LazyColumn(
            modifier = modifier
                .then(contentModifier)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = MaterialSymbolIcon("cloud", filled = true),
                        contentDescription = optionName,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = optionName,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isApiKeyConfigured) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MaterialSymbolIcon(
                                if (isApiKeyConfigured) "verified_user" else "key_off",
                                filled = true
                            ),
                            contentDescription = null,
                            tint = if (isApiKeyConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(
                                    id = if (isApiKeyConfigured) {
                                        R.string.custom_api_key_configured_badge
                                    } else {
                                        R.string.custom_api_key_missing_banner
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isApiKeyConfigured) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            )
                            Text(
                                text = XkMusicApiService.BASE_URL.trimEnd('/'),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isApiKeyConfigured) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f)
                                }
                            )
                        }
                    }
                }
            }

            if (isBusy) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            M3FourColorCircularLoader(
                                modifier = Modifier.size(56.dp)
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val loadingText = if (session.isConnected) {
                                    stringResource(id = R.string.streaming_service_setup_disconnecting)
                                } else {
                                    stringResource(id = R.string.streaming_service_setup_connecting)
                                }
                                Text(
                                    text = loadingText,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.streamingservicesetup_establishing_connection),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.custom_api_optional_login_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(id = R.string.custom_api_optional_login_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )


                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("username_input"),
                                label = { Text(text = stringResource(id = R.string.custom_api_email)) },
                                placeholder = { Text(text = "email@example.com") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                                enabled = !isBusy
                            )

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("password_input"),
                                label = { Text(text = stringResource(id = R.string.streaming_service_setup_password)) },
                                placeholder = { Text(text = stringResource(id = R.string.streaming_service_setup_password_hint)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                enabled = !isBusy
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(id = R.string.streaming_service_setup_remember_password),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(id = R.string.streaming_service_setup_remember_password_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                TunerAnimatedSwitch(
                                    checked = rememberStreamingPasswords,
                                    onCheckedChange = { enabled -> appSettings.setRememberStreamingPasswords(enabled) },
                                    enabled = !isBusy
                                )
                            }
                        }
                    }
                }

                if (error != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                text = error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.connectService(
                                    serviceId = resolvedServiceId,
                                    serverUrl = XkMusicApiService.BASE_URL.trimEnd('/'),
                                    username = email,
                                    password = password
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_service_button"),
                            enabled = !isBusy && hasValidAccountPair
                        ) {
                            Text(text = stringResource(id = primaryActionText))
                        }

                        if (session.isConnected) {
                            OutlinedButton(
                                onClick = { viewModel.disconnectService(resolvedServiceId) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("disconnect_service_button"),
                                enabled = !isBusy
                            ) {
                                Text(text = stringResource(id = R.string.streaming_service_setup_disconnect))
                            }
                        }

                        if (!hasValidAccountPair && !isBusy) {
                            Text(
                                text = stringResource(id = R.string.streaming_service_setup_action_hint_api_key),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}
