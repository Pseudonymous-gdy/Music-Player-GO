import math
import re
from typing import Dict, Iterable, List, Optional, Tuple

WORD_SPLITTER = re.compile(r"[^a-z0-9]+")
FAVORITE_WEIGHT = 2.5
MIN_SIGNAL_SOURCES = 3


def recommend(
    catalog: List[Dict],
    history: Optional[List[Dict]] = None,
    favorites: Optional[List[Dict]] = None,
    limit: int = 1,
) -> List[str]:
    """
    Return up to `limit` song keys (signature strings) ranked by similarity to the user's taste.

    Each catalog entry should include at least:
    - id (optional)
    - signature (string, unique identifier)
    - title, displayName, artist, album, relativePath, duration, year, dateAdded
    """
    if not catalog or limit <= 0:
        return []

    limit = max(1, int(limit))
    history = history or []
    favorites = favorites or []

    vectors = _encode_catalog(catalog)
    signal_count = len(history) + len(favorites)
    if signal_count < MIN_SIGNAL_SOURCES:
        return _fallback(catalog, limit)

    user_vector = _build_user_vector(vectors, history, favorites)
    if not user_vector:
        return _fallback(catalog, limit)

    exclude = _exclude_recent(history)
    scored = []
    for key, (song, song_vector) in vectors.items():
        song_id = song.get("id")
        if song_id is not None and song_id in exclude:
            continue
        score = _cosine(user_vector, song_vector)
        scored.append((key, score, song.get("dateAdded", 0)))

    scored.sort(key=lambda item: (item[1], item[2]), reverse=True)
    ranked = [key for key, score, _ in scored if score > 0]
    if len(ranked) < limit:
        ranked.extend(
            key
            for key, _, _ in scored
            if key not in ranked
        )
    if len(ranked) < limit:
        ranked.extend(_fallback(catalog, limit))

    deduped = []
    for key in ranked:
        if key not in deduped:
            deduped.append(key)
        if len(deduped) >= limit:
            break
    return deduped


def _encode_catalog(catalog: List[Dict]) -> Dict[str, Tuple[Dict, Dict[str, float]]]:
    vectors = {}
    for song in catalog:
        key = _song_key(song)
        if key in vectors:
            continue
        vectors[key] = (song, _encode_song(song))
    return vectors


def _build_user_vector(vectors, history, favorites):
    contributions: Dict[str, float] = {}
    total_weight = 0.0
    history_size = max(1, len(history))
    max_play_count = max((entry.get("playCount", 1) for entry in history), default=1)

    for index, entry in enumerate(history):
        song = entry.get("song") or {}
        vector = _resolve_vector(vectors, song)
        if not vector:
            continue
        recency = (history_size - index) / history_size
        play = entry.get("playCount", 1) / max(1, max_play_count)
        listen = _listening_ratio(entry, song)
        weight = 1.0 + 0.6 * recency + 0.3 * play + 0.1 * listen
        _accumulate(contributions, vector, weight)
        total_weight += weight

    for fav in favorites:
        vector = _resolve_vector(vectors, fav)
        if not vector:
            continue
        _accumulate(contributions, vector, FAVORITE_WEIGHT)
        total_weight += FAVORITE_WEIGHT

    if not contributions or total_weight <= 0:
        return None

    normalized = {feature: value / total_weight for feature, value in contributions.items()}
    norm = math.sqrt(sum(val * val for val in normalized.values()))
    if norm == 0:
        return None
    return {feature: value / norm for feature, value in normalized.items()}


def _resolve_vector(vectors, song):
    key = _song_key(song)
    cached = vectors.get(key)
    if cached:
        return cached[1]
    vector = _encode_song(song)
    vectors[key] = (song, vector)
    return vector


def _song_key(song: Dict) -> str:
    signature = song.get("signature")
    if isinstance(signature, str) and signature:
        return signature
    title = (song.get("title") or "").strip().lower()
    artist = (song.get("artist") or "").strip().lower()
    album = (song.get("album") or "").strip().lower()
    duration = str(song.get("duration", 0))
    return "#".join([title, artist, album, duration])


def _encode_song(song: Dict) -> Dict[str, float]:
    features: Dict[str, float] = {}

    def add_feature(name: str, weight: float):
        features[name] = features.get(name, 0.0) + weight

    def add_token_features(text: Optional[str]):
        if not text:
            return
        tokens = [
            token for token in WORD_SPLITTER.split(text.lower())
            if 3 <= len(token) <= 20
        ][:5]
        for token in tokens:
            add_feature(f"token:{token}", 0.4)

    artist = song.get("artist")
    album = song.get("album")
    folder = song.get("relativePath")
    if artist:
        add_feature(f"artist:{artist.lower().strip()}", 3.0)
    if album:
        add_feature(f"album:{album.lower().strip()}", 2.0)
    if folder:
        add_feature(f"folder:{folder.lower().strip()}", 1.5)

    year = int(song.get("year", 0))
    if year <= 0:
        add_feature("year:unknown", 0.5)
    else:
        bucket = _year_bucket(year)
        add_feature(f"year:{bucket}", 0.8)

    duration = int(song.get("duration", 0)) // 1000
    bucket = _duration_bucket(duration)
    add_feature(f"duration:{bucket}", 0.7)

    add_token_features(song.get("title"))
    add_token_features(song.get("displayName"))

    norm = math.sqrt(sum(value * value for value in features.values()))
    if norm == 0:
        return features
    return {name: value / norm for name, value in features.items()}


def _year_bucket(year: int) -> str:
    if year < 1980:
        return "pre80"
    if year < 1990:
        return "80s"
    if year < 2000:
        return "90s"
    if year < 2010:
        return "00s"
    if year < 2020:
        return "10s"
    return "20s"


def _duration_bucket(duration_seconds: int) -> str:
    if duration_seconds <= 0:
        return "unknown"
    if duration_seconds < 150:
        return "short"
    if duration_seconds < 240:
        return "medium"
    if duration_seconds < 420:
        return "long"
    return "epic"


def _listening_ratio(entry: Dict, song: Dict) -> float:
    listened = float(entry.get("totalDuration", 0))
    duration = float(song.get("duration") or 0)
    if duration <= 0:
        return 0.0
    ratio = listened / duration
    return max(0.0, min(2.0, ratio))


def _accumulate(target: Dict[str, float], source: Dict[str, float], weight: float):
    if weight <= 0:
        return
    for feature, value in source.items():
        target[feature] = target.get(feature, 0.0) + value * weight


def _cosine(left: Dict[str, float], right: Dict[str, float]) -> float:
    if not left or not right:
        return 0.0
    if len(left) > len(right):
        left, right = right, left
    dot = 0.0
    for feature, value in left.items():
        other = right.get(feature)
        if other is not None:
            dot += value * other
    return dot


def _exclude_recent(history: List[Dict]) -> set:
    exclude = set()
    for entry in history[:5]:
        song = entry.get("song") or {}
        song_id = song.get("id")
        if song_id is not None:
            exclude.add(song_id)
    return exclude


def _fallback(catalog: List[Dict], limit: int) -> List[str]:
    sorted_catalog = sorted(
        catalog,
        key=lambda song: song.get("dateAdded", 0),
        reverse=True,
    )
    keys = []
    for song in sorted_catalog:
        key = _song_key(song)
        if key not in keys:
            keys.append(key)
        if len(keys) >= limit:
            break
    return keys
