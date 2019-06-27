package net.bjoernpetersen.javafxplayback

import javafx.scene.media.Media
import javafx.scene.media.MediaException
import javafx.scene.media.MediaPlayer
import net.bjoernpetersen.musicbot.spi.plugin.AbstractPlayback
import java.io.File
import java.io.IOException

internal class JavaFxPlayback @Throws(IOException::class, MediaException::class)
constructor(file: File) : AbstractPlayback() {

    private val player = MediaPlayer(Media(file.toURI().toURL().toExternalForm())).also {
        it.setOnEndOfMedia { markDone() }
    }

    override suspend fun play() {
        player.play()
    }

    override suspend fun pause() {
        player.pause()
    }

    @Throws(Exception::class)
    override suspend fun close() {
        player.dispose()
        super.close()
    }
}
