package net.bjoernpetersen.musicbot.youtube.auth

import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.PasswordBox
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubeAuthenticator

class YouTubeAuthenticatorImpl : YouTubeAuthenticator {
    override val name: String
        get() = "Manual"
    override val description: String
        get() = "Manually obtained API token"

    private lateinit var apiKeyEntry: Config.StringEntry

    override fun createStateEntries(state: Config) = Unit
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()
    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        apiKeyEntry = secrets.StringEntry(
            "apiToken",
            "YouTube API token",
            NonnullConfigChecker,
            PasswordBox
        )

        return listOf(apiKeyEntry)
    }

    override suspend fun initialize(initStateWriter: InitStateWriter) = Unit
    override suspend fun getToken(): String = apiKeyEntry.get()!!
    override fun invalidateToken() = Unit
    override suspend fun close() = Unit
}
