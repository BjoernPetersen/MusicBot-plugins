package net.bjoernpetersen.spotify.auth

internal data class TokenPair(val refreshToken: Token, val accessToken: Token?)
