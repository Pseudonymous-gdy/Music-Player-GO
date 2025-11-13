package com.iven.musicplayergo.recommendation

import com.iven.musicplayergo.models.HistoryEntry
import com.iven.musicplayergo.models.Music
import java.util.Locale
import kotlin.math.sqrt

/**
 * Lightweight content-based recommendation engine that replaces the legacy random shuffle.
 *
 * It builds song vectors from on-device metadata, derives a user preference vector from the
 * playback history + favorites, and ranks the catalog with cosine similarity.
 */
object RecommendationEngine {

    private const val MAX_HISTORY_WINDOW = 60
    private const val MIN_SIGNAL_SOURCES = 3
    private const val FAVORITE_WEIGHT = 2.5f
    private val WORD_SPLITTER = Regex("[^a-z0-9]+")

    private val lock = Any()
    private var catalog: Catalog = Catalog.EMPTY

    fun refreshCatalog(songs: List<Music>?) {
        synchronized(lock) {
            if (songs.isNullOrEmpty()) {
                catalog = Catalog.EMPTY
                return
            }
            val indexer = FeatureIndexer()
            val encoder = FeatureEncoder(indexer, WORD_SPLITTER)
            val vectors = songs.map { encoder.encode(it) }
            catalog = Catalog.from(vectors)
        }
    }

    fun recommend(
        history: List<HistoryEntry>,
        favorites: List<Music>?,
        excludeIds: Set<Long?> = emptySet(),
        limit: Int = 1
    ): List<Music> {
        if (limit <= 0) return emptyList()
        val snapshot = synchronized(lock) { catalog }
        if (snapshot.vectors.isEmpty()) return emptyList()

        val cleanedHistory = history.take(MAX_HISTORY_WINDOW)
        val favoriteList = favorites ?: emptyList()
        if (cleanedHistory.size + favoriteList.size < MIN_SIGNAL_SOURCES) {
            return snapshot.dateFallback(limit, excludeIds)
        }

        val userVector = buildUserVector(snapshot, cleanedHistory, favoriteList)
            ?: return snapshot.dateFallback(limit, excludeIds)

        val exclude = buildExcludeSet(excludeIds, cleanedHistory)
        val scored = snapshot.vectors.asSequence()
            .filter { vector ->
                val id = vector.music.id
                id == null || !exclude.contains(id)
            }
            .map { vector -> vector.music to cosineSimilarity(userVector, vector) }
            .toList()
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()

        return if (scored.isNotEmpty()) scored else snapshot.dateFallback(limit, excludeIds)
    }

    private fun buildExcludeSet(
        explicitExcludeIds: Set<Long?>,
        history: List<HistoryEntry>
    ): Set<Long> {
        val exclude = explicitExcludeIds.filterNotNull().toMutableSet()
        history.take(5).forEach { entry ->
            entry.music.id?.let { exclude.add(it) }
        }
        return exclude
    }

    private fun buildUserVector(
        catalog: Catalog,
        history: List<HistoryEntry>,
        favorites: List<Music>
    ): WeightedVector? {

        val contributions = mutableMapOf<Int, Float>()
        var totalWeight = 0f

        val historySize = history.size.coerceAtLeast(1)
        val maxPlayCount = history.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1

        history.forEachIndexed { index, entry ->
            val vector = catalog.vectorFor(entry.music) ?: return@forEachIndexed
            val recency = (historySize - index).toFloat() / historySize
            val play = entry.playCount.toFloat() / maxPlayCount
            val listen = listeningRatio(entry)
            val weight = 1f + 0.6f * recency + 0.3f * play + 0.1f * listen
            accumulate(contributions, vector.features, weight)
            totalWeight += weight
        }

        favorites.distinctBy { it.id }.forEach { fav ->
            val vector = catalog.vectorFor(fav) ?: return@forEach
            accumulate(contributions, vector.features, FAVORITE_WEIGHT)
            totalWeight += FAVORITE_WEIGHT
        }

        if (contributions.isEmpty() || totalWeight <= 0f) return null
        val normalized = contributions.mapValues { it.value / totalWeight }
        val norm = sqrt(normalized.values.sumOf { value -> (value * value).toDouble() })
        return WeightedVector(normalized, if (norm == 0.0) 1.0 else norm)
    }

    private fun listeningRatio(entry: HistoryEntry): Float {
        val duration = entry.music.duration.toFloat()
        if (duration <= 0f) return 0f
        val ratio = entry.totalDuration / duration
        return ratio.coerceIn(0f, 2f)
    }

    private fun cosineSimilarity(
        user: WeightedVector,
        song: SongVector
    ): Double {
        if (user.features.isEmpty() || song.features.isEmpty()) return 0.0
        val (smaller, larger) = if (user.features.size <= song.features.size) {
            user.features to song.features
        } else {
            song.features to user.features
        }
        var dot = 0.0
        smaller.forEach { (idx, value) ->
            val other = larger[idx] ?: return@forEach
            dot += value * other
        }
        if (dot == 0.0) return 0.0
        return dot / user.norm
    }

    private fun accumulate(
        target: MutableMap<Int, Float>,
        source: Map<Int, Float>,
        weight: Float
    ) {
        if (weight <= 0f) return
        source.forEach { (idx, value) ->
            val addition = value * weight
            target[idx] = target.getOrDefault(idx, 0f) + addition
        }
    }

    private data class WeightedVector(
        val features: Map<Int, Float>,
        val norm: Double
    )

    private data class SongVector(
        val music: Music,
        val features: Map<Int, Float>
    )

    private data class Catalog(
        val vectors: List<SongVector>,
        val byId: Map<Long, SongVector>,
        val bySignature: Map<String, SongVector>
    ) {

        fun vectorFor(song: Music?): SongVector? {
            song ?: return null
            song.id?.let { id -> byId[id] }?.let { return it }
            return bySignature[song.signatureKey()]
        }

        fun dateFallback(limit: Int, excludeIds: Set<Long?>): List<Music> {
            val exclude = excludeIds.filterNotNull().toSet()
            val filtered = vectors.filter { vector ->
                val id = vector.music.id
                id == null || !exclude.contains(id)
            }
            return filtered
                .map { it.music }
                .sortedByDescending { it.dateAdded }
                .take(limit)
        }

        companion object {
            val EMPTY = Catalog(emptyList(), emptyMap(), emptyMap())

            fun from(vectors: List<SongVector>): Catalog {
                val idMap = mutableMapOf<Long, SongVector>()
                val signatureMap = mutableMapOf<String, SongVector>()
                vectors.forEach { vector ->
                    vector.music.id?.let { idMap[it] = vector }
                    signatureMap[vector.music.signatureKey()] = vector
                }
                return Catalog(vectors, idMap, signatureMap)
            }
        }
    }

    private class FeatureIndexer {
        private val indices = mutableMapOf<String, Int>()
        fun indexFor(feature: String): Int =
            indices.getOrPut(feature) { indices.size }
    }

    private class FeatureEncoder(
        private val indexer: FeatureIndexer,
        private val wordSplitter: Regex
    ) {

        fun encode(music: Music): SongVector {
            val features = mutableMapOf<Int, Float>()

            addCategorical(features, "artist", music.artist, weight = 3f)
            addCategorical(features, "album", music.album, weight = 2f)
            addCategorical(features, "folder", music.relativePath, weight = 1.5f)
            addYearFeature(features, music.year)
            addDurationFeature(features, music.duration)
            addTitleTokens(features, music.title)
            addTitleTokens(features, music.displayName)

            val norm = sqrt(features.values.sumOf { value -> (value * value).toDouble() }).toFloat()
            val normalized = if (norm <= 0f) {
                features
            } else {
                features.mapValues { it.value / norm }
            }
            return SongVector(music, normalized)
        }

        private fun addCategorical(
            target: MutableMap<Int, Float>,
            type: String,
            rawValue: String?,
            weight: Float
        ) {
            val value = rawValue?.trim()?.lowercase(Locale.ROOT)
            if (value.isNullOrEmpty()) return
            addFeature(target, "$type:$value", weight)
        }

        private fun addYearFeature(target: MutableMap<Int, Float>, year: Int) {
            if (year <= 0) {
                addFeature(target, "year:unknown", 0.5f)
                return
            }
            val bucket = when {
                year < 1980 -> "pre80"
                year < 1990 -> "80s"
                year < 2000 -> "90s"
                year < 2010 -> "00s"
                year < 2020 -> "10s"
                else -> "20s"
            }
            addFeature(target, "year:$bucket", 0.8f)
        }

        private fun addDurationFeature(target: MutableMap<Int, Float>, durationMs: Long) {
            val duration = durationMs / 1000
            val bucket = when {
                duration <= 0 -> "unknown"
                duration < 150 -> "short"
                duration < 240 -> "medium"
                duration < 420 -> "long"
                else -> "epic"
            }
            addFeature(target, "duration:$bucket", 0.7f)
        }

        private fun addTitleTokens(target: MutableMap<Int, Float>, title: String?) {
            val normalized = title?.lowercase(Locale.ROOT) ?: return
            val tokens = normalized.split(wordSplitter)
                .filter { it.length in 3..20 }
                .take(5)
            tokens.forEach { token ->
                addFeature(target, "token:$token", 0.4f)
            }
        }

        private fun addFeature(target: MutableMap<Int, Float>, feature: String, weight: Float) {
            val index = indexer.indexFor(feature)
            target[index] = target.getOrDefault(index, 0f) + weight
        }
    }
}
