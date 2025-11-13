import json
import re
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence, Union

import numpy as np
import pandas as pd

DEFAULT_SAMPLE = Path(__file__).with_name("sample_songs.json")


class SongFeatureEncoder:
    """Converts rich song metadata into numeric vectors."""

    def __init__(
        self,
        categorical_cols: Optional[Sequence[str]] = None,
        tag_cols: Optional[Sequence[str]] = None,
        numeric_cols: Optional[Sequence[str]] = None,
    ):
        self.categorical_cols = tuple(categorical_cols or ("artist", "album", "genre"))
        self.tag_cols = tuple(tag_cols or ("tags",))
        self.numeric_cols = tuple(numeric_cols or ("duration", "year", "tempo"))
        self.index_map: Dict[str, Dict[str, int]] = {}
        self.numeric_stats: Dict[str, tuple] = {}
        self.numeric_order: List[str] = []
        self.numeric_offset = 0
        self.dimension = 0

    def fit(self, df: pd.DataFrame) -> "SongFeatureEncoder":
        offset = 0
        self.index_map.clear()
        for column in self.categorical_cols:
            if column not in df.columns:
                continue
            values = {
                str(value).strip().lower()
                for value in df[column].dropna().tolist()
                if str(value).strip()
            }
            if not values:
                continue
            mapping = {value: offset + idx for idx, value in enumerate(sorted(values))}
            self.index_map[column] = mapping
            offset += len(mapping)

        tag_pattern = re.compile(r"[;,|/]+")
        for column in self.tag_cols:
            if column not in df.columns:
                continue
            tags = set()
            for raw in df[column].dropna().tolist():
                tags.update(self._split_tags(raw, tag_pattern))
            if not tags:
                continue
            mapping = {value: offset + idx for idx, value in enumerate(sorted(tags))}
            self.index_map[column] = mapping
            offset += len(mapping)

        self.numeric_order = []
        self.numeric_stats = {}
        for column in self.numeric_cols:
            if column not in df.columns:
                continue
            series = pd.to_numeric(df[column], errors="coerce").fillna(0)
            mean = float(series.mean())
            std = float(series.std()) or 1.0
            self.numeric_stats[column] = (mean, std)
            self.numeric_order.append(column)

        self.numeric_offset = offset
        self.dimension = offset + len(self.numeric_order)
        return self

    def transform(self, df: pd.DataFrame) -> np.ndarray:
        if df.empty or self.dimension == 0:
            return np.zeros((len(df), self.dimension), dtype=np.float32)
        rows = df.to_dict(orient="records")
        vectors = np.vstack([self.transform_row(row) for row in rows])
        return vectors

    def transform_row(self, row: Mapping[str, Any]) -> np.ndarray:
        vector = np.zeros(self.dimension, dtype=np.float32)
        for column, mapping in self.index_map.items():
            if column in self.categorical_cols:
                value = str(row.get(column, "")).strip().lower()
                if value:
                    idx = mapping.get(value)
                    if idx is not None:
                        vector[idx] = 1.0
            elif column in self.tag_cols:
                tokens = self._split_tags(row.get(column, ""))
                for token in tokens:
                    idx = mapping.get(token)
                    if idx is not None:
                        vector[idx] = 1.0

        for offset, column in enumerate(self.numeric_order):
            mean, std = self.numeric_stats[column]
            try:
                value = float(row.get(column, mean))
            except (TypeError, ValueError):
                value = mean
            vector[self.numeric_offset + offset] = (value - mean) / std
        return vector

    @staticmethod
    def _split_tags(raw_value: Any, pattern: Optional[re.Pattern] = None) -> List[str]:
        if raw_value is None:
            return []
        if isinstance(raw_value, (list, tuple, set)):
            tokens = raw_value
        else:
            regex = pattern or re.compile(r"[;,|/]+")
            tokens = regex.split(str(raw_value))
        return [token.strip().lower() for token in tokens if token and token.strip()]


class Recommender:
    """Content-based recommender that powers the shuffle/推薦 behavior."""

    def __init__(
        self,
        storage: Optional[Union[str, Path]] = None,
        playlist: Optional[Union[List[Dict[str, Any]], pd.DataFrame]] = None,
    ):
        self.storage = Path(storage) if storage else DEFAULT_SAMPLE
        self._raw_playlist = self._load_playlist(playlist)
        if self._raw_playlist.empty:
            raise ValueError("Playlist is empty. Provide songs via storage or playlist.")
        if "id" not in self._raw_playlist.columns:
            self._raw_playlist["id"] = [
                f"song_{idx}" for idx in range(len(self._raw_playlist))
            ]
        self._raw_playlist = self._raw_playlist.reset_index(drop=True)

        self.encoder = SongFeatureEncoder()
        self.encoder.fit(self._raw_playlist)
        self.song_matrix = self.encoder.transform(self._raw_playlist)
        self.normalized_matrix = self._normalize(self.song_matrix)
        self.id_to_index = {
            str(row["id"]): idx for idx, row in self._raw_playlist.iterrows()
        }

    def selection(
        self,
        policy: str = "random",
        n: int = 1,
        recent_played: Optional[Sequence[Union[str, Dict[str, Any]]]] = None,
        liked_song_ids: Optional[Sequence[Union[str, Dict[str, Any]]]] = None,
    ) -> List[Dict[str, Any]]:
        policy = (policy or "random").lower()
        if policy not in {"random", "linucb", "linucb+", "recommend"}:
            raise ValueError(f"Unsupported policy: {policy}")
        n = max(1, int(n))

        if policy in {"random", "recommend"}:
            return self._recommend_with_similarity(
                limit=n,
                recent_played=recent_played,
                liked_song_ids=liked_song_ids,
            )
        raise NotImplementedError(f"Policy '{policy}' is not implemented yet.")

    # ------------------------------------------------------------------ #
    # Internal helpers                                                   #
    # ------------------------------------------------------------------ #

    def _load_playlist(
        self, playlist: Optional[Union[List[Dict[str, Any]], pd.DataFrame]]
    ) -> pd.DataFrame:
        if playlist is not None:
            if isinstance(playlist, pd.DataFrame):
                return playlist.copy()
            return pd.DataFrame(playlist)
        if self.storage and self.storage.exists():
            suffix = self.storage.suffix.lower()
            if suffix == ".json":
                with self.storage.open("r", encoding="utf-8") as src:
                    data = json.load(src)
                return pd.DataFrame(data)
            if suffix == ".csv":
                return pd.read_csv(self.storage)
        return pd.DataFrame()

    @staticmethod
    def _normalize(matrix: np.ndarray) -> np.ndarray:
        if matrix.size == 0:
            return matrix
        norms = np.linalg.norm(matrix, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return matrix / norms

    def _recommend_with_similarity(
        self,
        limit: int,
        recent_played: Optional[Sequence[Union[str, Dict[str, Any]]]],
        liked_song_ids: Optional[Sequence[Union[str, Dict[str, Any]]]],
    ) -> List[Dict[str, Any]]:
        if self.normalized_matrix.size == 0:
            return []

        seed_indices = self._resolve_song_indices(recent_played)
        seed_indices.extend(
            idx for idx in self._resolve_song_indices(liked_song_ids) if idx not in seed_indices
        )

        if not seed_indices:
            return self._fallback(limit)

        user_vector = self.normalized_matrix[seed_indices].mean(axis=0)
        norm = np.linalg.norm(user_vector)
        if norm == 0:
            return self._fallback(limit)
        user_vector /= norm

        scores = self.normalized_matrix @ user_vector
        for idx in seed_indices:
            scores[idx] = -np.inf

        ordering = np.argsort(scores)[::-1]
        recommendations: List[Dict[str, Any]] = []
        for idx in ordering:
            if len(recommendations) >= limit:
                break
            if not np.isfinite(scores[idx]):
                continue
            recommendations.append(self._serialize_song(idx, float(scores[idx])))

        if recommendations:
            return recommendations
        return self._fallback(limit)

    def _resolve_song_indices(
        self, items: Optional[Sequence[Union[str, Dict[str, Any]]]]
    ) -> List[int]:
        if not items:
            return []
        resolved: List[int] = []
        for item in items:
            song_id = None
            if isinstance(item, Mapping):
                song_id = item.get("id") or item.get("song_id")
            else:
                song_id = item
            if song_id is None:
                continue
            idx = self.id_to_index.get(str(song_id))
            if idx is not None:
                resolved.append(idx)
        return resolved

    def _serialize_song(self, position: int, score: Optional[float]) -> Dict[str, Any]:
        row = self._raw_playlist.iloc[position].to_dict()
        payload = {
            key: (value.item() if isinstance(value, np.generic) else value)
            for key, value in row.items()
        }
        payload["score"] = None if score is None else round(score, 6)
        return payload

    def _fallback(self, limit: int) -> List[Dict[str, Any]]:
        if self._raw_playlist.empty:
            return []
        fallback_column = self._fallback_sort_column()
        candidates = (
            self._raw_playlist.sort_values(by=fallback_column, ascending=False)
            .head(limit)
            .to_dict(orient="records")
        )
        return [
            {
                **{
                    key: (value.item() if isinstance(value, np.generic) else value)
                    for key, value in row.items()
                },
                "score": None,
            }
            for row in candidates
        ]

    def _fallback_sort_column(self) -> str:
        for column in ("dateAdded", "date_added", "year"):
            if column in self._raw_playlist.columns:
                return column
        return "id"


def build_recommender(storage: Optional[Union[str, Path]] = None) -> Recommender:
    return Recommender(storage=storage)
