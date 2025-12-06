import sys
# add the directory to the system path to avoid below import errors
sys.path.append("..")

from Recommender import *
from UserSimulator import UserSimulator
import random
import torch
import pandas
import numpy as np
from typing import List, Dict, Optional, Union

class SongSimulator:
    """
    Simulate a *per-user* song catalog.

    Responsibilities:
      - Hold a list of MusicItem objects representing this user's library.
      - Generate item feature vectors with latent "genre" structure.
      - Provide sampling utilities (e.g., uniform / popularity-biased).

    Typical usage:
        song_sim = SongSimulator(dim=feature_dim, n_songs=500)
        playlist = song_sim.get_catalog()  # List[MusicItem]
        recommender = Recommender(playlist=playlist, policy="LinUCB+")

    Feature generation model (high-level):
      - Sample K latent genre centers in R^dim.
      - For each song:
          - Choose a dominant genre.
          - Sample features around that genre center with Gaussian noise.
          - Optionally normalize to unit norm.

    Popularity model:
      - "uniform": all songs equally likely.
      - "zipf":   popularity ~ 1 / rank^alpha (heavy-tailed).
    """

    def __init__(
        self,
        dim: int,
        n_songs: int = 500,
        n_genres: int = 8,
        feature_noise_std: float = 0.3,
        normalize_features: bool = True,
        popularity_mode: str = "zipf",
        zipf_alpha: float = 1.1,
        n_artists: int = 50,
        seed: Optional[int] = None,
    ):
        """
        Parameters
        ----------
        dim : int
            Feature dimension for each MusicItem.features.
        n_songs : int
            Number of songs in this user's catalog.
        n_genres : int
            Number of latent genre clusters.
        feature_noise_std : float
            Standard deviation of noise around genre centers.
        normalize_features : bool
            If True, each song feature is normalized to unit norm.
        popularity_mode : {"uniform", "zipf"}
            How to construct the per-song sampling distribution.
        zipf_alpha : float
            Exponent for Zipf-like popularity (only used if popularity_mode == "zipf").
        n_artists : int
            Number of distinct pseudo-artist IDs for metadata.
        seed : Optional[int]
            Random seed for reproducibility.
        """
        assert n_songs > 0, "n_songs must be positive."
        assert n_genres > 0, "n_genres must be positive."
        assert popularity_mode in ("uniform", "zipf"), "popularity_mode must be 'uniform' or 'zipf'."

        self.dim = dim
        self.n_songs = n_songs
        self.n_genres = n_genres
        self.feature_noise_std = float(feature_noise_std)
        self.normalize_features = normalize_features
        self.popularity_mode = popularity_mode
        self.zipf_alpha = float(zipf_alpha)
        self.n_artists = n_artists

        self.rng = np.random.RandomState(seed)

        # Internal storage
        self.genre_centers: np.ndarray = np.zeros((n_genres, dim), dtype=np.float64)
        self.songs: List["MusicItem"] = []
        self.song_by_id: Dict[Union[int, str], "MusicItem"] = {}
        self.popularity: np.ndarray = np.zeros(n_songs, dtype=np.float64)

        self._init_genre_centers()
        self._init_songs()
        self._init_popularity()

    # ------------------------------------------------------------------
    # Internal initialization
    # ------------------------------------------------------------------
    def _init_genre_centers(self):
        """
        Sample latent genre centers as random unit vectors in R^dim.
        """
        centers = self.rng.randn(self.n_genres, self.dim)
        norms = np.linalg.norm(centers, axis=1, keepdims=True) + 1e-8
        self.genre_centers = centers / norms  # shape: (n_genres, dim)

    def _sample_song_feature(self, genre_id: int) -> np.ndarray:
        """
        Sample a song feature vector around a given genre center.
        """
        base = self.genre_centers[genre_id]
        noise = self.feature_noise_std * self.rng.randn(self.dim)
        feat = base + noise
        if self.normalize_features:
            norm = np.linalg.norm(feat)
            if norm > 1e-8:
                feat = feat / norm
        return feat.astype(np.float64)

    def _init_songs(self):
        """
        Create n_songs MusicItem instances with generated features and simple metadata.
        """
        from typing import cast
        # Late import/annotation to avoid circular import issues in some setups
        global MusicItem  # assume MusicItem is defined in the same module

        songs: List["MusicItem"] = []
        song_by_id: Dict[Union[int, str], "MusicItem"] = {}

        # Optionally enforce a rough genre distribution (uniform for simplicity)
        genre_ids = self.rng.randint(0, self.n_genres, size=self.n_songs)

        for song_idx in range(self.n_songs):
            gid = int(genre_ids[song_idx])
            features = self._sample_song_feature(gid)

            # Simple synthetic metadata
            name = f"Song_{song_idx}"
            artist_id = self.rng.randint(0, self.n_artists)
            artist = f"Artist_{artist_id}"

            # Use integer IDs; can easily convert to str if desired
            item_id: Union[int, str] = song_idx

            item = MusicItem(
                id=item_id,
                features=features,
                name=name,
                artist=artist,
            )

            songs.append(item)
            song_by_id[item_id] = item

        self.songs = songs
        self.song_by_id = song_by_id

    def _init_popularity(self):
        """
        Build a sampling distribution over songs (e.g., uniform or Zipf-like).
        """
        if self.popularity_mode == "uniform":
            probs = np.ones(self.n_songs, dtype=np.float64) / float(self.n_songs)
        else:
            # Zipf-like: p(i) ∝ 1 / rank(i)^alpha
            ranks = np.arange(1, self.n_songs + 1, dtype=np.float64)
            weights = 1.0 / np.power(ranks, self.zipf_alpha)
            probs = weights / (np.sum(weights) + 1e-12)

        self.popularity = probs

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    def get_catalog(self) -> List["MusicItem"]:
        """
        Return the full list of MusicItem objects for this user's catalog.
        """
        return self.songs

    def get_song(self, song_id: Union[int, str]) -> "MusicItem":
        """
        Return a MusicItem given its ID.
        """
        if song_id not in self.song_by_id:
            raise KeyError(f"Song ID {song_id} not found in catalog.")
        return self.song_by_id[song_id]

    def sample_song(self, use_popularity: bool = True) -> "MusicItem":
        """
        Sample a single song from the catalog.

        Parameters
        ----------
        use_popularity : bool
            If True, sample according to the internal popularity distribution.
            If False, sample uniformly at random.

        Returns
        -------
        MusicItem
        """
        if use_popularity:
            idx = int(self.rng.choice(self.n_songs, p=self.popularity))
        else:
            idx = int(self.rng.randint(0, self.n_songs))
        return self.songs[idx]

    def sample_playlist(
        self,
        length: int,
        use_popularity: bool = True,
        replace: bool = True,
    ) -> List["MusicItem"]:
        """
        Sample a playlist (a list of songs) from the catalog.

        Parameters
        ----------
        length : int
            Number of songs to sample.
        use_popularity : bool
            Whether to sample according to popularity or uniformly.
        replace : bool
            If True, sampling is with replacement (songs can repeat).
            If False, sampling is without replacement (no duplicates).

        Returns
        -------
        List[MusicItem]
        """
        if length <= 0:
            return []

        if replace:
            if use_popularity:
                idxs = self.rng.choice(self.n_songs, size=length, p=self.popularity)
            else:
                idxs = self.rng.randint(0, self.n_songs, size=length)
        else:
            # Without replacement: use choice with replace=False
            if length > self.n_songs:
                raise ValueError("length cannot exceed n_songs when replace=False.")
            if use_popularity:
                idxs = self.rng.choice(self.n_songs, size=length, replace=False, p=self.popularity)
            else:
                idxs = self.rng.choice(self.n_songs, size=length, replace=False)

        return [self.songs[int(i)] for i in idxs]

    def get_feature_matrix(self) -> np.ndarray:
        """
        Return a matrix of shape (n_songs, dim) with all song features stacked row-wise.
        """
        feats = [np.asarray(it.features, dtype=np.float64).reshape(1, -1) for it in self.songs]
        return np.vstack(feats)
