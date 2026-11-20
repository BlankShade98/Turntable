package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.util.Base64
import com.example.BuildConfig
import com.example.R
import com.example.model.PlaybackTrack
import com.example.model.SpotifyDevice
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class SpotifyRepository(
    context: Context,
    val preferences: TurntablePreferences = TurntablePreferences(context),
) {
    private val appContext = context.applicationContext
    private val accountsApi = SpotifyNetworkClient.accountsService
    private val webApi = SpotifyNetworkClient.webApiService
    private val oEmbedApi = SpotifyNetworkClient.oEmbedService
    private val deezerApi = SpotifyNetworkClient.deezerService
    private val itunesApi = SpotifyNetworkClient.itunesService

    companion object {
        const val REDIRECT_URI = "turntable://spotify-callback"
        private const val SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing"
        private val artFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val artUrlCache = ConcurrentHashMap<String, String>()
        private val artJobLock = Any()

        @Volatile
        private var activeArtFetchTrackKey: String = ""

        @Volatile
        private var pendingArtTrackId: String = ""

        @Volatile
        private var activeArtFetchJob: Job? = null
    }

    fun isClientIdConfigured(): Boolean {
        val id = BuildConfig.SPOTIFY_CLIENT_ID
        return id.isNotBlank() && id != "YOUR_SPOTIFY_CLIENT_ID"
    }

    suspend fun buildAuthorizationUri(): Uri? {
        if (!isClientIdConfigured()) return null
        val verifier = generateRandomUrlSafe(64)
        val state = generateRandomUrlSafe(24)
        preferences.savePkceChallenge(verifier = verifier, state = state)

        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build()
    }

    suspend fun handleAuthCallback(callbackUri: Uri): Result<String> {
        val error = callbackUri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Spotify authorization canceled: $error"))
        }
        val code = callbackUri.getQueryParameter("code")
        val returnedState = callbackUri.getQueryParameter("state")
        if (code.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Missing authorization code from Spotify."))
        }

        val storedAuth = preferences.getAuth()
        if (storedAuth.oauthState.isNotBlank() && returnedState != storedAuth.oauthState) {
            return Result.failure(IllegalStateException("OAuth state mismatch. Please try connecting again."))
        }
        if (storedAuth.pkceVerifier.isBlank()) {
            return Result.failure(IllegalStateException("Missing PKCE verifier. Please retry sign-in."))
        }

        val tokenResponse = runCatching {
            accountsApi.exchangeCode(
                code = code,
                redirectUri = REDIRECT_URI,
                clientId = BuildConfig.SPOTIFY_CLIENT_ID,
                codeVerifier = storedAuth.pkceVerifier,
            )
        }.getOrElse { ex ->
            return Result.failure(IllegalStateException("Network error contacting Spotify: ${ex.message}"))
        }

        if (!tokenResponse.isSuccessful || tokenResponse.body() == null) {
            return Result.failure(
                IllegalStateException("Token exchange failed (HTTP ${tokenResponse.code()}).")
            )
        }

        val tokenBody = tokenResponse.body()!!
        val expiresAt = System.currentTimeMillis() + (tokenBody.expiresIn * 1000L) - 30_000L

        val profileName = runCatching {
            val profileResp = webApi.getCurrentUser("Bearer ${tokenBody.accessToken}")
            profileResp.body()?.displayName ?: profileResp.body()?.id
        }.getOrNull() ?: "Spotify Listener"

        preferences.saveAuthTokens(
            accessToken = tokenBody.accessToken,
            refreshToken = tokenBody.refreshToken.orEmpty(),
            expiresAtEpochMs = expiresAt,
            displayName = profileName,
        )

        refreshPlayback()
        WidgetUpdater.schedulePeriodicSync(appContext)
        return Result.success(profileName)
    }

    suspend fun disconnect() {
        WidgetStateCache.setLiveArtBitmap(null)
        preferences.clearAuth()
        AlbumArtCache.clearCache(appContext)
        extractAndSavePaletteFromBitmap(null, "", persist = true)
        WidgetUpdater.cancelPeriodicSync(appContext)
        WidgetUpdater.pushWidgetUpdate(appContext)
    }

    suspend fun refreshDynamicPaletteFromCurrentArt() {
        if (!WidgetStateCache.isWarmed) {
            WidgetStateCache.ensureWarmed(appContext)
        }
        val track = WidgetStateCache.track
        extractAndSavePaletteFromBitmap(
            liveBitmap = WidgetStateCache.liveArtBitmap,
            localArtPath = track.localArtPath,
            persist = !track.isPlaying,
        )
    }

    private suspend fun extractAndSavePaletteFromBitmap(
        liveBitmap: Bitmap?,
        localArtPath: String,
        persist: Boolean = false,
    ) {
        val bitmap = liveBitmap?.takeIf { !it.isRecycled }
            ?: AlbumArtCache.decodeDownsampledWidgetBitmap(
                filePath = localArtPath,
                context = appContext,
                targetSizeDp = 96,
            )
            ?: AlbumArtCache.decodePlaceholderWidgetBitmap(
                context = appContext,
                resId = R.drawable.img_vinyl_placeholder,
                targetSizeDp = 96,
            )
            ?: return
        val palette = AlbumColorExtractor.extractPastelPalette(bitmap)
        WidgetStateCache.updatePalette(palette)
        if (persist) {
            artFetchScope.launch { preferences.saveDynamicPalette(palette) }
        }
    }

    /**
     * Enforces the idle-only disk cache policy:
     * - When Spotify media IS playing: ensures live art is in memory, then cleans cached
     *   album art files and cached song/artist metadata from disk.
     * - When NO Spotify media is playing: stores ONLY the last track's album art, song name,
     *   and artist name in cache so the widget retains them while idle or across restarts.
     */
    suspend fun syncCacheForPlaybackState(track: PlaybackTrack) {
        if (track.isPlaying) {
            // Load into RAM first if resuming from an idle cached track before deleting disk cache
            if (WidgetStateCache.liveArtBitmap == null && track.localArtPath.isNotBlank()) {
                WidgetStateCache.ensureLiveArtLoadedFromFile(track.localArtPath, appContext)
            }
            AlbumArtCache.clearIdleCache(appContext)
            preferences.clearCachedTrack()
            val latest = WidgetStateCache.track
            if (latest.isPlaying && latest.localArtPath.isNotBlank()) {
                WidgetStateCache.track = latest.copy(localArtPath = "")
            }
        } else if (track.hasTrack) {
            val liveBmp = WidgetStateCache.liveArtBitmap
                ?: track.localArtPath.takeIf { it.isNotBlank() }?.let {
                    AlbumArtCache.decodeDownsampledWidgetBitmap(it, appContext, targetSizeDp = 160)
                }
            val cachedArtPath = if (liveBmp != null && !liveBmp.isRecycled) {
                AlbumArtCache.saveLastIdleArt(appContext, liveBmp)
            } else {
                track.localArtPath.takeIf { it.isNotBlank() && AlbumArtCache.isValidImageFile(File(it)) }.orEmpty()
            }
            val idleCachedTrack = track.copy(
                localArtPath = cachedArtPath,
                isPlaying = false,
            )
            WidgetStateCache.track = idleCachedTrack
            preferences.saveTrack(idleCachedTrack)
            extractAndSavePaletteFromBitmap(liveBmp, cachedArtPath, persist = true)
        }
    }

    /**
     * Called when Spotify's MediaSession is destroyed or its notification is dismissed.
     * Marks playback paused and stores the last album art, song name, and artist name in cache.
     */
    suspend fun handleSpotifySessionInactive() {
        if (!WidgetStateCache.isWarmed) {
            WidgetStateCache.ensureWarmed(appContext)
        }
        val current = WidgetStateCache.track
        if (!current.hasTrack) return
        val pausedTrack = current.copy(isPlaying = false)
        WidgetStateCache.track = pausedTrack
        syncCacheForPlaybackState(pausedTrack)
        WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = WidgetStateCache.track)
    }

    /**
     * Handles real-time track metadata and direct album art Bitmaps straight from
     * Android's notification shade / active Spotify MediaSession (< 15ms, zero network wait).
     */
    suspend fun handleMediaSessionMetadata(
        mediaId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        isPlaying: Boolean?,
        directBitmap: Bitmap?,
        artUriString: String,
    ) {
        try {
            if (!WidgetStateCache.isWarmed) {
                WidgetStateCache.ensureWarmed(appContext)
            }
            val previous = WidgetStateCache.track

            val incomingTitle = title.trim()
            val incomingArtist = artist.trim()
            val incomingAlbum = album.trim()
            val mediaIdKey = extractSpotifyId(mediaId)

            val sameId = mediaIdKey.isNotBlank() && mediaIdKey == previous.trackId
            val sameTitleArtist = incomingTitle.isNotBlank() &&
                incomingTitle.equals(previous.title, ignoreCase = true) &&
                (incomingArtist.isBlank() || incomingArtist.equals(previous.artist, ignoreCase = true))
            val isLikelySameTrack = sameId || sameTitleArtist || (incomingTitle.isBlank() && directBitmap != null)

            val resolvedTitle = incomingTitle.ifBlank { if (isLikelySameTrack) previous.title else "" }
            val resolvedArtist = incomingArtist.ifBlank { if (isLikelySameTrack) previous.artist else "" }
            val resolvedAlbum = incomingAlbum.ifBlank { if (isLikelySameTrack) previous.album else "" }

            if (resolvedTitle.isBlank() && directBitmap == null) return

            val titleArtistKey = if (resolvedTitle.isNotBlank() || resolvedArtist.isNotBlank()) {
                "ta:${resolvedTitle.lowercase()}|${resolvedArtist.lowercase()}"
            } else {
                ""
            }
            val genericAlbums = setOf("single", "unknown", "unknown album", "various artists")
            val albumKey = if (
                resolvedAlbum.isNotBlank() &&
                resolvedArtist.isNotBlank() &&
                resolvedAlbum.lowercase() !in genericAlbums
            ) {
                "album:${resolvedAlbum.lowercase()}|${resolvedArtist.lowercase()}"
            } else {
                ""
            }

            val normalizedId = when {
                mediaIdKey.isNotBlank() -> mediaIdKey
                isLikelySameTrack && previous.trackId.isNotBlank() -> previous.trackId
                else -> titleArtistKey
            }

            val sameTrack = (normalizedId.isNotBlank() && normalizedId == previous.trackId) || sameTitleArtist

            // 1. Obtain live Bitmap in memory from MediaSession/notification or artwork URI
            var resolvedBitmap: Bitmap? = null
            var ownsResolvedBitmap = false

            if (directBitmap != null && !directBitmap.isRecycled && directBitmap.width >= 32 && directBitmap.height >= 32) {
                resolvedBitmap = directBitmap
            } else if (artUriString.isNotBlank()) {
                val loaded = when {
                    artUriString.startsWith("content://") || artUriString.startsWith("file://") -> {
                        AlbumArtCache.loadBitmapFromContentUri(appContext, artUriString)
                    }
                    artUriString.startsWith("http://") || artUriString.startsWith("https://") -> {
                        AlbumArtCache.downloadBitmap(artUriString)
                    }
                    else -> null
                }
                if (loaded != null) {
                    resolvedBitmap = loaded
                    ownsResolvedBitmap = true
                }
            }

            val hasVerifiedArt = resolvedBitmap != null
            if (hasVerifiedArt) {
                WidgetStateCache.setLiveArtBitmap(resolvedBitmap)
                if (ownsResolvedBitmap && resolvedBitmap != null && !resolvedBitmap.isRecycled) {
                    runCatching { resolvedBitmap.recycle() }
                }
                pendingArtTrackId = ""
                synchronized(artJobLock) {
                    activeArtFetchJob?.cancel()
                    activeArtFetchJob = null
                    activeArtFetchTrackKey = ""
                }
            } else {
                // Ensure existing idle cached art is loaded into RAM so the widget never flashes
                if (WidgetStateCache.liveArtBitmap == null && previous.localArtPath.isNotBlank()) {
                    WidgetStateCache.ensureLiveArtLoadedFromFile(previous.localArtPath, appContext)
                }
                pendingArtTrackId = normalizedId
            }

            val resolvedPlaying = isPlaying ?: if (sameTrack) {
                previous.isPlaying
            } else {
                true
            }

            val updatedTrack = PlaybackTrack(
                trackId = normalizedId,
                title = resolvedTitle,
                artist = resolvedArtist,
                album = resolvedAlbum,
                albumArtUrl = if (artUriString.startsWith("http")) artUriString else previous.albumArtUrl,
                localArtPath = if (resolvedPlaying) "" else previous.localArtPath,
                durationMs = if (durationMs > 0L) durationMs else previous.durationMs,
                progressMs = if (sameTrack) previous.progressMs else 0L,
                isPlaying = resolvedPlaying,
                deviceName = previous.deviceName.ifBlank { "This device" },
                lastUpdatedMs = System.currentTimeMillis(),
            )

            if (hasVerifiedArt) {
                extractAndSavePaletteFromBitmap(
                    liveBitmap = WidgetStateCache.liveArtBitmap,
                    localArtPath = "",
                    persist = !resolvedPlaying,
                )
            }

            WidgetStateCache.track = updatedTrack
            WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = updatedTrack)
            syncCacheForPlaybackState(updatedTrack)
        } finally {
            if (directBitmap != null && !directBitmap.isRecycled) {
                runCatching { directBitmap.recycle() }
            }
        }
    }

    suspend fun handleSpotifyBroadcastMetadata(
        rawTrackId: String,
        trackName: String,
        artist: String,
        album: String,
        durationMs: Long,
        playingOverride: Boolean? = null,
    ) {
        if (!WidgetStateCache.isWarmed) {
            WidgetStateCache.ensureWarmed(appContext)
        }
        val previous = WidgetStateCache.track

        val trackIdKey = extractSpotifyId(rawTrackId)
        val incomingTitle = trackName.trim()
        val incomingArtist = artist.trim()
        val incomingAlbum = album.trim()

        val sameSpotifyId = trackIdKey.isNotBlank() && trackIdKey == previous.trackId
        val sameIncomingTitle = incomingTitle.isNotBlank() &&
            incomingTitle.equals(previous.title, ignoreCase = true) &&
            (incomingArtist.isBlank() || incomingArtist.equals(previous.artist, ignoreCase = true))
        val isLikelySameTrack = sameSpotifyId || (trackIdKey.isBlank() && sameIncomingTitle)

        // Never inherit artist/album from the PREVIOUS song when a new song arrives
        val resolvedTitle = incomingTitle.ifBlank { if (isLikelySameTrack) previous.title else "" }
        val resolvedArtist = incomingArtist.ifBlank { if (isLikelySameTrack) previous.artist else "" }
        val resolvedAlbum = incomingAlbum.ifBlank { if (isLikelySameTrack) previous.album else "" }

        val titleArtistKey = if (resolvedTitle.isNotBlank() || resolvedArtist.isNotBlank()) {
            "ta:${resolvedTitle.lowercase()}|${resolvedArtist.lowercase()}"
        } else {
            ""
        }
        val genericAlbums = setOf("single", "unknown", "unknown album", "various artists")
        val albumKey = if (
            resolvedAlbum.isNotBlank() &&
            resolvedArtist.isNotBlank() &&
            resolvedAlbum.lowercase() !in genericAlbums
        ) {
            "album:${resolvedAlbum.lowercase()}|${resolvedArtist.lowercase()}"
        } else {
            ""
        }

        val matchesTitleArtist = resolvedTitle.isNotBlank() &&
            resolvedTitle.equals(previous.title, ignoreCase = true) &&
            resolvedArtist.equals(previous.artist, ignoreCase = true)

        val normalizedId = when {
            trackIdKey.isNotBlank() -> trackIdKey
            matchesTitleArtist && previous.trackId.isNotBlank() -> previous.trackId
            else -> titleArtistKey
        }

        val sameTrack = (normalizedId.isNotBlank() && normalizedId == previous.trackId) || matchesTitleArtist

        if (WidgetStateCache.liveArtBitmap == null && previous.localArtPath.isNotBlank()) {
            WidgetStateCache.ensureLiveArtLoadedFromFile(previous.localArtPath, appContext)
        }
        val hasImmediateArt = sameTrack && WidgetStateCache.liveArtBitmap != null && pendingArtTrackId != normalizedId
        if (hasImmediateArt) {
            if (pendingArtTrackId == normalizedId) pendingArtTrackId = ""
        } else {
            pendingArtTrackId = normalizedId
        }

        val resolvedPlaying = playingOverride ?: if (sameTrack) {
            previous.isPlaying
        } else {
            true
        }

        val immediateTrack = PlaybackTrack(
            trackId = normalizedId,
            title = resolvedTitle,
            artist = resolvedArtist,
            album = resolvedAlbum,
            albumArtUrl = if (hasImmediateArt) {
                artUrlCache[normalizedId] ?: previous.albumArtUrl
            } else {
                previous.albumArtUrl
            },
            localArtPath = if (resolvedPlaying) "" else previous.localArtPath,
            durationMs = if (durationMs > 0L) durationMs else previous.durationMs,
            progressMs = if (sameTrack) previous.progressMs else 0L,
            isPlaying = resolvedPlaying,
            deviceName = previous.deviceName.ifBlank { "This device" },
            lastUpdatedMs = System.currentTimeMillis(),
        )

        // Push to the home screen FIRST from hot memory (< 10ms)
        WidgetStateCache.track = immediateTrack
        WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = immediateTrack)
        artFetchScope.launch { syncCacheForPlaybackState(immediateTrack) }

        if (!hasImmediateArt) {
            val fetchJob = synchronized(artJobLock) {
                val existing = activeArtFetchJob
                if (activeArtFetchTrackKey == normalizedId && existing?.isActive == true) {
                    existing
                } else {
                    existing?.cancel()
                    activeArtFetchTrackKey = normalizedId
                    artFetchScope.launch {
                        val result = fetchBestArtworkBitmap(
                            primaryKey = normalizedId,
                            rawTrackId = rawTrackId,
                            albumKey = albumKey,
                            trackName = immediateTrack.title,
                            artist = immediateTrack.artist,
                            album = immediateTrack.album,
                        ) ?: return@launch

                        val (artUrl, bitmap) = result
                        if (pendingArtTrackId == normalizedId) {
                            pendingArtTrackId = ""
                        }
                        val latest = WidgetStateCache.track
                        val stillCurrentTrack = latest.trackId == normalizedId ||
                            (latest.title.equals(immediateTrack.title, ignoreCase = true) &&
                                latest.artist.equals(immediateTrack.artist, ignoreCase = true))
                        if (stillCurrentTrack) {
                            WidgetStateCache.setLiveArtBitmap(bitmap)
                            extractAndSavePaletteFromBitmap(
                                liveBitmap = WidgetStateCache.liveArtBitmap,
                                localArtPath = "",
                                persist = !latest.isPlaying,
                            )
                            val updated = latest.copy(
                                albumArtUrl = artUrl,
                                localArtPath = if (latest.isPlaying) "" else latest.localArtPath,
                                lastUpdatedMs = System.currentTimeMillis(),
                            )
                            WidgetStateCache.track = updated
                            WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = updated)
                            syncCacheForPlaybackState(updated)
                        }
                        if (!bitmap.isRecycled) {
                            runCatching { bitmap.recycle() }
                        }
                    }.also { activeArtFetchJob = it }
                }
            }

            withTimeoutOrNull(7_500L) {
                fetchJob.join()
            }
        }
    }

    suspend fun handleSpotifyBroadcastPlaybackState(
        isPlaying: Boolean,
        progressMs: Long?,
    ) {
        if (!WidgetStateCache.isWarmed) {
            WidgetStateCache.ensureWarmed(appContext)
        }
        val current = WidgetStateCache.track
        // If resuming from idle cache, load cached art into memory BEFORE clearing disk cache
        if (isPlaying && WidgetStateCache.liveArtBitmap == null && current.localArtPath.isNotBlank()) {
            WidgetStateCache.ensureLiveArtLoadedFromFile(current.localArtPath, appContext)
        }
        val updated = current.copy(
            isPlaying = isPlaying,
            localArtPath = if (isPlaying) "" else current.localArtPath,
            progressMs = if (progressMs != null && progressMs >= 0L) progressMs else current.progressMs,
        )
        WidgetStateCache.track = updated
        // Push immediately before disk I/O so Play/Pause icon morphs in < 10ms.
        WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = updated)
        syncCacheForPlaybackState(updated)

        // If the current track's album art failed or timed out earlier, automatically retry now.
        val pendingId = pendingArtTrackId
        if (
            pendingId.isNotBlank() &&
            current.trackId == pendingId &&
            current.hasTrack &&
            activeArtFetchJob?.isActive != true
        ) {
            val retryJob = synchronized(artJobLock) {
                if (activeArtFetchTrackKey == pendingId && activeArtFetchJob?.isActive == true) {
                    activeArtFetchJob
                } else {
                    activeArtFetchTrackKey = pendingId
                    val albumKey = if (current.album.isNotBlank() && current.artist.isNotBlank()) {
                        "album:${current.album.trim().lowercase()}|${current.artist.trim().lowercase()}"
                    } else {
                        ""
                    }
                    artFetchScope.launch {
                        val result = fetchBestArtworkBitmap(
                            primaryKey = pendingId,
                            rawTrackId = pendingId,
                            albumKey = albumKey,
                            trackName = current.title,
                            artist = current.artist,
                            album = current.album,
                        ) ?: return@launch

                        val (artUrl, bitmap) = result
                        if (pendingArtTrackId == pendingId) {
                            pendingArtTrackId = ""
                        }
                        val latest = WidgetStateCache.track
                        if (latest.trackId == pendingId ||
                            (latest.title.equals(current.title, ignoreCase = true) &&
                                latest.artist.equals(current.artist, ignoreCase = true))
                        ) {
                            WidgetStateCache.setLiveArtBitmap(bitmap)
                            extractAndSavePaletteFromBitmap(
                                liveBitmap = WidgetStateCache.liveArtBitmap,
                                localArtPath = "",
                                persist = !latest.isPlaying,
                            )
                            val refreshed = latest.copy(
                                albumArtUrl = artUrl,
                                localArtPath = if (latest.isPlaying) "" else latest.localArtPath,
                                lastUpdatedMs = System.currentTimeMillis(),
                            )
                            WidgetStateCache.track = refreshed
                            WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = refreshed)
                            syncCacheForPlaybackState(refreshed)
                        }
                        if (!bitmap.isRecycled) {
                            runCatching { bitmap.recycle() }
                        }
                    }.also { activeArtFetchJob = it }
                }
            }
            withTimeoutOrNull(6_000L) {
                retryJob?.join()
            }
        }
    }

    /**
     * Resolves and downloads a verified, decodable cover Bitmap in memory across multiple
     * zero-auth sources in parallel without cluttering disk cache while playing.
     */
    private suspend fun fetchBestArtworkBitmap(
        primaryKey: String,
        rawTrackId: String,
        albumKey: String,
        trackName: String,
        artist: String,
        album: String,
    ): Pair<String, Bitmap>? {
        suspend fun tryDownload(url: String): Pair<String, Bitmap>? {
            if (url.isBlank()) return null
            val bmp = AlbumArtCache.downloadBitmap(url) ?: return null
            artUrlCache[primaryKey] = url
            if (albumKey.isNotBlank()) {
                artUrlCache[albumKey] = url
            }
            return url to bmp
        }

        // 1. Hot URL cache hit
        val cachedUrl = artUrlCache[primaryKey]
            ?: albumKey.takeIf { it.isNotBlank() }?.let { artUrlCache[it] }
        if (!cachedUrl.isNullOrBlank()) {
            tryDownload(cachedUrl)?.let { return it }
        }

        // 2. Query Spotify oEmbed, iTunes, and Deezer in PARALLEL so fallback never wastes extra RTTs
        val spotifyWebUrl = buildSpotifyOpenUrl(rawTrackId)
        val (oEmbedUrl, itunesUrl, deezerUrl) = coroutineScope {
            val oEmbedDeferred = async {
                if (spotifyWebUrl != null) {
                    withTimeoutOrNull(2_800L) {
                        fetchOEmbedThumbnailUrl(spotifyWebUrl)
                    }.orEmpty()
                } else {
                    ""
                }
            }
            val itunesDeferred = async {
                withTimeoutOrNull(2_800L) {
                    fetchItunesArtworkUrl(trackName = trackName, artist = artist)
                }.orEmpty()
            }
            val deezerDeferred = async {
                withTimeoutOrNull(2_800L) {
                    fetchDeezerArtworkUrl(trackName = trackName, artist = artist)
                }.orEmpty()
            }
            Triple(oEmbedDeferred.await(), itunesDeferred.await(), deezerDeferred.await())
        }

        // Prefer exact Spotify oEmbed artwork first, then iTunes, then Deezer
        for (candidate in listOf(oEmbedUrl, itunesUrl, deezerUrl).distinct()) {
            tryDownload(candidate)?.let { return it }
        }

        // 3. Album-level fallback (uses primary artist + album name when track title is niche/remixed)
        if (album.isNotBlank()) {
            val (albumItunesUrl, albumDeezerUrl) = coroutineScope {
                val itunesAlbumDef = async {
                    withTimeoutOrNull(2_200L) {
                        fetchItunesArtworkUrl(trackName = album, artist = artist)
                    }.orEmpty()
                }
                val deezerAlbumDef = async {
                    withTimeoutOrNull(2_200L) {
                        fetchDeezerArtworkUrl(trackName = album, artist = artist)
                    }.orEmpty()
                }
                itunesAlbumDef.await() to deezerAlbumDef.await()
            }
            for (candidate in listOf(albumItunesUrl, albumDeezerUrl).distinct()) {
                tryDownload(candidate)?.let { return it }
            }
        }

        return null
    }

    private suspend fun fetchOEmbedThumbnailUrl(spotifyWebUrl: String): String {
        return runCatching {
            val resp = oEmbedApi.getOEmbed(spotifyWebUrl)
            if (resp.isSuccessful) resp.body()?.thumbnailUrl.orEmpty() else ""
        }.getOrDefault("")
    }

    private suspend fun fetchDeezerArtworkUrl(
        trackName: String,
        artist: String,
    ): String {
        val primaryArtist = extractPrimaryArtist(artist)
        val cleanedTitle = cleanTrackTitleForSearch(trackName).ifBlank { trackName }
        val query = listOf(primaryArtist, cleanedTitle)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (query.isBlank()) return ""
        return runCatching {
            val resp = deezerApi.searchTrack(query = query)
            if (!resp.isSuccessful) return@runCatching ""
            val albumDto = resp.body()?.data?.firstOrNull()?.album ?: return@runCatching ""
            val candidate = albumDto.coverMedium?.takeIf { it.isNotBlank() }
                ?: albumDto.coverBig?.takeIf { it.isNotBlank() }
                ?: ""
            // Reject Deezer's empty-hash grey placeholder cover ("/images/cover//250x250")
            if (candidate.contains("/cover//")) "" else candidate
        }.getOrDefault("")
    }

    private suspend fun fetchItunesArtworkUrl(
        trackName: String,
        artist: String,
    ): String {
        val primaryArtist = extractPrimaryArtist(artist)
        val cleanedTitle = cleanTrackTitleForSearch(trackName).ifBlank { trackName }
        val query = listOf(primaryArtist, cleanedTitle)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (query.isBlank()) return ""
        return runCatching {
            val resp = itunesApi.searchTrack(term = query)
            if (!resp.isSuccessful) return@runCatching ""
            val item = resp.body()?.results?.firstOrNull() ?: return@runCatching ""
            val rawUrl = item.artworkUrl100 ?: item.artworkUrl60 ?: return@runCatching ""
            rawUrl.replace("100x100bb", "300x300bb").replace("60x60bb", "300x300bb")
        }.getOrDefault("")
    }

    private fun extractPrimaryArtist(artist: String): String {
        return artist
            .substringBefore(",")
            .substringBefore(";")
            .substringBefore(" feat.")
            .substringBefore(" ft.")
            .trim()
    }

    private fun cleanTrackTitleForSearch(title: String): String {
        return title
            .replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "")
            .replace(Regex("\\s+-\\s+(Remaster.*|Radio Edit.*|Single Version.*|Live.*|Acoustic.*)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun extractSpotifyId(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        return if (trimmed.contains(":")) {
            trimmed.substringAfterLast(":")
        } else {
            trimmed
        }
    }

    private fun buildSpotifyOpenUrl(rawTrackId: String): String? {
        val trimmed = rawTrackId.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("https://open.spotify.com/")) return trimmed
        val parts = trimmed.split(":")
        return when {
            parts.size >= 3 && parts[0] == "spotify" && parts[1] in setOf("track", "episode", "album") -> {
                val type = parts[1]
                val id = parts[2]
                if (id.isNotBlank()) "https://open.spotify.com/$type/$id" else null
            }
            parts.size == 1 && trimmed.length in 16..32 && trimmed.all { it.isLetterOrDigit() } -> {
                "https://open.spotify.com/track/$trimmed"
            }
            else -> null
        }
    }

    suspend fun refreshPlayback(): Result<PlaybackTrack> {
        val token = getValidAccessToken()
        if (token.isNullOrBlank()) {
            WidgetUpdater.pushWidgetUpdate(appContext)
            return Result.failure(IllegalStateException("Spotify account is not connected."))
        }

        val primaryResp = runCatching {
            webApi.getPlaybackState("Bearer $token")
        }.getOrElse { ex ->
            return Result.failure(IllegalStateException("Unable to reach Spotify: ${ex.message}"))
        }

        val resp = if (primaryResp.code() == 204 || primaryResp.code() == 403 || primaryResp.body()?.item == null) {
            runCatching { webApi.getCurrentlyPlaying("Bearer $token") }.getOrNull() ?: primaryResp
        } else {
            primaryResp
        }

        if (resp.code() == 204) {
            val existing = WidgetStateCache.track
            if (existing.hasTrack && existing.isPlaying) {
                handleSpotifySessionInactive()
            }
            return Result.success(WidgetStateCache.track)
        }

        if (!resp.isSuccessful) {
            return Result.failure(
                IllegalStateException("Spotify playback check failed (HTTP ${resp.code()}).")
            )
        }

        val state = resp.body()
        val item = state?.item
        if (item == null) {
            val existing = WidgetStateCache.track
            if (existing.hasTrack) {
                val updated = existing.copy(isPlaying = state?.isPlaying ?: false)
                WidgetStateCache.track = updated
                syncCacheForPlaybackState(updated)
                WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = WidgetStateCache.track)
                return Result.success(WidgetStateCache.track)
            }
            val idleTrack = PlaybackTrack(
                deviceName = state?.device?.name.orEmpty(),
                isPlaying = state?.isPlaying ?: false,
                lastUpdatedMs = System.currentTimeMillis(),
            )
            WidgetStateCache.track = idleTrack
            WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = idleTrack)
            return Result.success(idleTrack)
        }

        val bestArtUrl = item.album?.images
            ?.sortedByDescending { it.width ?: 0 }
            ?.firstOrNull { (it.width ?: 0) <= 640 }?.url
            ?: item.album?.images?.firstOrNull()?.url.orEmpty()

        val previousTrack = WidgetStateCache.track
        val artistLine = item.artists.joinToString(", ") { it.name }
        val albumName = item.album?.name.orEmpty()
        val trackIdKey = item.id.orEmpty()

        val artChanged = bestArtUrl != previousTrack.albumArtUrl || WidgetStateCache.liveArtBitmap == null
        if (bestArtUrl.isNotBlank() && artChanged) {
            val downloadedBmp = AlbumArtCache.downloadBitmap(bestArtUrl)
            if (downloadedBmp != null) {
                WidgetStateCache.setLiveArtBitmap(downloadedBmp)
                if (!downloadedBmp.isRecycled) {
                    runCatching { downloadedBmp.recycle() }
                }
            }
        }

        if (artChanged && WidgetStateCache.liveArtBitmap != null) {
            extractAndSavePaletteFromBitmap(
                liveBitmap = WidgetStateCache.liveArtBitmap,
                localArtPath = previousTrack.localArtPath,
                persist = !state.isPlaying,
            )
        }

        val updatedTrack = PlaybackTrack(
            trackId = trackIdKey,
            title = item.name,
            artist = artistLine,
            album = albumName,
            albumArtUrl = bestArtUrl,
            localArtPath = if (state.isPlaying) "" else previousTrack.localArtPath,
            durationMs = item.durationMs,
            progressMs = state.progressMs ?: 0L,
            isPlaying = state.isPlaying,
            deviceName = state.device?.name.orEmpty(),
            lastUpdatedMs = System.currentTimeMillis(),
        )
        WidgetStateCache.track = updatedTrack
        WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = updatedTrack)
        syncCacheForPlaybackState(updatedTrack)
        return Result.success(WidgetStateCache.track)
    }

    suspend fun togglePlayPause(): Result<String> {
        val token = getValidAccessToken()
        if (token.isNullOrBlank()) {
            if (!WidgetStateCache.isWarmed) {
                WidgetStateCache.ensureWarmed(appContext)
            }
            val currentTrack = WidgetStateCache.track
            val directNextPlaying = MediaNotificationListenerService.dispatchPlayPause()
            val nextPlaying = if (directNextPlaying != null) {
                directNextPlaying
            } else if (MediaKeyBridge.playPause(appContext)) {
                !currentTrack.isPlaying
            } else {
                return Result.success("Opened Spotify")
            }
            val updated = currentTrack.copy(isPlaying = nextPlaying)
            WidgetStateCache.track = updated
            WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = updated)
            syncCacheForPlaybackState(updated)
            SpotifySyncService.startIfEnabled(appContext)
            return Result.success("Dispatched Play/Pause to Spotify")
        }

        val current = WidgetStateCache.track
        val callResp = runCatching {
            if (current.isPlaying) {
                webApi.pause("Bearer $token")
            } else {
                webApi.play("Bearer $token")
            }
        }.getOrElse {
            MediaKeyBridge.playPause(appContext)
            return Result.failure(IllegalStateException("Network issue; sent media command to Spotify instead."))
        }

        return if (callResp.isSuccessful || callResp.code() == 204) {
            val toggled = !current.isPlaying
            val updated = current.copy(isPlaying = toggled)
            WidgetStateCache.track = updated
            WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = updated)
            syncCacheForPlaybackState(updated)
            delay(450L)
            refreshPlayback()
            Result.success(if (current.isPlaying) "Paused" else "Playing")
        } else if (callResp.code() == 404 || callResp.code() == 403) {
            MediaKeyBridge.playPause(appContext)
            val toggled = !current.isPlaying
            val updated = current.copy(isPlaying = toggled)
            WidgetStateCache.track = updated
            WidgetUpdater.pushWidgetUpdate(appContext, overrideTrack = updated)
            syncCacheForPlaybackState(updated)
            SpotifySyncService.startIfEnabled(appContext)
            Result.success("Sent Play/Pause to Spotify")
        } else {
            Result.failure(IllegalStateException("Spotify play/pause returned HTTP ${callResp.code()}"))
        }
    }

    suspend fun nextTrack(): Result<String> {
        val token = getValidAccessToken()
        if (token.isNullOrBlank()) {
            if (!MediaNotificationListenerService.dispatchNext()) {
                MediaKeyBridge.next(appContext)
            }
            SpotifySyncService.startIfEnabled(appContext)
            return Result.success("Dispatched Next Track to Spotify")
        }

        val callResp = runCatching {
            webApi.nextTrack("Bearer $token")
        }.getOrElse {
            MediaKeyBridge.next(appContext)
            return Result.failure(IllegalStateException("Network issue; sent device media key instead."))
        }

        return if (callResp.isSuccessful || callResp.code() == 204) {
            delay(450L)
            refreshPlayback()
            Result.success("Skipped to next track")
        } else if (callResp.code() == 404 || callResp.code() == 403) {
            MediaKeyBridge.next(appContext)
            SpotifySyncService.startIfEnabled(appContext)
            Result.success("Sent Next Track via device media key")
        } else {
            Result.failure(IllegalStateException("Spotify next track returned HTTP ${callResp.code()}"))
        }
    }

    suspend fun previousTrack(): Result<String> {
        val token = getValidAccessToken()
        if (token.isNullOrBlank()) {
            if (!MediaNotificationListenerService.dispatchPrevious()) {
                MediaKeyBridge.previous(appContext)
            }
            SpotifySyncService.startIfEnabled(appContext)
            return Result.success("Dispatched Previous Track to Spotify")
        }

        val callResp = runCatching {
            webApi.previousTrack("Bearer $token")
        }.getOrElse {
            MediaKeyBridge.previous(appContext)
            return Result.failure(IllegalStateException("Network issue; sent device media key instead."))
        }

        return if (callResp.isSuccessful || callResp.code() == 204) {
            delay(450L)
            refreshPlayback()
            Result.success("Returned to previous track")
        } else if (callResp.code() == 404 || callResp.code() == 403) {
            MediaKeyBridge.previous(appContext)
            SpotifySyncService.startIfEnabled(appContext)
            Result.success("Sent Previous Track via device media key")
        } else {
            Result.failure(IllegalStateException("Spotify previous track returned HTTP ${callResp.code()}"))
        }
    }

    suspend fun fetchDevices(): Result<List<SpotifyDevice>> {
        val token = getValidAccessToken()
            ?: return Result.failure(IllegalStateException("Not connected to Spotify"))

        val resp = runCatching {
            webApi.getDevices("Bearer $token")
        }.getOrElse { ex ->
            return Result.failure(ex)
        }

        if (!resp.isSuccessful || resp.body() == null) {
            return Result.failure(IllegalStateException("Devices fetch failed (HTTP ${resp.code()})"))
        }

        val devices = resp.body()!!.devices.mapNotNull { dto ->
            val id = dto.id ?: return@mapNotNull null
            SpotifyDevice(
                id = id,
                name = dto.name,
                type = dto.type,
                isActive = dto.isActive,
                volumePercent = dto.volumePercent,
            )
        }
        return Result.success(devices)
    }

    suspend fun transferPlayback(deviceId: String): Result<Unit> {
        val token = getValidAccessToken()
            ?: return Result.failure(IllegalStateException("Not connected to Spotify"))

        val resp = runCatching {
            webApi.transferPlayback(
                authHeader = "Bearer $token",
                body = TransferPlaybackRequestDto(deviceIds = listOf(deviceId), play = true),
            )
        }.getOrElse { ex ->
            return Result.failure(ex)
        }

        return if (resp.isSuccessful || resp.code() == 204) {
            delay(450L)
            refreshPlayback()
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Device transfer failed (HTTP ${resp.code()})"))
        }
    }

    private suspend fun getValidAccessToken(): String? {
        val auth = preferences.getAuth()
        if (auth.accessToken.isBlank() && auth.refreshToken.isBlank()) return null

        val now = System.currentTimeMillis()
        if (auth.accessToken.isNotBlank() && now < auth.expiresAtEpochMs) {
            return auth.accessToken
        }

        if (auth.refreshToken.isBlank() || !isClientIdConfigured()) {
            return auth.accessToken.takeIf { it.isNotBlank() }
        }

        val refreshResp = runCatching {
            accountsApi.refreshToken(
                refreshToken = auth.refreshToken,
                clientId = BuildConfig.SPOTIFY_CLIENT_ID,
            )
        }.getOrNull() ?: return null

        if (!refreshResp.isSuccessful || refreshResp.body() == null) {
            return null
        }

        val body = refreshResp.body()!!
        val expiresAt = System.currentTimeMillis() + (body.expiresIn * 1000L) - 30_000L
        preferences.saveAuthTokens(
            accessToken = body.accessToken,
            refreshToken = body.refreshToken ?: auth.refreshToken,
            expiresAtEpochMs = expiresAt,
        )
        return body.accessToken
    }

    private fun generateRandomUrlSafe(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
