import sys
# add the directory to the system path to avoid below import errors
sys.path.append("..")

from Recommender import *
import random
import torch
import pandas
import numpy as np
from typing import Optional, Dict, Any, Union

class UserSimulator:
    """
    Simulate a *single* user's preference dynamics for music recommendation.

    Preference decomposition:
      - global_pref_t (long-term / stable preference):
          A slowly drifting preference vector over the feature space.
      - temp_pref_t (short-term / temporary preference):
          A fast-changing preference determined by recently listened songs.
      - beta_t (real-time preference):
          A weighted combination of global_pref_t and temp_pref_t.

    Reward model (interpretable as satisfaction score or click probability):
        1. Compute a utility from the real-time preference and the current song:
               utility_t = <beta_t, x_t>
        2. Map utility_t to a reward using either a dot or logistic mode and add noise.

    Typical usage:
      - As an environment to pretrain LinUCB+RNN or bandit + RNN models.
      - Given a MusicItem (or raw feature vector), call step(...) to get a reward.

    Parameters
    ----------
    dim : int
        Feature dimension of MusicItem.features.
    global_drift_std : float
        Standard deviation of the Gaussian noise controlling how fast
        the long-term preference drifts. Smaller value = more stable.
    temp_decay : float in (0, 1)
        Exponential decay coefficient for short-term preference.
        Closer to 1.0 means the user remembers older songs longer.
    merge_lambda : float in [0, 1]
        Weight for the short-term preference in the real-time preference:
            beta_t = (1 - merge_lambda) * global_pref_t
                     + merge_lambda * temp_pref_t
    noise_std : float
        Standard deviation of Gaussian noise added on top of mean reward
        (simulates random user behavior).
    reward_mode : {"dot", "logistic"}
        - "dot":      reward ~ <beta_t, x_t> + noise
        - "logistic": reward ~ sigmoid(<beta_t, x_t> / temp) + noise,
                      truncated to [0, 1].
    logistic_temp : float
        Temperature for the logistic mode. Larger value yields smoother output.
    seed : Optional[int]
        Random seed for reproducibility.
    """

    def __init__(
        self,
        dim: int,
        global_drift_std: float = 0.01,
        temp_decay: float = 0.9,
        merge_lambda: float = 0.3,
        noise_std: float = 0.05,
        reward_mode: str = "logistic",
        logistic_temp: float = 0.5,
        seed: Optional[int] = None,
    ):
        assert 0.0 < temp_decay < 1.0, "temp_decay should be in (0, 1)."
        assert 0.0 <= merge_lambda <= 1.0, "merge_lambda should be in [0, 1]."
        assert reward_mode in ("dot", "logistic"), "reward_mode must be 'dot' or 'logistic'."

        self.dim = dim
        self.global_drift_std = float(global_drift_std)
        self.temp_decay = float(temp_decay)
        self.merge_lambda = float(merge_lambda)
        self.noise_std = float(noise_std)
        self.reward_mode = reward_mode
        self.logistic_temp = float(logistic_temp)

        self.rng = np.random.RandomState(seed)

        # Internal state
        self.t: int = 0
        self.global_pref: np.ndarray = np.zeros(dim, dtype=np.float64)
        self.temp_pref: np.ndarray = np.zeros(dim, dtype=np.float64)
        self._init_global_pref()

    # ------------------------------------------------------------------
    # Initialization / reset
    # ------------------------------------------------------------------
    def _init_global_pref(self):
        """
        Initialize the long-term preference vector to a random unit vector.
        """
        v = self.rng.randn(self.dim).astype(np.float64)
        v /= (np.linalg.norm(v) + 1e-8)
        self.global_pref = v
        self.temp_pref = np.zeros_like(v)
        self.t = 0

    def reset(self, resample_global: bool = False):
        """
        Reset the user state at the start of a new episode.

        Parameters
        ----------
        resample_global : bool
            If True:
                Resample a new long-term preference (effectively a new user).
            If False:
                Keep the long-term preference and clear only short-term state
                (same user, new session).
        """
        if resample_global:
            self._init_global_pref()
        else:
            self.temp_pref = np.zeros_like(self.global_pref)
            self.t = 0

    # ------------------------------------------------------------------
    # Preference updates
    # ------------------------------------------------------------------
    def _update_global_pref(self):
        """
        Apply a very small random drift to the long-term preference.
        This keeps the user stable but not completely static.
        """
        eps = self.rng.normal(loc=0.0, scale=self.global_drift_std, size=self.dim)
        self.global_pref = self.global_pref + eps
        # Normalize to avoid exploding norms
        self.global_pref /= (np.linalg.norm(self.global_pref) + 1e-8)

    def _update_temp_pref(self, x_t: np.ndarray):
        """
        Update the short-term preference based on the current song feature x_t.

        We use an exponential moving average:
            temp_pref_t = decay * temp_pref_{t-1} + (1 - decay) * x_t
        and then normalize it to focus on direction rather than magnitude.
        """
        self.temp_pref = self.temp_decay * self.temp_pref + (1.0 - self.temp_decay) * x_t
        self.temp_pref /= (np.linalg.norm(self.temp_pref) + 1e-8)

    def get_realtime_pref(self) -> np.ndarray:
        """
        Compute the real-time preference:
            beta_t = (1 - merge_lambda) * global_pref_t
                     + merge_lambda * temp_pref_t,
        then normalize it to a unit vector.
        """
        beta = (1.0 - self.merge_lambda) * self.global_pref + self.merge_lambda * self.temp_pref
        beta /= (np.linalg.norm(beta) + 1e-8)
        return beta

    # ------------------------------------------------------------------
    # Interaction: given a song -> return a reward
    # ------------------------------------------------------------------
    def step(
        self,
        item_or_feature: Union["MusicItem", np.ndarray],
        return_info: bool = False,
    ) -> Union[float, tuple]:
        """
        Simulate listening to a single song and return a reward.

        Parameters
        ----------
        item_or_feature : MusicItem or np.ndarray
            If MusicItem:
                Use item.features as x_t.
            If np.ndarray:
                Treated directly as x_t with shape (dim,).
        return_info : bool
            If True, returns (reward, info) where info contains internal
            state for logging/analysis.

        Returns
        -------
        reward : float
            Simulated user reward.
        (reward, info) : (float, Dict[str, Any]) if return_info is True
            info includes:
                - "t"
                - "global_pref"
                - "temp_pref"
                - "beta_t"
                - "utility"
        """
        # Extract feature vector x_t
        if isinstance(item_or_feature, np.ndarray):
            x_t = item_or_feature.astype(np.float64).reshape(-1)
        else:
            # Assumes compatibility with the provided MusicItem class
            x_t = np.asarray(item_or_feature.features, dtype=np.float64).reshape(-1)

        if x_t.shape[0] != self.dim:
            raise ValueError(f"Expected feature dim={self.dim}, got {x_t.shape[0]}")

        # Optional normalization to prevent scale issues
        norm = np.linalg.norm(x_t)
        if norm > 1e-8:
            x_t = x_t / norm

        # Advance time
        self.t += 1

        # Update long-term and short-term preferences
        self._update_global_pref()
        self._update_temp_pref(x_t)

        # Combine into real-time preference
        beta_t = self.get_realtime_pref()

        # Compute utility
        utility = float(np.dot(beta_t, x_t))  # Roughly in [-1, 1]

        # Map utility to mean reward
        if self.reward_mode == "dot":
            mean_reward = utility
        else:  # "logistic"
            z = utility / max(self.logistic_temp, 1e-6)
            mean_reward = 1.0 / (1.0 + np.exp(-z))  # in (0, 1)

        # Add stochastic noise
        reward = mean_reward + self.noise_std * self.rng.randn()

        # In logistic mode, it is natural to keep reward within [0, 1]
        if self.reward_mode == "logistic":
            reward = float(np.clip(reward, 0.0, 1.0))
        else:
            reward = float(reward)

        if not return_info:
            return reward

        info: Dict[str, Any] = {
            "t": self.t,
            "global_pref": self.global_pref.copy(),
            "temp_pref": self.temp_pref.copy(),
            "beta_t": beta_t.copy(),
            "utility": utility,
        }
        return reward, info
