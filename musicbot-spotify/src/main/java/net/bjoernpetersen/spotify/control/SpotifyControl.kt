package net.bjoernpetersen.spotify.control

import net.bjoernpetersen.musicbot.api.plugin.Base
import net.bjoernpetersen.musicbot.spi.plugin.GenericPlugin

@Base
interface SpotifyControl : GenericPlugin {

    override val name: String
        get() = "Spotify client control"

    override val description: String
        get() = "Allows control of a Spotify client"

    val deviceId: String
}
