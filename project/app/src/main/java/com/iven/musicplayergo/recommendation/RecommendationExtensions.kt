package com.iven.musicplayergo.recommendation

import com.iven.musicplayergo.models.Music
import java.util.Locale

fun Music.signatureKey(): String {
    val titleKey = (title ?: "").trim().lowercase(Locale.ROOT)
    val artistKey = (artist ?: "").trim().lowercase(Locale.ROOT)
    val albumKey = (album ?: "").trim().lowercase(Locale.ROOT)
    return listOf(titleKey, artistKey, albumKey, duration.toString()).joinToString("#")
}
