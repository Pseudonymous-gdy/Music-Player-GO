import os
import torch
import numpy as np
import pandas as pd
import random
import math
import torch
import torch.nn as nn
import torch.nn.functional as F
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
    LinUCB+:
      - MusicItem.features keep x_{t,a} (may be prior)
      - Recommender.contexts save/provide θ_a
      - Additional RNN-based user's global preference vector should be implemented
    """
    def __init__(
            self,
            storage: str = "Python_Interface/recommender_params.npz", # Path for loading/saving parameters, .npz format
            playlist: Optional[List[MusicItem]] = None,
            alpha: float = 1.0,
            l2: float = 1.0,
            initialization: bool = True, # for testing, independently initialize parameters without loading from storage
            policy: str = "LinUCB", # LinUCB or LinUCB+
            discount: float = 0.7, # discount factor for avoiding recommending same item repeatedly
            **kwargs # additional arguments
    ):
        self.storage = storage
        self.playlist = playlist if playlist is not None else []
        self.alpha = alpha
        self.l2 = l2
        self.policy = policy
        self.discount = discount

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
        
        if policy == 'LinUCB+':
            d = self.playlist[0].features.shape[0]
            self.rnn_model = RNN(dim=d, storage=self.storage,
                                hidden_size=kwargs.get('hidden_size', 2*d))
            self.rnn_model.load_model()
        self.last_selected_id = None

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
        dirn = os.path.dirname(path)
        if dirn and not os.path.exists(dirn):
            os.makedirs(dirn)

        save_dict = {}
        if os.path.exists(path):
            try:
                existing = np.load(path, allow_pickle=False)
                for k in existing.files:
                    save_dict[k] = existing[k]
            except Exception:
                save_dict = {}
        # Preserve existing parameters for items not in the current playlist
        for it in self.playlist:
            save_dict[f"A_{it.id}"] = self._A[it.id]
            save_dict[f"b_{it.id}"] = self._b[it.id]
        np.savez_compressed(path, **save_dict)
    
    def selection(self, n: int = 2) -> List[MusicItem]:
        """
        Select top-n items based on the specified policy and provided contexts.
        """

        scores = []
        for it in self.playlist:
            x_a = it.features  # x_{t,a}
            theta_a = np.linalg.solve(self._A[it.id], self._b[it.id])  # θ_a
            if theta_a is None:
                raise ValueError(f"Context θ for ID={it.id} not provided")

            A_inv = np.linalg.inv(self._A[it.id])
            pta = np.dot(theta_a, x_a) + self.alpha * np.sqrt(np.dot(x_a, np.dot(A_inv, x_a)))
            if self.policy == 'LinUCB+':
                # Get β_t from RNN
                _, beta_t = self.rnn_model.forward(x_a, self.rnn_model.h_t_1)
                beta_t_np = beta_t.detach().cpu().numpy()
                pta += np.dot(beta_t_np, x_a)
            if it.id == self.last_selected_id:
                pta *= self.discount  # apply discount to last selected item to avoid repetition
            scores.append((pta, it))

        # Sort by score descending and select top-n
        scores.sort(key=lambda tup: tup[0], reverse=True)
        top_n_items = [tup[1] for tup in scores[:n]]
        return top_n_items
    
    def add_item(self, item: MusicItem):
        '''
        Add a new music item to the playlist and initialize its parameters.
        '''
        self.save_params()  # Save current parameters
        self.playlist.append(item)
        self.load_params()  # Reload parameters to include the new item
        self.feedback(item, 1e-2) # Reward lightly to prioritize

    def remove_item(self, item: MusicItem):
        '''
        Remove a music item from the playlist and save parameters.
        '''
        self.feedback(item, -5) # Penalize heavily to de-prioritize
        self.save_params()
        self.playlist.remove(item)
        self.load_params()  # Reload parameters to exclude the removed item


    def feedback(self, item: MusicItem, reward: float):
        """
        Update model parameters based on feedback (reward) for the selected item.
        """
        x_a = item.features
        if self.policy == 'LinUCB+':
            # reward = x_{t,a}×θ_t + β_t×θ_t
            reward_ = reward - np.dot(np.linalg.solve(self._A[item.id], self._b[item.id]), x_a)
            self.rnn_model.train_per_update(x_a, reward_)
        self._A[item.id] += np.outer(x_a, x_a)
        self._b[item.id] += reward * x_a
        self.last_selected_id = item.id


class RNN(nn.Module):
    """
    Single-layer vanilla RNN (no batch dimension by default).

    This module is intended to model a global user preference vector β_t.
    - Input at each time step: x_t ∈ R^dim
    - Hidden state: h_t ∈ R^hidden_size
    - Preference vector: β_t ∈ R^dim

    For compatibility with your reward term x_{t,a}^T β_t, you typically want:
        hidden_size == dim

    Interface matches your Recommender usage:
        RNN(dim=d, storage=storage_path, hidden_size=..., nonlinearity="tanh")
    """

    def __init__(
        self,
        dim: int,
        storage: str,
        hidden_size: int,
        nonlinearity: str = "tanh",
    ):
        super().__init__()
        assert nonlinearity in ("tanh", "relu")

        self.dim = dim
        self.hidden_size = hidden_size
        self.storage = storage  # shared storage path with Recommender
        self.nonlinearity = nonlinearity

        # RNN cell parameters:
        #   h_t = φ(W_ih x_t + b_ih + W_hh h_{t-1} + b_hh)
        self.W_ih = nn.Parameter(torch.Tensor(hidden_size, dim))
        self.b_ih = nn.Parameter(torch.Tensor(hidden_size))

        self.W_hh = nn.Parameter(torch.Tensor(hidden_size, hidden_size))
        self.b_hh = nn.Parameter(torch.Tensor(hidden_size))

        # Map hidden state to β_t ∈ R^dim
        self.W_output = nn.Parameter(torch.Tensor(dim, hidden_size))
        self.b_output = nn.Parameter(torch.Tensor(dim))

        # Internal state for online training
        self.X_t_1 = None  # previous input feature (numpy array)
        self.h_t_1 = None  # previous hidden state (torch tensor)
        self.beta_t = torch.zeros(dim, dtype=torch.float32, requires_grad=False)

        self.reset_parameters()

        # IMPORTANT: create optimizer AFTER parameters are registered
        self.optimizer = torch.optim.Adam(self.parameters(), lr=1e-3)

    def reset_parameters(self):
        """
        Initialize all parameters with a simple uniform distribution.
        """
        stdv = 1.0 / math.sqrt(self.hidden_size)
        for p in self.parameters():
            p.data.uniform_(-stdv, stdv)

    def forward(self, feature, h0: Optional[torch.Tensor] = None):
        """
        Forward pass through the RNN.

        Args:
            feature: input feature vector x_t (numpy array or torch tensor)
            h0: initial hidden state h_0 (torch tensor), if None, initialized to zeros

        Returns:
            h_t:    hidden state at time t (torch tensor, shape: (hidden_size,))
            beta_t: preference vector at time t (torch tensor, shape: (dim,))
        """
        # Convert input feature to 1D float tensor
        if isinstance(feature, np.ndarray):
            x_t = torch.from_numpy(feature).float()
        elif isinstance(feature, torch.Tensor):
            x_t = feature.float().view(-1)
        else:
            x_t = torch.tensor(feature, dtype=torch.float32).view(-1)

        if x_t.numel() != self.dim:
            raise ValueError(f"Expected input feature dim={self.dim}, got {x_t.numel()}")

        # Prepare previous hidden state
        if h0 is None:
            h_t_1 = torch.zeros(self.hidden_size, dtype=torch.float32)
        else:
            # Detach to avoid backprop through the whole history
            h_t_1 = h0.detach().float()

        # RNN cell computation
        h_t = torch.matmul(self.W_ih, x_t) + self.b_ih \
              + torch.matmul(self.W_hh, h_t_1) + self.b_hh

        if self.nonlinearity == "tanh":
            h_t = torch.tanh(h_t)
        else:
            h_t = F.relu(h_t)

        # Map hidden state to β_t
        beta_t = torch.matmul(self.W_output, h_t) + self.b_output
        return h_t, beta_t

    def save_model(self):
        """
        Save RNN parameters into the shared .npz file.
        Keys are prefixed with 'rnn_' to avoid collision with LinUCB params.
        """
        path = self.storage
        dirn = os.path.dirname(path)
        if dirn and not os.path.exists(dirn):
            os.makedirs(dirn)

        # Load existing data if the file already exists
        save_dict = {}
        if os.path.exists(path):
            try:
                existing = np.load(path, allow_pickle=False)
                for k in existing.files:
                    save_dict[k] = existing[k]
            except Exception:
                # If loading fails, start with an empty dict
                save_dict = {}

        # Add RNN parameters with 'rnn_' prefix
        state = self.state_dict()
        for name, tensor in state.items():
            key = f"rnn_{name}"
            save_dict[key] = tensor.detach().cpu().numpy()

        np.savez_compressed(path, **save_dict)

    def load_model(self):
        """
        Load RNN parameters from the shared .npz file if present.
        Missing or shape-mismatched parameters are left as initialized.
        """
        path = self.storage
        if not os.path.exists(path):
            # Nothing to load yet; keep current initialization
            return

        data = np.load(path, allow_pickle=False)
        state = self.state_dict()
        new_state = {}

        for name, tensor in state.items():
            key = f"rnn_{name}"
            if key in data.files:
                arr = data[key]
                t = torch.from_numpy(arr).to(tensor.dtype)
                if t.shape == tensor.shape:
                    new_state[name] = t
                else:
                    # Shape mismatch: keep the current initialized tensor
                    new_state[name] = tensor
            else:
                # Key not found: keep the current initialized tensor
                new_state[name] = tensor

        self.load_state_dict(new_state)

    def train_per_update(self, features: np.ndarray, reward: float, lr: float = 1e-3):
        """
        Perform a single update step for training.

        Args:
            features: Input features for the RNN (for update), numpy array of shape (dim,)
            reward: Reward signal for the current step (scalar).
            lr: Learning rate for the update.
        """
        # Use explicit None checks to avoid ambiguity with numpy arrays / tensors
        if self.X_t_1 is None or self.h_t_1 is None:
            # First time step: initialize previous input and hidden state
            self.X_t_1 = features
            self.h_t_1 = torch.zeros(self.hidden_size, dtype=torch.float32)

        # Optionally adjust learning rate
        for group in self.optimizer.param_groups:
            group["lr"] = lr

        self.optimizer.zero_grad()

        # One-step delayed update:
        # use (X_{t-1}, h_{t-1}) to produce β_t, then compare with reward for X_t
        self.h_t_1, self.beta_t = self.forward(self.X_t_1, self.h_t_1)

        # Update stored previous input for next step
        self.X_t_1 = features

        x_t = torch.from_numpy(features).float()
        predicted_reward = torch.dot(self.beta_t, x_t)

        loss = F.mse_loss(predicted_reward, torch.tensor(reward, dtype=torch.float32))
        loss.backward()
        self.optimizer.step()
