from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from .core import Recommender

_RECOMMENDER: Optional[Recommender] = None
_CATALOG_SIGNATURE: Optional[Tuple[Any, ...]] = None


def _playlist_signature(catalog: Sequence[Dict[str, Any]]) -> Tuple[Any, ...]:
    if not catalog:
        return ()
    prefix = tuple(
        (
            song.get("id"),
            song.get("title"),
            song.get("artist"),
            song.get("album"),
            song.get("duration"),
        )
        for song in catalog[:50]
    )
    total_dates = sum(int(song.get("dateAdded") or 0) for song in catalog)
    total_duration = sum(int(song.get("duration") or 0) for song in catalog)
    return (len(catalog), total_dates, total_duration, prefix)


def _ensure_recommender(catalog: List[Dict[str, Any]]) -> Optional[Recommender]:
    global _RECOMMENDER, _CATALOG_SIGNATURE
    if not catalog:
        _RECOMMENDER = None
        _CATALOG_SIGNATURE = None
        return None
    signature = _playlist_signature(catalog)
    if _RECOMMENDER is None or signature != _CATALOG_SIGNATURE:
        _RECOMMENDER = Recommender(playlist=catalog)
        _CATALOG_SIGNATURE = signature
    return _RECOMMENDER


def _recent_song_ids(history: Optional[Iterable[Dict[str, Any]]]) -> List[Any]:
    if not history:
        return []
    song_ids: List[Any] = []
    for entry in history:
        song = entry.get("song") or {}
        song_id = song.get("id") or song.get("song_id") or song.get("signature")
        if song_id is not None:
            song_ids.append(song_id)
    return song_ids


def _favorite_ids(favorites: Optional[Iterable[Dict[str, Any]]]) -> List[Any]:
    if not favorites:
        return []
    ids: List[Any] = []
    for song in favorites:
        song_id = song.get("id") or song.get("song_id") or song.get("signature")
        if song_id is not None:
            ids.append(song_id)
    return ids


def _result_key(song: Dict[str, Any]) -> Optional[str]:
    song_id = song.get("id")
    if song_id is not None:
        return str(song_id)
    signature = song.get("signature")
    if signature:
        return str(signature)
    title = song.get("title") or song.get("displayName")
    artist = song.get("artist")
    album = song.get("album")
    duration = song.get("duration")
    if title or artist or album:
        return "#".join(
            str(part or "").strip().lower()
            for part in (title, artist, album, duration)
        )
    return None


def recommend(
    catalog: List[Dict[str, Any]],
    history: Optional[List[Dict[str, Any]]] = None,
    favorites: Optional[List[Dict[str, Any]]] = None,
    limit: int = 1,
) -> List[str]:
    recommender = _ensure_recommender(catalog)
    if recommender is None:
        return []
    limit = max(1, int(limit))
    recent_ids = _recent_song_ids(history)
    favorite_ids = _favorite_ids(favorites)
    results = recommender.selection(
        policy="recommend",
        n=limit,
        recent_played=recent_ids,
        liked_song_ids=favorite_ids,
    )
    keys: List[str] = []
    for song in results:
        key = _result_key(song)
        if key:
            keys.append(key)
    return keys


__all__ = ["recommend"]
