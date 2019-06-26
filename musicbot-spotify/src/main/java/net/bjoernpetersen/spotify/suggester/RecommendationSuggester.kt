package net.bjoernpetersen.spotify.suggester

import com.wrapper.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.IntSerializer
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.NumberBox
import net.bjoernpetersen.musicbot.api.config.SerializationException
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.SongEntry
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.BrokenSuggesterException
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.spotify.auth.SpotifyAuthenticator
import net.bjoernpetersen.spotify.marketFromToken
import net.bjoernpetersen.spotify.provider.SpotifyProvider
import java.util.LinkedList
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

@IdBase("Spotify recommendation suggester")
class RecommendationSuggester : Suggester, CoroutineScope {

    private val logger = KotlinLogging.logger { }

    override val name: String = "Spotify recommendation suggester"
    override val description: String =
        "Suggests Spotify songs based on the last played manually enqueued song"

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    @Inject
    private lateinit var auth: SpotifyAuthenticator
    @Inject
    private lateinit var provider: SpotifyProvider

    private lateinit var fallbackEntry: Config.StringEntry
    private lateinit var baseEntry: Config.SerializedEntry<SimpleSong>

    // various station tuning attributes
    private lateinit var targetDanceability: Config.SerializedEntry<Int>
    private lateinit var targetEnergy: Config.SerializedEntry<Int>
    private lateinit var minInstrumentalness: Config.SerializedEntry<Int>
    private lateinit var maxInstrumentalness: Config.SerializedEntry<Int>
    private lateinit var minLiveness: Config.SerializedEntry<Int>
    private lateinit var maxLiveness: Config.SerializedEntry<Int>

    private val baseId: String
        get() = baseEntry.get()?.id
            ?: getSongId(fallbackEntry.get()!!)
            ?: throw IllegalStateException("No valid base or fallback set")

    private lateinit var baseSong: Song

    private suspend fun lookupBaseSong(): Song {
        val baseId = baseId
        return provider.lookup(baseId)
    }

    override val subject: String
        get() = "Based on ${baseEntry.get()?.name ?: baseSong.title}"

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        fallbackEntry = config.StringEntry(
            key = "fallbackEntry",
            description = "Spotify song URL of a song to base stations on if there is no alternative",
            configChecker = { if (it?.let(::getSongId) == null) "Invalid URL" else null },
            uiNode = TextBox,
            default = "https://open.spotify.com/track/75n8FqbBeBLW2jUzvjdjXV?si=3LjPfzQdTcmnMn05gf7UNQ"
        )

        targetDanceability = config.SerializedEntry(
            key = "targetDanceability",
            description = "Danceability describes how suitable a track is for dancing based on" +
                " a combination of musical elements including tempo, rhythm stability," +
                " beat strength, and overall regularity.",
            serializer = IntSerializer,
            configChecker = NonnullConfigChecker,
            uiNode = NumberBox(min = -1),
            default = -1
        )
        targetEnergy = config.SerializedEntry(
            key = "targetEnergy",
            description = "Energy represents a perceptual measure of intensity and activity." +
                " Typically, energetic tracks feel fast, loud, and noisy. For example," +
                " death metal has high energy, while a Bach prelude scores low on the scale." +
                " Perceptual features contributing to this attribute include dynamic range," +
                " perceived loudness, timbre, onset rate, and general entropy.",
            serializer = IntSerializer,
            configChecker = NonnullConfigChecker,
            uiNode = NumberBox(min = -1),
            default = -1
        )
        minInstrumentalness = config.SerializedEntry(
            key = "minInstrumentalness",
            description = "Predicts whether a track contains no vocals. “Ooh” and “aah” sounds are" +
                " treated as instrumental in this context. Rap or spoken word tracks are clearly" +
                " “vocal”. The closer the instrumentalness value is to 100, the greater likelihood" +
                " the track contains no vocal content. Values above 50 are intended to represent" +
                " instrumental tracks, but confidence is higher as the value approaches 100.",
            serializer = IntSerializer,
            configChecker = NonnullConfigChecker,
            uiNode = NumberBox(),
            default = 0
        )
        maxInstrumentalness = config.SerializedEntry(
            key = "maxInstrumentalness",
            description = "Predicts whether a track contains no vocals. “Ooh” and “aah” sounds are" +
                " treated as instrumental in this context. Rap or spoken word tracks are clearly" +
                " “vocal”. The closer the instrumentalness value is to 100, the greater likelihood" +
                " the track contains no vocal content. Values above 50 are intended to represent" +
                " instrumental tracks, but confidence is higher as the value approaches 100.",
            serializer = IntSerializer,
            configChecker = NonnullConfigChecker,
            uiNode = NumberBox(),
            default = 100
        )
        minLiveness = config.SerializedEntry(
            key = "minLiveness",
            description = "Detects the presence of an audience in the recording." +
                " Higher liveness values represent an increased probability that" +
                " the track was performed live. A value above 80 provides strong" +
                " likelihood that the track is live.",
            serializer = IntSerializer,
            configChecker = NonnullConfigChecker,
            uiNode = NumberBox(),
            default = 0
        )
        maxLiveness = config.SerializedEntry(
            key = "maxLiveness",
            description = "Danceability describes how suitable a track is for dancing based on" +
                " a combination of musical elements including tempo, rhythm stability," +
                " beat strength, and overall regularity.",
            serializer = IntSerializer,
            configChecker = NonnullConfigChecker,
            uiNode = NumberBox(),
            default = 100
        )

        return listOf(
            fallbackEntry,
            targetDanceability,
            targetEnergy,
            minInstrumentalness, maxInstrumentalness,
            minLiveness, maxLiveness
        )
    }

    override fun createStateEntries(state: Config) {
        baseEntry = state.SerializedEntry(
            key = "baseEntry",
            description = "",
            serializer = SimpleSong.Serializer,
            configChecker = { null })
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        withContext(coroutineContext) {
            initStateWriter.state("Trying to retrieve base song")
            baseSong = try {
                lookupBaseSong()
            } catch (e: NoSuchSongException) {
                throw InitializationException(e)
            } catch (e: IllegalStateException) {
                throw InitializationException(e)
            }

            initStateWriter.state("Filling suggestions")
            fillNextSongs()

            if (nextSongs.isEmpty()) {
                throw InitializationException("Could not get any recommendations")
            }
        }
    }

    private val nextSongs: MutableList<Song> = LinkedList()

    private fun Int.toPercentFloat(): Float = toFloat() / 100
    private fun Config.SerializedEntry<Int>.setIfPresent(set: (Float) -> Unit) {
        val value = get()!!
        if (value == default) return
        set(value.toPercentFloat())
    }

    private suspend fun fillNextSongs() {
        withContext(coroutineContext) {
            val api = SpotifyApi.builder()
                .setAccessToken(auth.getToken())
                .build()

            api.recommendations
                .marketFromToken()
                .seed_tracks(baseSong.id)
                .apply {
                    targetDanceability.setIfPresent { target_danceability(it) }
                    targetEnergy.setIfPresent { target_energy(it) }

                    minInstrumentalness.setIfPresent { min_instrumentalness(it) }
                    maxInstrumentalness.setIfPresent { max_instrumentalness(it) }

                    minLiveness.setIfPresent { min_liveness(it) }
                    maxLiveness.setIfPresent { max_liveness(it) }
                }
                .build()
                .execute()
                .tracks
                .map { it.id }
                .let { provider.lookupBatch(it) }
                .forEach { nextSongs.add(it) }
        }
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        return withContext(coroutineContext) {
            if (nextSongs.size <= 1) {
                fillNextSongs()
                if (nextSongs.isEmpty()) {
                    throw BrokenSuggesterException()
                }
            }

            nextSongs.toList().let { it.subList(0, min(maxLength, it.size)) }
        }
    }

    override suspend fun suggestNext(): Song = withContext(coroutineContext) {
        val song = getNextSuggestions(1).first()
        nextSongs.remove(song)
        song
    }

    override suspend fun removeSuggestion(song: Song) = withContext<Unit>(coroutineContext) {
        nextSongs.remove(song)
    }

    override suspend fun notifyPlayed(songEntry: SongEntry) = withContext(coroutineContext) {
        super.notifyPlayed(songEntry)
        if (songEntry.user != null) {
            val song = songEntry.song
            baseEntry.set(SimpleSong(song.id, song.title))
            baseSong = song
            nextSongs.clear()
        }
    }

    override suspend fun close() {
        job.cancel()
    }
}

private data class SimpleSong(val id: String, val name: String) {
    object Serializer : ConfigSerializer<SimpleSong> {
        override fun serialize(obj: SimpleSong): String {
            return "${obj.id};${obj.name}"
        }

        override fun deserialize(string: String): SimpleSong {
            val splits = string.split(";")
            if (splits.size < 2) throw SerializationException()
            val id = splits[0]
            val name = splits.subList(1, splits.size).joinToString(";")
            return SimpleSong(id, name)
        }
    }
}
