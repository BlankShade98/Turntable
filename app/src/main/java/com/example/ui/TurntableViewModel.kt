package com.example.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.MediaKeyBridge
import com.example.data.MediaNotificationListenerService
import com.example.data.SpotifyRepository
import com.example.data.SpotifySyncService
import com.example.data.WidgetStateCache
import com.example.model.PlaybackTrack
import com.example.model.SpotifyConnectionStatus
import com.example.model.SpotifyDevice
import com.example.model.WidgetStyleConfig
import com.example.widget.TurntableWidgetReceiver
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TurntableUiState(
    val track: PlaybackTrack = PlaybackTrack(),
    val style: WidgetStyleConfig = WidgetStyleConfig(),
    val connectionStatus: SpotifyConnectionStatus = SpotifyConnectionStatus.Disconnected,
    val devices: List<SpotifyDevice> = emptyList(),
    val isSyncing: Boolean = false,
    val placedWidgetCount: Int = 0,
    val isPinSupported: Boolean = false,
    val isSpotifyInstalled: Boolean = false,
    val statusMessage: String? = null,
    val showManualPinHelp: Boolean = false,
    val liveSyncEnabled: Boolean = true,
    val isLiveSyncRunning: Boolean = false,
    val isNotificationAccessGranted: Boolean = false,
    val isMediaListenerConnected: Boolean = false,
)

class TurntableViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = SpotifyRepository(appContext)

    private val devicesFlow = MutableStateFlow<List<SpotifyDevice>>(emptyList())
    private val isSyncingFlow = MutableStateFlow(false)
    private val isConnectingFlow = MutableStateFlow(false)
    private val authErrorFlow = MutableStateFlow<String?>(null)
    private val statusMessageFlow = MutableStateFlow<String?>(null)
    private val showManualPinHelpFlow = MutableStateFlow(false)
    private val placedWidgetCountFlow = MutableStateFlow(countPlacedWidgets())
    private val notificationAccessGrantedFlow = MutableStateFlow(
        MediaNotificationListenerService.isNotificationAccessGranted(appContext)
    )

    private var pollingJob: Job? = null

    val uiState: StateFlow<TurntableUiState> = combine(
        WidgetStateCache.trackFlow,
        WidgetStateCache.styleFlow,
        repository.preferences.authFlow,
        devicesFlow,
        combine(
            isSyncingFlow,
            isConnectingFlow,
            authErrorFlow,
            statusMessageFlow,
            showManualPinHelpFlow,
        ) { syncing, connecting, authErr, statusMsg, pinHelp ->
            TransientUiFlags(syncing, connecting, authErr, statusMsg, pinHelp)
        },
        combine(
            placedWidgetCountFlow,
            repository.preferences.liveSyncEnabledFlow,
            SpotifySyncService.isRunningFlow,
            notificationAccessGrantedFlow,
            MediaNotificationListenerService.isConnectedFlow,
        ) { count, syncEnabled, syncRunning, notifGranted, listenerConnected ->
            SyncStatusFlags(count, syncEnabled, syncRunning, notifGranted, listenerConnected)
        },
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val track = values[0] as PlaybackTrack
        val style = values[1] as WidgetStyleConfig
        val auth = values[2] as com.example.data.StoredSpotifyAuth
        @Suppress("UNCHECKED_CAST")
        val devices = values[3] as List<SpotifyDevice>
        val flags = values[4] as TransientUiFlags
        val syncFlags = values[5] as SyncStatusFlags

        val connection = when {
            !repository.isClientIdConfigured() -> SpotifyConnectionStatus.NeedsClientSecret
            flags.connecting -> SpotifyConnectionStatus.Connecting
            flags.authError != null -> SpotifyConnectionStatus.Error(flags.authError)
            auth.isAuthenticated -> SpotifyConnectionStatus.Connected(
                displayName = auth.displayName.ifBlank { "Spotify Account" },
                activeDeviceName = track.deviceName.ifBlank {
                    devices.firstOrNull { it.isActive }?.name
                },
            )
            else -> SpotifyConnectionStatus.Disconnected
        }

        TurntableUiState(
            track = track,
            style = style,
            connectionStatus = connection,
            devices = devices,
            isSyncing = flags.syncing,
            placedWidgetCount = syncFlags.placedWidgetCount,
            isPinSupported = isPinAppWidgetSupported(),
            isSpotifyInstalled = MediaKeyBridge.isSpotifyInstalled(appContext),
            statusMessage = flags.statusMessage,
            showManualPinHelp = flags.showManualPinHelp,
            liveSyncEnabled = syncFlags.liveSyncEnabled,
            isLiveSyncRunning = syncFlags.isLiveSyncRunning,
            isNotificationAccessGranted = syncFlags.isNotificationAccessGranted,
            isMediaListenerConnected = syncFlags.isMediaListenerConnected,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TurntableUiState(
            isPinSupported = isPinAppWidgetSupported(),
            isSpotifyInstalled = MediaKeyBridge.isSpotifyInstalled(appContext),
            placedWidgetCount = countPlacedWidgets(),
            isNotificationAccessGranted = MediaNotificationListenerService.isNotificationAccessGranted(appContext),
        ),
    )

    init {
        refreshWidgetCount()
        viewModelScope.launch {
            WidgetStateCache.ensureWarmed(appContext)
            repository.refreshDynamicPaletteFromCurrentArt()
        }
    }

    fun onForegroundStarted() {
        refreshWidgetCount()
        notificationAccessGrantedFlow.value =
            MediaNotificationListenerService.isNotificationAccessGranted(appContext)
        MediaNotificationListenerService.requestSyncNow()
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            SpotifySyncService.startIfEnabled(appContext)
            WidgetUpdater.pushWidgetUpdate(appContext)
            if (repository.preferences.getAuth().isAuthenticated) {
                refreshNow()
                loadDevices()
            }
            while (isActive) {
                delay(5_000L)
                if (repository.preferences.getAuth().isAuthenticated) {
                    repository.refreshPlayback()
                }
            }
        }
    }

    fun openNotificationAccessSettings() {
        MediaNotificationListenerService.openNotificationAccessSettings(appContext)
    }

    fun setLiveSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.preferences.setLiveSyncEnabled(enabled)
            if (enabled) {
                val started = SpotifySyncService.start(appContext)
                statusMessageFlow.value = if (started) {
                    "Live background sync active"
                } else {
                    "Could not start live sync service"
                }
            } else {
                SpotifySyncService.stop(appContext)
                statusMessageFlow.value = "Live background sync paused"
            }
        }
    }

    fun onForegroundStopped() {
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun prepareSpotifyAuthUri(): Uri? {
        authErrorFlow.value = null
        return repository.buildAuthorizationUri()
    }

    fun handleAuthCallback(uri: Uri) {
        viewModelScope.launch {
            isConnectingFlow.value = true
            authErrorFlow.value = null
            val result = repository.handleAuthCallback(uri)
            isConnectingFlow.value = false
            result.onSuccess { displayName ->
                statusMessageFlow.value = "Connected as $displayName"
                loadDevices()
            }.onFailure { err ->
                authErrorFlow.value = err.message ?: "Spotify sign-in failed"
            }
        }
    }

    fun disconnectSpotify() {
        viewModelScope.launch {
            repository.disconnect()
            devicesFlow.value = emptyList()
            authErrorFlow.value = null
            statusMessageFlow.value = "Disconnected Spotify account"
        }
    }

    fun onPlayPause() {
        viewModelScope.launch {
            isSyncingFlow.value = true
            val result = repository.togglePlayPause()
            isSyncingFlow.value = false
            result.onSuccess { msg ->
                statusMessageFlow.value = msg
            }.onFailure { err ->
                statusMessageFlow.value = err.message
            }
        }
    }

    fun onNextTrack() {
        viewModelScope.launch {
            isSyncingFlow.value = true
            val result = repository.nextTrack()
            isSyncingFlow.value = false
            result.onSuccess { msg ->
                statusMessageFlow.value = msg
            }.onFailure { err ->
                statusMessageFlow.value = err.message
            }
        }
    }

    fun onPreviousTrack() {
        viewModelScope.launch {
            isSyncingFlow.value = true
            val result = repository.previousTrack()
            isSyncingFlow.value = false
            result.onSuccess { msg ->
                statusMessageFlow.value = msg
            }.onFailure { err ->
                statusMessageFlow.value = err.message
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            isSyncingFlow.value = true
            val result = repository.refreshPlayback()
            isSyncingFlow.value = false
            result.onFailure { err ->
                statusMessageFlow.value = err.message
            }
            refreshWidgetCount()
        }
    }

    fun sendMediaKeyPlayPause() {
        MediaKeyBridge.playPause(appContext)
        statusMessageFlow.value = "Sent Play/Pause media key to active device player"
    }

    fun sendMediaKeyNext() {
        MediaKeyBridge.next(appContext)
        statusMessageFlow.value = "Sent Next Track media key to active device player"
    }

    fun sendMediaKeyPrevious() {
        MediaKeyBridge.previous(appContext)
        statusMessageFlow.value = "Sent Previous Track media key to active device player"
    }

    fun openSpotifyApp() {
        val opened = MediaKeyBridge.openSpotify(appContext)
        if (!opened) {
            statusMessageFlow.value = "Could not open Spotify on this device"
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            repository.fetchDevices().onSuccess { list ->
                devicesFlow.value = list
            }
        }
    }

    fun transferToDevice(device: SpotifyDevice) {
        viewModelScope.launch {
            isSyncingFlow.value = true
            repository.transferPlayback(device.id)
                .onSuccess {
                    statusMessageFlow.value = "Playing on ${device.name}"
                    loadDevices()
                }
                .onFailure { err ->
                    statusMessageFlow.value = err.message ?: "Could not transfer playback"
                }
            isSyncingFlow.value = false
        }
    }

    fun updateStyle(transform: (WidgetStyleConfig) -> WidgetStyleConfig) {
        viewModelScope.launch {
            val current = repository.preferences.getStyle()
            val updated = transform(current)
            repository.preferences.updateWidgetStyle(updated)
            WidgetUpdater.pushWidgetUpdate(appContext)
        }
    }

    fun requestPinWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = AppWidgetManager.getInstance(appContext)
            if (manager.isRequestPinAppWidgetSupported) {
                val provider = ComponentName(appContext, TurntableWidgetReceiver::class.java)
                val requested = manager.requestPinAppWidget(provider, null, null)
                if (requested) {
                    statusMessageFlow.value = "Confirm adding Turntable in your launcher prompt"
                    viewModelScope.launch {
                        repeat(5) {
                            delay(1_200L)
                            refreshWidgetCount()
                        }
                    }
                    return
                }
            }
        }
        showManualPinHelpFlow.value = true
    }

    fun dismissManualPinHelp() {
        showManualPinHelpFlow.value = false
    }

    fun clearStatusMessage() {
        statusMessageFlow.value = null
    }

    private fun refreshWidgetCount() {
        placedWidgetCountFlow.value = countPlacedWidgets()
    }

    private fun countPlacedWidgets(): Int {
        return runCatching {
            val manager = AppWidgetManager.getInstance(appContext)
            val provider = ComponentName(appContext, TurntableWidgetReceiver::class.java)
            manager.getAppWidgetIds(provider).size
        }.getOrDefault(0)
    }

    private fun isPinAppWidgetSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching {
                AppWidgetManager.getInstance(appContext).isRequestPinAppWidgetSupported
            }.getOrDefault(false)
    }

    private data class TransientUiFlags(
        val syncing: Boolean,
        val connecting: Boolean,
        val authError: String?,
        val statusMessage: String?,
        val showManualPinHelp: Boolean,
    )

    private data class SyncStatusFlags(
        val placedWidgetCount: Int,
        val liveSyncEnabled: Boolean,
        val isLiveSyncRunning: Boolean,
        val isNotificationAccessGranted: Boolean,
        val isMediaListenerConnected: Boolean,
    )
}
