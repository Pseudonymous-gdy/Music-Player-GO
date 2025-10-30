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
    约定：MusicItem.features 存放的是 θ_a （臂参数），而不是 x_{t,a}
    """
    def __init__(
        self,
        id: Union[int, str],
        features: Optional[Union[List[float], np.ndarray, torch.Tensor]] = None,
        name: Optional[str] = None,
        artist: Optional[str] = None
    ):
        self.id = id
        self.features = _to_numpy_1d(features)  # 这里存 θ_a
        self.name = name
        self.artist = artist

    def load_theta_from_csv(self, storage: str, id_col: str = "ID"):
        """
        从 CSV 载入 θ_a 到 self.features （与 test.csv 匹配）
        """
        df = pd.read_csv(storage)
        if id_col not in df.columns:
            raise ValueError(f"{id_col} not in {list(df.columns)}")

        # 规范列名
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
    Disjoint LinUCB：
      - MusicItem.features 保存 θ_a（可能为先验）
      - Recommender.contexts 保存/提供 x_{t,a}
    """
    def __init__(
        self,
        storage: Optional[str] = None,   # 用于读取 x_{t,a} 的 .npz（键=str(id)）
        playlist: Optional[List[MusicItem]] = None,
        alpha: float = 1.0,
        l2: float = 1.0,
        prior_lambda: float = 0.0
    ):
        self.playlist: List[MusicItem] = playlist if playlist is not None else []
        self.alpha = float(alpha)
        self.l2 = float(l2)
        self.prior_lambda = float(prior_lambda)

        # 上下文（当前轮可用的 x_{t,a}）缓存
        self.contexts: Dict[Union[int, str], np.ndarray] = {}
        if storage is not None:
            self.load_contexts_from_npz(storage)

        # LinUCB states
        self._A: Dict[Union[int, str], np.ndarray] = {}
        self._b: Dict[Union[int, str], np.ndarray] = {}
        self._theta_prior: Dict[Union[int, str], np.ndarray] = {}  # 记录从 item.features 或 CSV 得到的 θ 先验
        self._d: Optional[int] = self._infer_dim()

        # 如 playlist 中已有 θ_a，按 prior 注入
        self.apply_theta_priors_from_items()

    # ---------- IO ----------
    def load_contexts_from_npz(self, npz_path: str):
        """
        读取 x_{t,a}：npz 的每个 key 为 str(id)，值为 1D numpy 向量
        """
        if not os.path.exists(npz_path):
            raise FileNotFoundError(npz_path)
        data = np.load(npz_path, allow_pickle=False)
        self.contexts = {}
        for k in data.files:
            self.contexts[k] = _to_numpy_1d(data[k])
        # 维度可能由 x 决定
        self._d = self._infer_dim(force=True)

    def load_theta_priors_from_csv(self, csv_path: str, id_col: str = "ID"):
        """
        将 CSV 中的 θ_a 写入对应 MusicItem.features（若在 playlist 中），
        并按 prior_lambda 注入 A,b 作为先验
        """
        df = pd.read_csv(csv_path)
        df.columns = [str(c).strip() for c in df.columns]
        if id_col not in df.columns:
            raise ValueError(f"{id_col} not in CSV columns: {df.columns.tolist()}")
        feat_cols = [c for c in df.columns if c != id_col]
        if not feat_cols:
            raise ValueError("No theta columns found")

        # 构建 id -> theta
        id_to_theta = {}
        for _, row in df.iterrows():
            arm_id = row[id_col]
            theta = row[feat_cols].to_numpy(dtype=np.float64).reshape(-1)
            id_to_theta[str(arm_id)] = theta

        # 写回 playlist 中的 item.features，并注入先验
        for item in self.playlist:
            key = str(item.id)
            if key in id_to_theta:
                item.features = _to_numpy_1d(id_to_theta[key])
        self.apply_theta_priors_from_items()

    def save_params(self, path: str):
        # Keep a stable string representation for file keys, but iterate the
        # original keys so we can index into the in-memory dicts correctly.
        arm_keys = list(self._A.keys())
        arm_ids = np.array([str(k) for k in arm_keys], dtype=object)
        out = {
            "arm_ids": arm_ids,
            "alpha": np.array([self.alpha]),
            "l2": np.array([self.l2]),
            "prior_lambda": np.array([self.prior_lambda]),
            "d": np.array([self._d if self._d is not None else -1]),
        }
        for k in arm_keys:
            kstr = str(k)
            out[f"A__{kstr}"] = self._A[k]
            out[f"b__{kstr}"] = self._b[k]
            # theta_prior keys are stored as strings in self._theta_prior
            if kstr in self._theta_prior:
                out[f"theta_prior__{kstr}"] = self._theta_prior[kstr]
        np.savez_compressed(path, **out)

    def load_params(self, path: str):
        data = np.load(path, allow_pickle=True)
        self.alpha = float(data["alpha"][0])
        self.l2 = float(data["l2"][0])
        self.prior_lambda = float(data["prior_lambda"][0])
        d_val = int(data["d"][0])
        self._d = None if d_val < 0 else d_val
        self._A.clear(); self._b.clear(); self._theta_prior.clear()
        # arm_ids were saved as strings; attempt to convert numeric-looking ids
        # back to ints so in-memory keys match how MusicItem.id was used.
        raw_ids = [str(x) for x in data["arm_ids"]]
        for kstr in raw_ids:
            # choose int if kstr represents an integer, else keep string
            try:
                if kstr.isdigit():
                    k = int(kstr)
                else:
                    k = kstr
            except Exception:
                k = kstr
            self._A[k] = data[f"A__{kstr}"]
            self._b[k] = data[f"b__{kstr}"]
            tkey = f"theta_prior__{kstr}"
            if tkey in data.files:
                # theta_prior keys are stored with string ids
                self._theta_prior[kstr] = data[tkey]

    # ---------- core utils ----------
    def _infer_dim(self, force: bool = False) -> Optional[int]:
        # 先看 contexts（x 的维度）
        for k, v in self.contexts.items():
            if v is not None:
                return int(v.shape[0])
        # 再看任意 θ_a
        for it in self.playlist:
            if it.features is not None:
                return int(it.features.shape[0])
        return None if not force else None

    def _get_dim_or_raise(self) -> int:
        d = self._d or self._infer_dim()
        if d is None:
            raise ValueError("Unknown feature dim: need x_{t,a} (contexts) or θ_a to infer dimension.")
        self._d = d
        return d

    def _ensure_arm_initialized(self, arm_id: Union[int, str], d: int):
        if arm_id not in self._A:
            self._A[arm_id] = self.l2 * np.eye(d, dtype=np.float64)
            self._b[arm_id] = np.zeros(d, dtype=np.float64)
            # 若有 θ 先验且 prior_lambda>0，则即时注入
            key = str(arm_id)
            if key in self._theta_prior and self.prior_lambda > 0.0:
                self._A[arm_id] += self.prior_lambda * np.eye(d, dtype=np.float64)
                self._b[arm_id] += self.prior_lambda * self._theta_prior[key]

    def apply_theta_priors_from_items(self):
        """
        将当前 playlist 中 item.features(=θ_a) 记作先验，稍后在 _ensure_arm_initialized 时注入
        若已经有 A,b 则不重复加先验（只在首次初始化时注入）
        """
        for it in self.playlist:
            if it.features is not None:
                theta = _to_numpy_1d(it.features)
                if theta is None:
                    continue
                if self._d is None:
                    self._d = int(theta.shape[0])
                elif int(theta.shape[0]) != int(self._d):
                    raise ValueError(f"θ dim mismatch for arm {it.id}: got {theta.shape[0]}, expected {self._d}")
                self._theta_prior[str(it.id)] = theta

    # ---------- public API ----------
    def observe(self, item: MusicItem, reward: float, x_context: Optional[np.ndarray] = None):
        """
        更新 LinUCB：需要 x_{t,a}。若未显式给出，则从 self.contexts 里取。
        """
        d = self._get_dim_or_raise()
        # 取 x_{t,a}
        if x_context is None:
            x = self.contexts.get(str(item.id), None)
        else:
            x = _to_numpy_1d(x_context)
        if x is None:
            raise ValueError(f"observe(): missing x_{ {item.id} }")
        if x.shape[0] != d:
            raise ValueError(f"x dim mismatch: got {x.shape[0]}, expected {d}")

        self._ensure_arm_initialized(item.id, d)
        self._A[item.id] = self._A[item.id] + np.outer(x, x)
        self._b[item.id] = self._b[item.id] + reward * x

    update = observe

    def selection(
        self,
        policy: str = 'random',
        n: int = 1,
        contexts_by_id: Optional[Dict[Union[int, str], np.ndarray]] = None
    ) -> List[MusicItem]:
        assert policy in ['random', 'LinUCB', 'LinUCB+']
        if len(self.playlist) == 0 or n <= 0:
            return []
        n = min(n, len(self.playlist))

        if policy == 'random':
            return random.sample(self.playlist, k=n)

        if policy == 'LinUCB':
            return self._select_linucb(n, contexts_by_id)

        if policy == 'LinUCB+':
            raise NotImplementedError("LinUCB+（Hybrid）尚未实现。")

    def _select_linucb(
        self,
        n: int,
        contexts_by_id: Optional[Dict[Union[int, str], np.ndarray]] = None
    ) -> List[MusicItem]:
        d = self._get_dim_or_raise()
        scored: List[Tuple[float, MusicItem]] = []

        for item in self.playlist:
            # 拿 x_{t,a}
            x = None
            if contexts_by_id is not None and str(item.id) in contexts_by_id:
                x = _to_numpy_1d(contexts_by_id[str(item.id)])
            elif str(item.id) in self.contexts:
                x = _to_numpy_1d(self.contexts[str(item.id)])
            if x is None:
                # 没有上下文就跳过该臂
                continue
            if x.shape[0] != d:
                continue

            self._ensure_arm_initialized(item.id, d)
            A = self._A[item.id]; b = self._b[item.id]
            A_inv = np.linalg.inv(A)
            theta_hat = A_inv @ b
            mean = float(theta_hat @ x)
            bonus = self.alpha * float(np.sqrt(x @ (A_inv @ x)))
            scored.append((mean + bonus, item))

        if not scored:
            # 若完全缺少上下文，退化为随机
            return random.sample(self.playlist, k=n)

        scored.sort(key=lambda t: t[0], reverse=True)
        out, seen = [], set()
        for _, it in scored:
            if it.id in seen:
                continue
            out.append(it); seen.add(it.id)
            if len(out) >= n: break
        return out
