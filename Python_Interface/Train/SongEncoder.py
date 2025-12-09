"""
SongEncoder.py

Encode real audio tracks (e.g. FMA dataset) into fixed-dimensional
song embeddings using librosa.

Default design:
  - Look for audio files under Train/Data/ (recursively).
  - For each *.mp3 file:
      * Load mono audio (optionally only first N seconds).
      * Compute log-mel spectrogram with n_mels=64.
      * Pool over time with [mean, std] -> 64 * 2 = 128 dims.
  - Save all embeddings into a single NPZ file:
      key   = track_id (string, taken from file stem, e.g. "000123")
      value = np.ndarray of shape (128,)

You can later load this NPZ and feed each vector into MusicItem.features.

Usage (from repo root), for example:
    python Python_Interface/Train/SongEncoder.py \
        --audio-root Python_Interface/Train/Data \
        --pattern "*.mp3" \
        --output-npz Python_Interface/Train/Data/fma_song_embeddings_128d.npz
"""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Dict, Tuple

import numpy as np
import librosa


# ----------------------------------------------------------------------
# Core feature extraction
# ----------------------------------------------------------------------
def extract_logmel_embedding(
    y: np.ndarray,
    sr: int,
    n_mels: int = 64,
    n_fft: int = 2048,
    hop_length: int = 512,
) -> np.ndarray:
    """
    Extract a fixed-dimensional log-mel embedding from a 1D audio signal.

    Steps:
      1. Compute mel-spectrogram: shape (n_mels, T)
      2. Convert to log scale.
      3. Pool over time with mean and std:
             mean: shape (n_mels,)
             std:  shape (n_mels,)
         Concatenate -> shape (2 * n_mels,) = 128 when n_mels=64.

    This gives a robust, order-agnostic summary of the timbral / spectral
    content of the track, suitable as MusicItem.features.
    """
    # Mel-spectrogram (power)
    S = librosa.feature.melspectrogram(
        y=y,
        sr=sr,
        n_fft=n_fft,
        hop_length=hop_length,
        n_mels=n_mels,
        power=2.0,
    )  # shape: (n_mels, T)

    # Log scale (avoid log(0))
    S_log = librosa.power_to_db(S, ref=np.max)  # (n_mels, T)

    # Time pooling: mean and std over axis=1
    mean_vec = np.mean(S_log, axis=1)
    std_vec = np.std(S_log, axis=1)

    # Concatenate -> fixed-dim vector
    emb = np.concatenate([mean_vec, std_vec], axis=0)  # (2 * n_mels,)
    return emb.astype(np.float32)


def encode_single_file(
    audio_path: Path,
    sr: int,
    duration: float,
    n_mels: int,
    n_fft: int,
    hop_length: int,
) -> Tuple[str, np.ndarray]:
    """
    Encode a single audio file into a fixed-dimensional embedding.

    Parameters
    ----------
    audio_path : Path
        Path to the audio file.
    sr : int
        Target sampling rate for librosa.load.
    duration : float
        If > 0, only load the first `duration` seconds.
        If <= 0, load full file.
    n_mels, n_fft, hop_length :
        Parameters for mel-spectrogram extraction.

    Returns
    -------
    track_id : str
        The identifier for this track (here we simply use file stem).
    emb : np.ndarray
        Embedding vector of shape (2 * n_mels,).
    """
    # For FMA, file names are like "000123.mp3", we use "000123" as track_id.
    track_id = audio_path.stem

    if duration > 0:
        y, _ = librosa.load(
            path=str(audio_path),
            sr=sr,
            mono=True,
            duration=duration,
        )
    else:
        y, _ = librosa.load(
            path=str(audio_path),
            sr=sr,
            mono=True,
        )

    # Safety check: empty or too-short audio
    if y.size < hop_length:
        # Fallback: pad with zeros or simply return zeros
        # Here we choose to pad.
        pad_len = hop_length - y.size
        y = np.pad(y, (0, pad_len), mode="constant")

    emb = extract_logmel_embedding(
        y=y,
        sr=sr,
        n_mels=n_mels,
        n_fft=n_fft,
        hop_length=hop_length,
    )
    return track_id, emb


# ----------------------------------------------------------------------
# Directory encoding
# ----------------------------------------------------------------------
def encode_directory(
    audio_root: Path,
    pattern: str,
    sr: int,
    duration: float,
    n_mels: int,
    n_fft: int,
    hop_length: int,
) -> Dict[str, np.ndarray]:
    """
    Walk through `audio_root` recursively, encode all matching audio files.

    Parameters
    ----------
    audio_root : Path
        Root directory that contains audio files (e.g. Train/Data).
        We will search recursively for files matching `pattern`.
    pattern : str
        Glob pattern for audio files, e.g. "*.mp3" or "*.wav".
    sr : int
        Target sampling rate for librosa.load.
    duration : float
        See encode_single_file().
    n_mels, n_fft, hop_length :
        See extract_logmel_embedding().

    Returns
    -------
    emb_dict : Dict[str, np.ndarray]
        Mapping from track_id (str) to embedding (2 * n_mels,).
    """
    emb_dict: Dict[str, np.ndarray] = {}
    audio_files = sorted(audio_root.rglob(pattern))

    if not audio_files:
        print(f"[SongEncoder] No files matched pattern '{pattern}' under {audio_root}")
        return emb_dict

    print(f"[SongEncoder] Found {len(audio_files)} audio files under {audio_root}")

    for idx, audio_path in enumerate(audio_files, start=1):
        try:
            track_id, emb = encode_single_file(
                audio_path=audio_path,
                sr=sr,
                duration=duration,
                n_mels=n_mels,
                n_fft=n_fft,
                hop_length=hop_length,
            )
            emb_dict[track_id] = emb

        except Exception as e:
            # Log and continue; a few broken files should not crash the whole job
            print(f"[SongEncoder] ERROR on file {audio_path}: {e}")

        if idx % 50 == 0 or idx == len(audio_files):
            print(
                f"[SongEncoder] Encoded {idx}/{len(audio_files)} files "
                f"({100.0 * idx / max(len(audio_files), 1):.1f}%)"
            )

    return emb_dict


# ----------------------------------------------------------------------
# Saving / CLI
# ----------------------------------------------------------------------
def save_embeddings_npz(
    emb_dict: Dict[str, np.ndarray],
    output_path: Path,
) -> None:
    """
    Save embeddings to NPZ file.

    Keys are track_ids (strings), values are embedding vectors.

    Example:
        data = np.load(output_path, allow_pickle=False)
        vec = data["000123"]  # -> shape (128,)
    """
    if not emb_dict:
        print("[SongEncoder] No embeddings to save; skipping NPZ write.")
        return

    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Build a dict for np.savez_compressed
    npz_dict = {k: v for k, v in emb_dict.items()}
    np.savez_compressed(str(output_path), **npz_dict)
    print(
        f"[SongEncoder] Saved {len(emb_dict)} embeddings "
        f"to {output_path} (dim={next(iter(emb_dict.values())).shape[0]})"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Encode FMA (or other) audio tracks into fixed 128-dim song embeddings using librosa."
    )
    parser.add_argument(
        "--audio-root",
        type=str,
        default=str(Path(__file__).resolve().parent / "Data"),
        help="Root directory containing audio files (default: Train/Data relative to this script).",
    )
    parser.add_argument(
        "--pattern",
        type=str,
        default="*.mp3",
        help="Glob pattern to match audio files (default: '*.mp3').",
    )
    parser.add_argument(
        "--output-npz",
        type=str,
        default=str(Path(__file__).resolve().parent / "Data" / "fma_song_embeddings_128d.npz"),
        help="Output NPZ file path for embeddings.",
    )
    parser.add_argument(
        "--sample-rate",
        type=int,
        default=22050,
        help="Target sampling rate for librosa.load (default: 22050).",
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=30.0,
        help="Max audio duration in seconds to load per file (default: 30.0; <=0 means full file).",
    )
    parser.add_argument(
        "--n-mels",
        type=int,
        default=64,
        help="Number of mel bands (default: 64; embedding dim will be 2 * n_mels).",
    )
    parser.add_argument(
        "--n-fft",
        type=int,
        default=2048,
        help="FFT window size for STFT (default: 2048).",
    )
    parser.add_argument(
        "--hop-length",
        type=int,
        default=512,
        help="Hop length for STFT (default: 512).",
    )
    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()

    audio_root = Path(args.audio_root).resolve()
    output_path = Path(args.output_npz).resolve()

    print(f"[SongEncoder] Audio root: {audio_root}")
    print(f"[SongEncoder] Output NPZ: {output_path}")
    print(f"[SongEncoder] sample_rate={args.sample_rate}, duration={args.duration}, "
          f"n_mels={args.n_mels}, n_fft={args.n_fft}, hop_length={args.hop_length}")

    emb_dict = encode_directory(
        audio_root=audio_root,
        pattern=args.pattern,
        sr=args.sample_rate,
        duration=args.duration,
        n_mels=args.n_mels,
        n_fft=args.n_fft,
        hop_length=args.hop_length,
    )
    save_embeddings_npz(emb_dict, output_path)


if __name__ == "__main__":
    main()
