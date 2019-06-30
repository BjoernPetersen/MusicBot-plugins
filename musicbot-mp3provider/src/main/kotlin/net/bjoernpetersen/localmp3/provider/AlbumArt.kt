package net.bjoernpetersen.localmp3.provider

import com.mpatric.mp3agic.BaseException
import com.mpatric.mp3agic.Mp3File
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.spi.image.ImageData
import java.io.IOException
import java.nio.file.Path

private val logger = KotlinLogging.logger { }

fun loadImage(basePath: Path, path: Path): ImageData? {
    if (!path.normalize().startsWith(basePath.normalize())) {
        logger.warn { "Tried to load data from restricted file: $path" }
        return null
    }

    val mp3File = try {
        Mp3File(path)
    } catch (e: IOException) {
        return null
    } catch (e: BaseException) {
        return null
    }

    if (!mp3File.hasId3v2Tag()) return null
    val tag = mp3File.id3v2Tag
    val image = tag.albumImage ?: return null
    val type = tag.albumImageMimeType ?: "image/*"
    return ImageData(type, image)
}
