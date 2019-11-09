package net.bjoernpetersen.localmp3.provider

import com.mpatric.mp3agic.BaseException
import com.mpatric.mp3agic.Mp3File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.spi.image.ImageData

private val logger = KotlinLogging.logger { }

fun loadImage(path: Path): ImageData? {
    val mp3File = try {
        Mp3File(path)
    } catch (e: IOException) {
        logger.warn(e) {}
        null
    } catch (e: BaseException) {
        logger.warn(e) {}
        null
    }

    if (mp3File != null && mp3File.hasId3v2Tag()) {
        val tag = mp3File.id3v2Tag
        val image = tag.albumImage
        if (image != null) {
            val type = tag.albumImageMimeType ?: "image/*"
            return ImageData(type, image)
        }
    }
    return null
}

private val jpgVariants = listOf(
    "jpg",
    "jpeg",
    "JPG",
    "JPEG"
)

private val imageFileCandidates by lazy {
    sequenceOf(
        "Folder",
        "Album",
        "Cover",
        "AlbumArt",
        "AlbumArtSmall",
        "Disc"
    )
        .flatMap { sequenceOf(it, it.toLowerCase()) }
        .flatMap { name -> jpgVariants.asSequence().map { "$name.$it" } }
}

fun loadFolderImage(path: Path): ImageData? {
    val parent = path.parent
    return imageFileCandidates
        .map { parent.resolve(it) }
        .firstOrNull { Files.isRegularFile(it) }
        ?.let { Files.readAllBytes(it) }
        ?.let { ImageData("image/jpeg", it) }
}
