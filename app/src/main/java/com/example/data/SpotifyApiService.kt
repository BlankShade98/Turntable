package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class SpotifyTokenResponseDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String = "Bearer",
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "expires_in") val expiresIn: Long = 3600L,
    @Json(name = "refresh_token") val refreshToken: String? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifyUserProfileDto(
    @Json(name = "id") val id: String,
    @Json(name = "display_name") val displayName: String? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifyImageDto(
    @Json(name = "url") val url: String,
    @Json(name = "height") val height: Int? = null,
    @Json(name = "width") val width: Int? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifyArtistDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String,
)

@JsonClass(generateAdapter = true)
data class SpotifyAlbumDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String = "",
    @Json(name = "images") val images: List<SpotifyImageDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SpotifyTrackDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String = "",
    @Json(name = "duration_ms") val durationMs: Long = 0L,
    @Json(name = "artists") val artists: List<SpotifyArtistDto> = emptyList(),
    @Json(name = "album") val album: SpotifyAlbumDto? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifyDeviceDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "is_active") val isActive: Boolean = false,
    @Json(name = "name") val name: String = "Spotify Device",
    @Json(name = "type") val type: String = "Smartphone",
    @Json(name = "volume_percent") val volumePercent: Int? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifyPlayerStateDto(
    @Json(name = "device") val device: SpotifyDeviceDto? = null,
    @Json(name = "is_playing") val isPlaying: Boolean = false,
    @Json(name = "progress_ms") val progressMs: Long? = 0L,
    @Json(name = "item") val item: SpotifyTrackDto? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifyDevicesResponseDto(
    @Json(name = "devices") val devices: List<SpotifyDeviceDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TransferPlaybackRequestDto(
    @Json(name = "device_ids") val deviceIds: List<String>,
    @Json(name = "play") val play: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class SpotifyOEmbedDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "thumbnail_url") val thumbnailUrl: String? = null,
    @Json(name = "thumbnail_width") val thumbnailWidth: Int? = null,
    @Json(name = "thumbnail_height") val thumbnailHeight: Int? = null,
)

@JsonClass(generateAdapter = true)
data class ItunesTrackItemDto(
    @Json(name = "artworkUrl100") val artworkUrl100: String? = null,
    @Json(name = "artworkUrl60") val artworkUrl60: String? = null,
)

@JsonClass(generateAdapter = true)
data class ItunesSearchResponseDto(
    @Json(name = "results") val results: List<ItunesTrackItemDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DeezerAlbumDto(
    @Json(name = "cover_medium") val coverMedium: String? = null,
    @Json(name = "cover_big") val coverBig: String? = null,
)

@JsonClass(generateAdapter = true)
data class DeezerTrackDto(
    @Json(name = "album") val album: DeezerAlbumDto? = null,
)

@JsonClass(generateAdapter = true)
data class DeezerSearchResponseDto(
    @Json(name = "data") val data: List<DeezerTrackDto> = emptyList(),
)

interface SpotifyOEmbedService {
    @GET("oembed")
    suspend fun getOEmbed(
        @Query("url") spotifyUrl: String,
    ): Response<SpotifyOEmbedDto>
}

interface ItunesSearchService {
    @GET("search")
    suspend fun searchTrack(
        @Query("term") term: String,
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 1,
    ): Response<ItunesSearchResponseDto>
}

interface DeezerSearchService {
    @GET("search")
    suspend fun searchTrack(
        @Query("q") query: String,
        @Query("limit") limit: Int = 1,
    ): Response<DeezerSearchResponseDto>
}

interface SpotifyAccountsService {
    @FormUrlEncoded
    @POST("api/token")
    suspend fun exchangeCode(
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("client_id") clientId: String,
        @Field("code_verifier") codeVerifier: String,
    ): Response<SpotifyTokenResponseDto>

    @FormUrlEncoded
    @POST("api/token")
    suspend fun refreshToken(
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String,
        @Field("client_id") clientId: String,
    ): Response<SpotifyTokenResponseDto>
}

interface SpotifyWebApiService {
    @GET("v1/me")
    suspend fun getCurrentUser(
        @Header("Authorization") authHeader: String,
    ): Response<SpotifyUserProfileDto>

    @GET("v1/me/player")
    suspend fun getPlaybackState(
        @Header("Authorization") authHeader: String,
    ): Response<SpotifyPlayerStateDto>

    @GET("v1/me/player/currently-playing")
    suspend fun getCurrentlyPlaying(
        @Header("Authorization") authHeader: String,
    ): Response<SpotifyPlayerStateDto>

    @GET("v1/me/player/devices")
    suspend fun getDevices(
        @Header("Authorization") authHeader: String,
    ): Response<SpotifyDevicesResponseDto>

    @PUT("v1/me/player/play")
    suspend fun play(
        @Header("Authorization") authHeader: String,
    ): Response<Unit>

    @PUT("v1/me/player/pause")
    suspend fun pause(
        @Header("Authorization") authHeader: String,
    ): Response<Unit>

    @POST("v1/me/player/next")
    suspend fun nextTrack(
        @Header("Authorization") authHeader: String,
    ): Response<Unit>

    @POST("v1/me/player/previous")
    suspend fun previousTrack(
        @Header("Authorization") authHeader: String,
    ): Response<Unit>

    @PUT("v1/me/player")
    suspend fun transferPlayback(
        @Header("Authorization") authHeader: String,
        @Body body: TransferPlaybackRequestDto,
    ): Response<Unit>
}

object SpotifyNetworkClient {
    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    val fastJsonClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .callTimeout(4000, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "TurntableWidget/1.0 (Android)")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    val fastImageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3000, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .callTimeout(5500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "image/jpeg, image/png, image/webp, image/*;q=0.9")
                    .header("User-Agent", "TurntableWidget/1.0 (Android)")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    val accountsService: SpotifyAccountsService by lazy {
        Retrofit.Builder()
            .baseUrl("https://accounts.spotify.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(SpotifyAccountsService::class.java)
    }

    val webApiService: SpotifyWebApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.spotify.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(SpotifyWebApiService::class.java)
    }

    val oEmbedService: SpotifyOEmbedService by lazy {
        Retrofit.Builder()
            .baseUrl("https://open.spotify.com/")
            .client(fastJsonClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(SpotifyOEmbedService::class.java)
    }

    val deezerService: DeezerSearchService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(fastJsonClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(DeezerSearchService::class.java)
    }

    val itunesService: ItunesSearchService by lazy {
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(fastJsonClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(ItunesSearchService::class.java)
    }
}
