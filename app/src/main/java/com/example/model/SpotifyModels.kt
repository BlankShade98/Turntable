package com.example.model

enum class WidgetThemePreset(
    val id: String,
    val label: String,
    val backgroundHex: Long,
    val cardElevatedHex: Long,
    val accentHex: Long,
    val onAccentHex: Long,
    val primaryTextHex: Long,
    val secondaryTextHex: Long,
) {
    ALBUM_PASTEL(
        id = "album_pastel",
        label = "Album Pastel",
        backgroundHex = 0xFFE5EAE1,
        cardElevatedHex = 0xFFD2DBD0,
        accentHex = 0xFF5D6B8E,
        onAccentHex = 0xFFFCFBF8,
        primaryTextHex = 0xFF1A221B,
        secondaryTextHex = 0xFF47554A,
    ),
    OBSIDIAN(
        id = "obsidian",
        label = "Obsidian",
        backgroundHex = 0xFF11151B,
        cardElevatedHex = 0xFF1C222C,
        accentHex = 0xFF1ED760,
        onAccentHex = 0xFF0A140D,
        primaryTextHex = 0xFFF4F1EA,
        secondaryTextHex = 0xFF9BA5B4,
    ),
    VINYL_WARM(
        id = "vinyl_warm",
        label = "Warm Vinyl",
        backgroundHex = 0xFF1C1612,
        cardElevatedHex = 0xFF2B221B,
        accentHex = 0xFFF5A623,
        onAccentHex = 0xFF1A1105,
        primaryTextHex = 0xFFFAF3E8,
        secondaryTextHex = 0xFFC4B39F,
    ),
    MONOCHROME(
        id = "monochrome",
        label = "Studio Ivory",
        backgroundHex = 0xFFF2EFE9,
        cardElevatedHex = 0xFFE4E0D7,
        accentHex = 0xFF141820,
        onAccentHex = 0xFFF7F5F0,
        primaryTextHex = 0xFF12161C,
        secondaryTextHex = 0xFF5A6270,
    );

    companion object {
        fun fromId(id: String?): WidgetThemePreset =
            entries.firstOrNull { it.id == id } ?: ALBUM_PASTEL
    }
}

data class DynamicAlbumPalette(
    val backgroundArgb: Int = 0xFFE5EAE1.toInt(),
    val elevatedArgb: Int = 0xFFD2DBD0.toInt(),
    val playButtonArgb: Int = 0xFF5D6B8E.toInt(),
    val playIconArgb: Int = 0xFFFCFBF8.toInt(),
    val primaryTextArgb: Int = 0xFF1A221B.toInt(),
    val secondaryTextArgb: Int = 0xFF47554A.toInt(),
)

enum class WidgetLayoutDensity(val id: String, val label: String) {
    COMPACT("compact", "Compact Bar"),
    SHOWCASE("showcase", "Album Showcase");

    companion object {
        fun fromId(id: String?): WidgetLayoutDensity =
            entries.firstOrNull { it.id == id } ?: COMPACT
    }
}

enum class WidgetCornerStyle(val id: String, val label: String, val radiusDp: Int) {
    SOFT("soft", "Rounded", 24),
    STUDIO("studio", "Crisp Studio", 14),
    PILL("pill", "Pill Edge", 32);

    companion object {
        // Locked to "Rounded" (SOFT) per design spec
        fun fromId(id: String?): WidgetCornerStyle = SOFT
    }
}

data class WidgetStyleConfig(
    val themePreset: WidgetThemePreset = WidgetThemePreset.ALBUM_PASTEL,
    val layoutDensity: WidgetLayoutDensity = WidgetLayoutDensity.COMPACT,
    val cornerStyle: WidgetCornerStyle = WidgetCornerStyle.SOFT,
    val showAlbumSubtitle: Boolean = true,
    val showDeviceBadge: Boolean = true,
    val dynamicPalette: DynamicAlbumPalette = DynamicAlbumPalette(),
) {
    fun resolvedColors(): ResolvedWidgetColors {
        return if (themePreset == WidgetThemePreset.ALBUM_PASTEL) {
            ResolvedWidgetColors(
                backgroundArgb = dynamicPalette.backgroundArgb,
                elevatedArgb = dynamicPalette.elevatedArgb,
                playButtonArgb = dynamicPalette.playButtonArgb,
                playIconArgb = dynamicPalette.playIconArgb,
                primaryTextArgb = dynamicPalette.primaryTextArgb,
                secondaryTextArgb = dynamicPalette.secondaryTextArgb,
            )
        } else {
            ResolvedWidgetColors(
                backgroundArgb = themePreset.backgroundHex.toInt(),
                elevatedArgb = themePreset.cardElevatedHex.toInt(),
                playButtonArgb = themePreset.accentHex.toInt(),
                playIconArgb = themePreset.onAccentHex.toInt(),
                primaryTextArgb = themePreset.primaryTextHex.toInt(),
                secondaryTextArgb = themePreset.secondaryTextHex.toInt(),
            )
        }
    }
}

data class ResolvedWidgetColors(
    val backgroundArgb: Int,
    val elevatedArgb: Int,
    val playButtonArgb: Int,
    val playIconArgb: Int,
    val primaryTextArgb: Int,
    val secondaryTextArgb: Int,
)

data class PlaybackTrack(
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtUrl: String = "",
    val localArtPath: String = "",
    val durationMs: Long = 0L,
    val progressMs: Long = 0L,
    val isPlaying: Boolean = false,
    val deviceName: String = "",
    val lastUpdatedMs: Long = 0L,
) {
    val hasTrack: Boolean
        get() = title.isNotBlank()
}

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val volumePercent: Int?,
)

sealed interface SpotifyConnectionStatus {
    data object NeedsClientSecret : SpotifyConnectionStatus
    data object Disconnected : SpotifyConnectionStatus
    data object Connecting : SpotifyConnectionStatus
    data class Connected(
        val displayName: String,
        val activeDeviceName: String?,
    ) : SpotifyConnectionStatus
    data class Error(val message: String) : SpotifyConnectionStatus
}
