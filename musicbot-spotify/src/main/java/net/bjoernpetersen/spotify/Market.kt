package net.bjoernpetersen.spotify

import com.wrapper.spotify.requests.AbstractRequest

fun <T : AbstractRequest.Builder<T>> T.marketFromToken(): T =
    setQueryParameter("market", "from_token")
