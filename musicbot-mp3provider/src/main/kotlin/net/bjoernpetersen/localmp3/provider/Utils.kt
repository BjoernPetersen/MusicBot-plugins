package net.bjoernpetersen.localmp3.provider

import java.nio.file.Path

/**
 * Returns the extension of this file (not including the dot), or an empty string if it doesn't have one.
 */
internal val Path.extension: String
    get() = fileName.toString().substringAfterLast('.', "")
