package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.WidgetStateCache
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.model.PlaybackTrack
import com.example.model.SpotifyConnectionStatus
import com.example.model.WidgetStyleConfig
import com.example.ui.theme.GoogleSansFontFamily
import java.io.File

@Composable
fun WidgetPreviewCard(
    track: PlaybackTrack,
    style: WidgetStyleConfig,
    connectionStatus: SpotifyConnectionStatus,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenSpotify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = style.resolvedColors()
    val bgColor = Color(colors.backgroundArgb)
    val elevatedColor = Color(colors.elevatedArgb)
    val playBtnColor = Color(colors.playButtonArgb)
    val playIconColor = Color(colors.playIconArgb)
    val primaryText = Color(colors.primaryTextArgb)
    val secondaryText = Color(colors.secondaryTextArgb)
    val outerCorner = com.example.model.WidgetCornerStyle.SOFT.radiusDp.dp
    val artCorner = (com.example.model.WidgetCornerStyle.SOFT.radiusDp - 10).coerceAtLeast(8).dp

    val displayTitle = when {
        track.hasTrack -> track.title
        else -> "No song playing"
    }
    val subtitleText = when {
        track.hasTrack -> buildString {
            append(track.artist.ifBlank { "Unknown artist" })
            if (style.showAlbumSubtitle && track.album.isNotBlank()) {
                append(" • ")
                append(track.album)
            }
        }
        connectionStatus is SpotifyConnectionStatus.Connected -> "Play a song in Spotify"
        else -> "Play music in Spotify"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "HOME SCREEN PREVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "4×1 – 5×1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Single-row bar preview matching the home screen widget proportions with Google Sans
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(98.dp)
                .clip(RoundedCornerShape(outerCorner))
                .clickable(onClick = onOpenSpotify)
                .testTag("widget_preview_card"),
            shape = RoundedCornerShape(outerCorner),
            color = bgColor,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 1.dp,
                        color = primaryText.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(outerCorner),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 70.dp of 98.dp widget height (~71%), leaving 14.dp equal top/bottom/left margins
                    AlbumArtworkBox(
                        track = track,
                        elevatedColor = elevatedColor,
                        corner = artCorner,
                        modifier = Modifier
                            .size(70.dp)
                            .testTag("widget_album_art"),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = 14.dp),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        Text(
                            text = displayTitle,
                            color = primaryText,
                            fontFamily = GoogleSansFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitleText,
                            color = secondaryText,
                            fontFamily = GoogleSansFontFamily,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(top = 10.dp, bottom = 9.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Top-right "Spotify" badge (opens config app on the home screen widget)
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(50))
                                .background(playBtnColor)
                                .padding(horizontal = 9.dp)
                                .testTag("preview_spotify_config_pill"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_widget_music_waves),
                                contentDescription = "Open Turntable settings",
                                tint = playIconColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Spotify",
                                color = playIconColor,
                                fontFamily = GoogleSansFontFamily,
                                fontSize = 11.5.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BorderlessTransportButton(
                                icon = Icons.Rounded.SkipPrevious,
                                contentDescription = stringResource(R.string.action_previous_track),
                                contentColor = primaryText,
                                testTag = "preview_previous_button",
                                onClick = onPrevious,
                            )

                            BorderlessTransportButton(
                                icon = Icons.Rounded.SkipNext,
                                contentDescription = stringResource(R.string.action_next_track),
                                contentColor = primaryText,
                                testTag = "preview_next_button",
                                onClick = onNext,
                            )

                            MorphingPlayPauseButton(
                                isPlaying = track.isPlaying,
                                containerColor = playBtnColor,
                                contentColor = playIconColor,
                                onClick = onPlayPause,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumArtworkBox(
    track: PlaybackTrack,
    elevatedColor: Color,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    val liveBitmap by WidgetStateCache.liveArtBitmapFlow.collectAsStateWithLifecycle()
    val validLiveImage = remember(liveBitmap) {
        liveBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }
    val localFile = track.localArtPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
    val model: Any? = localFile ?: track.albumArtUrl.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(elevatedColor),
        contentAlignment = Alignment.Center,
    ) {
        if (validLiveImage != null) {
            Image(
                bitmap = validLiveImage,
                contentDescription = stringResource(R.string.cd_album_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.cd_album_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.img_vinyl_placeholder),
                contentDescription = stringResource(R.string.cd_vinyl_placeholder),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun BorderlessTransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    contentColor: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun MorphingPlayPauseButton(
    isPlaying: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val cornerRadius = if (isPlaying) 12.dp else 20.dp

    Box(
        modifier = Modifier
            .width(54.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor)
            .clickable(onClick = onClick)
            .testTag("preview_play_pause_button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.action_play_pause),
            tint = contentColor,
            modifier = Modifier.size(26.dp),
        )
    }
}
