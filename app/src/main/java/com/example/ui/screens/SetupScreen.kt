package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BroadcastOnHome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.SpotifyConnectionStatus
import com.example.model.SpotifyDevice
import com.example.ui.TurntableUiState

@Composable
fun SetupScreen(
    state: TurntableUiState,
    onOpenNotificationAccess: () -> Unit,
    onToggleLiveSync: (Boolean) -> Unit,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onRefreshDevices: () -> Unit,
    onTransferDevice: (SpotifyDevice) -> Unit,
    onOpenSpotify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(
                    text = "Setup",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Keep your home screen widget synced in real time with Spotify on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            NotificationShadeSyncCard(
                isGranted = state.isNotificationAccessGranted,
                isConnected = state.isMediaListenerConnected,
                onOpenSettings = onOpenNotificationAccess,
            )
        }

        item {
            LiveSyncCard(
                enabled = state.liveSyncEnabled,
                isRunning = state.isLiveSyncRunning,
                onToggle = onToggleLiveSync,
            )
        }

        item {
            SyncSpotifyPlaybackCard(
                onOpenSpotify = onOpenSpotify,
            )
        }

        // Only show optional Spotify Web API account / Connect devices if OAuth is configured
        if (state.connectionStatus !is SpotifyConnectionStatus.NeedsClientSecret) {
            item {
                OptionalSpotifyAccountCard(
                    status = state.connectionStatus,
                    onConnectSpotify = onConnectSpotify,
                    onDisconnectSpotify = onDisconnectSpotify,
                    onRefreshDevices = onRefreshDevices,
                )
            }
        }

        if (state.connectionStatus is SpotifyConnectionStatus.Connected) {
            item {
                Text(
                    text = "Spotify Connect Devices",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            if (state.devices.isEmpty()) {
                item {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "No active Spotify devices detected",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Open Spotify on your phone, desktop, or speaker and tap Refresh Devices.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(state.devices, key = { it.id }) { device ->
                    DeviceRowCard(
                        device = device,
                        onSelect = { onTransferDevice(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationShadeSyncCard(
    isGranted: Boolean,
    isConnected: Boolean,
    onOpenSettings: () -> Unit,
) {
    val active = isGranted || isConnected
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (active) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.NotificationsActive
                        },
                        contentDescription = null,
                        tint = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (active) {
                                R.string.notification_access_active_title
                            } else {
                                R.string.notification_access_title
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.notification_access_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (active) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("notification_access_settings_button"),
                ) {
                    Text(stringResource(R.string.action_notification_settings))
                }
            } else {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("grant_notification_access_button"),
                ) {
                    Text(stringResource(R.string.action_grant_notification_access))
                }
            }
        }
    }
}

@Composable
private fun LiveSyncCard(
    enabled: Boolean,
    isRunning: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        tint = if (isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) {
                            "Live Background Sync • Active"
                        } else {
                            "Live Background Sync"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Keeps the widget listening for Spotify song switches and play/pause changes even when Turntable is closed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("toggle_live_sync"),
            )
        }
    }
}

@Composable
private fun SyncSpotifyPlaybackCard(
    onOpenSpotify: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.BroadcastOnHome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Sync Spotify Playback",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1. Open the Spotify app on your phone.\n" +
                    "2. Go to Settings → Playback (or Devices) → enable Device Broadcast Status.\n" +
                    "3. Play or skip any track — Turntable automatically displays the live song title, artist, album art, and pastel colors with no login required.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))
            FilledTonalButton(
                onClick = onOpenSpotify,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("setup_open_spotify_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_open_spotify))
            }
        }
    }
}

@Composable
private fun OptionalSpotifyAccountCard(
    status: SpotifyConnectionStatus,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onRefreshDevices: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            when (status) {
                is SpotifyConnectionStatus.Connected -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = status.displayName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = status.activeDeviceName?.let { "Active: $it" }
                                    ?: "Authenticated with Spotify Web API",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = onRefreshDevices,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("refresh_devices_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Refresh Devices")
                        }
                        OutlinedButton(
                            onClick = onDisconnectSpotify,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("disconnect_spotify_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_disconnect_spotify))
                        }
                    }
                }

                is SpotifyConnectionStatus.Error -> {
                    Text(
                        text = "Sign-in Error",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onConnectSpotify,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("retry_connect_button"),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_connect_spotify))
                    }
                }

                SpotifyConnectionStatus.Connecting -> {
                    Text(
                        text = "Completing Spotify authorization...",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                SpotifyConnectionStatus.Disconnected -> {
                    Text(
                        text = "Spotify Connect Account (Optional)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Sign in only if you want remote Spotify Connect device switching across PCs and speakers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onConnectSpotify,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("connect_screen_oauth_button"),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_connect_spotify))
                    }
                }

                SpotifyConnectionStatus.NeedsClientSecret -> Unit
            }
        }
    }
}

@Composable
private fun DeviceRowCard(
    device: SpotifyDevice,
    onSelect: () -> Unit,
) {
    val icon = when (device.type.lowercase()) {
        "computer" -> Icons.Rounded.Computer
        "speaker", "avr", "tv" -> Icons.Rounded.Speaker
        else -> Icons.Rounded.Smartphone
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (device.isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onSelect)
            .testTag("spotify_device_${device.id}"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (device.isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = buildString {
                            append(device.type)
                            device.volumePercent?.let { append(" • $it% vol") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (device.isActive) {
                Text(
                    text = "Active",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "Transfer",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
