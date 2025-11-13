# audio_features_fixed.py
import os
import json
import numpy as np
import librosa

def load_mono_16k(path, target_sr=16000):
    y, sr = librosa.load(path, sr=target_sr, mono=True)
    return np.asarray(y, dtype=np.float32), target_sr

def logmel_db(y, sr, n_mels=128, n_fft=400, hop_length=160, fmin=20, fmax=None):
    S = librosa.feature.melspectrogram(
        y=y, sr=sr, n_fft=n_fft, hop_length=hop_length,
        n_mels=n_mels, fmin=fmin, fmax=fmax, power=2.0
    )
    S_db = librosa.power_to_db(S, ref=np.max)    # (n_mels, T)
    return S_db.T.astype(np.float32)             # -> (T, n_mels)

def mfcc_13(y, sr, n_mfcc=13, n_fft=400, hop_length=160):
    M = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=n_mfcc, n_fft=n_fft, hop_length=hop_length)  # (n_mfcc, T)
    return M.T.astype(np.float32)  # (T, n_mfcc)

def time_pool_stats(X, how="meanstd", extra=False):
    """
    X: (T, D)  on time axis.
    how: 'meanstd' | 'mean' | 'meanstdminmax' | 'p10p50p90' | 'all'
    extra=True 时，mean/std 会先做数值稳定处理。
    """
    if X.size == 0:
        raise ValueError("Empty feature matrix.")
    T, D = X.shape
    # 数值稳定
    if extra:
        X = np.nan_to_num(X, nan=0.0, posinf=0.0, neginf=0.0)

    def _meanstd(A):
        m = A.mean(axis=0)
        s = A.std(axis=0, ddof=0)
        return np.concatenate([m, s], axis=0)

    if how == "mean":
        v = X.mean(axis=0)                            # (D,)
    elif how == "meanstd":
        v = _meanstd(X)                               # (2D,)
    elif how == "meanstdminmax":
        v = np.concatenate([_meanstd(X), X.min(0), X.max(0)], axis=0)  # (4D,)
    elif how == "p10p50p90":
        p = np.percentile(X, [10, 50, 90], axis=0)    # (3, D)
        v = p.reshape(-1)                             # (3D,)
    elif how == "all":
        p = np.percentile(X, [10, 50, 90], axis=0)    # (3, D)
        v = np.concatenate([_meanstd(X), X.min(0), X.max(0), p.reshape(-1)], axis=0)  # (7D,)
    else:
        raise ValueError(f"Unknown pooling: {how}")
    return v.astype(np.float32)

def make_fixed_vector(path, feature="logmel", n_mels=128, n_mfcc=13,
                      pool="meanstd", n_fft=400, hop_length=160):
    y, sr = load_mono_16k(path, 16000)

    if feature == "logmel":
        F = logmel_db(y, sr, n_mels=n_mels, n_fft=n_fft, hop_length=hop_length)  # (T, n_mels)
        x = time_pool_stats(F, how=pool, extra=True)  # 长度取决于 pool 与 n_mels
        cfg = {"feature":"logmel", "n_mels": n_mels}
    elif feature == "mfcc":
        F = mfcc_13(y, sr, n_mfcc=n_mfcc, n_fft=n_fft, hop_length=hop_length)   # (T, n_mfcc)
        x = time_pool_stats(F, how=pool, extra=True)
        cfg = {"feature":"mfcc", "n_mfcc": n_mfcc}
    else:
        raise ValueError("feature must be 'logmel' or 'mfcc'.")

    # 统一做一次 L2 归一化（可选）
    norm = np.linalg.norm(x) + 1e-8
    x = (x / norm).astype(np.float32)

    meta = {
        "sr": sr, "samples": int(len(y)),
        "duration_sec": round(len(y)/sr, 3),
        "source": path,
        "feature_cfg": {**cfg, "n_fft": n_fft, "hop_length": hop_length, "pool": pool, "l2norm": True}
    }
    return x, meta

def save_npz(out_path, x, meta):
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    meta_json = json.dumps(meta, ensure_ascii=False).encode("utf-8")
    np.savez_compressed(out_path, x=x, meta=meta_json)
