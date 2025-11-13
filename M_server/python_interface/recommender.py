import os
import torch
import numpy as np
import pandas as pd
import random
from typing import Optional, Union, List, Dict, Tuple

def _to_numpy_1d(x) -> Optional[np.ndarray]:
    if x is None:
        return None
    if isinstance(x, torch.Tensor):
        x = x.detach().cpu().numpy()
    x = np.asarray(x)
    x = np.squeeze(x)
    if x.ndim != 1:
        x = x.reshape(-1)
    return x.astype(np.float64)

class MusicItem:
    """
    Music item class for recommendation.
    - id: item id (key for getting everything)
    - features: arm parameter x_{t,a} (not context θ_a)
    - name, artist: optional metadata

    Note:
    - features may be None initially; can be set later via load_theta()
    - But you need to include the feature dimension via `feature_dim` kwarg when initializing
    """
    def __init__(
        self,
        id: Union[int, str],
        features: Optional[Union[List[float], np.ndarray, torch.Tensor]] = None,
        name: Optional[str] = None,
        artist: Optional[str] = None,
        **kwargs
    ):
        self.id = id
        if features is not None:
            feat = _to_numpy_1d(features)
            if feat is None:
                raise ValueError("features must not be None")
            self.features: np.ndarray = feat  # get θ_a
        else:
            # initialize
            self.features: np.ndarray = np.ones(kwargs.get("feature_dim", 5), dtype=np.float64)
        self.name = name
        self.artist = artist

    def load_x(self, storage: str, id_col: str = "ID"):
        """
        get x_{t,a} (not θ_a) from CSV/npz and load to self.features
        """
        if storage.endswith('.npz'):
            data = np.load(storage, allow_pickle=False)
            key = str(self.id)
            if key not in data.files:
                raise ValueError(f"θ for ID={self.id} not found in NPZ: {storage}")
            x_a = data[key]
            self.features = _to_numpy_1d(x_a)
            return
        df = pd.read_csv(storage)
        if id_col not in df.columns:
            raise ValueError(f"{id_col} not in {list(df.columns)}")

        # Clean column names
        df.columns = [str(c).strip() for c in df.columns]
        feat_cols = [c for c in df.columns if c != id_col]
        if not feat_cols:
            raise ValueError("No theta columns in CSV")

        key = str(self.id) if df[id_col].dtype == object else self.id
        row = df[df[id_col] == key]
        if row.empty:
            raise ValueError(f"θ for ID={self.id} not found in CSV: {storage}")
        theta = row.iloc[0][feat_cols].to_numpy(dtype=np.float64)
        self.features = _to_numpy_1d(theta)

class Recommender:
    """
    Disjoint LinUCB:
      - MusicItem.features keep x_{t,a} (may be prior)
      - Recommender.contexts save/provide θ_a
    """
    def __init__(
            self,
            storage: str = "Python_Interface/recommender_params.npz", # Path for loading/saving parameters, .npz format
            playlist: Optional[List[MusicItem]] = None,
            alpha: float = 1.0,
            l2: float = 1.0,
            initialization: bool = True # for testing, independently initialize parameters without loading from storage
    ):
        self.storage = storage
        self.playlist = playlist if playlist is not None else []
        self.alpha = alpha
        self.l2 = l2

        # Internal parameter stores for disjoint LinUCB: A matrices and b vectors per item id
        self._A: Dict[Union[int, str], np.ndarray] = {}
        self._b: Dict[Union[int, str], np.ndarray] = {}

        # Initialize parameters for items already present in the playlist
        for it in self.playlist:
            d = it.features.shape[0]
            self._A[it.id] = np.eye(d, dtype=np.float64) * self.l2
            self._b[it.id] = np.zeros(d, dtype=np.float64)
        if not initialization:
            try:
                self.load_params()
            except FileNotFoundError:
                self.save_params()

    def load_params(self):
        """
        Load model parameters from NPZ file.
        """
        path = self.storage
        if not os.path.exists(path):
            raise FileNotFoundError(f"Parameter file not found: {path}")
        data = np.load(path, allow_pickle=False)
        # Load parameters for each item
        for it in self.playlist:
            key_A = f"A_{it.id}"
            key_b = f"b_{it.id}"
            if key_A in data.files and key_b in data.files:
                self._A[it.id] = data[key_A]
                self._b[it.id] = data[key_b]
            else:
                # Initialize if not found
                d = it.features.shape[0]
                self._A[it.id] = np.eye(d, dtype=np.float64) * self.l2
                self._b[it.id] = np.zeros(d, dtype=np.float64)

    def save_params(self):
        """
        Save model parameters to NPZ file.
        """
        path = self.storage
        save_dict = {}
        for it in self.playlist:
            save_dict[f"A_{it.id}"] = self._A[it.id]
            save_dict[f"b_{it.id}"] = self._b[it.id]
        np.savez_compressed(path, **save_dict)
    
    def selection(self, policy: str="LinUCB", n: int = 2) -> List[MusicItem]:
        """
        Select top-n items based on the specified policy and provided contexts.
        """
        if policy != "LinUCB":
            raise ValueError(f"Unsupported policy: {policy}")

        scores = []
        for it in self.playlist:
            x_a = it.features  # x_{t,a}
            theta_a = np.linalg.solve(self._A[it.id], self._b[it.id])  # θ_a
            if theta_a is None:
                raise ValueError(f"Context θ for ID={it.id} not provided")

            A_inv = np.linalg.inv(self._A[it.id])
            pta = np.dot(theta_a, x_a) + self.alpha * np.sqrt(np.dot(x_a, np.dot(A_inv, x_a)))
            scores.append((pta, it))

        # Sort by score descending and select top-n
        scores.sort(key=lambda tup: tup[0], reverse=True)
        top_n_items = [tup[1] for tup in scores[:n]]
        return top_n_items

    def feedback(self, item: MusicItem, reward: float):
        """
        Update model parameters based on feedback (reward) for the selected item.
        """
        x_a = item.features
        self._A[item.id] += np.outer(x_a, x_a)
        self._b[item.id] += reward * x_a