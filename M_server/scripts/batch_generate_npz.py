import argparse
import os
from python_interface.service.file_service.audio_features_fixed import make_fixed_vector, save_npz

def batch_generate_npz(audio_files, out_dir, feature, pool, n_mels, n_mfcc):
    for audio_file in audio_files:
        try:
            x, meta = make_fixed_vector(audio_file, feature=feature, n_mels=n_mels, n_mfcc=n_mfcc, pool=pool)
            base = os.path.splitext(os.path.basename(audio_file))[0]
            save_npz(os.path.join(out_dir, f"{base}.npz"), x, meta)
            print(f"[OK] {base}.npz  x.shape={x.shape}  len={x.size}")
        except Exception as e:
            print(f"[ERROR] Failed to process {audio_file}: {e}")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audio_files", type=str, nargs='+', required=True, help="List of audio files to process.")
    ap.add_argument("--out_dir", type=str, default="features_out", help="Output directory for NPZ files.")
    ap.add_argument("--feature", choices=["logmel", "mfcc"], default="logmel", help="Feature type to extract.")
    ap.add_argument("--pool", choices=["mean", "meanstd", "meanstdminmax", "p10p50p90", "all"], default="meanstd", help="Pooling method.")
    ap.add_argument("--n_mels", type=int, default=128, help="Number of mel bands.")
    ap.add_argument("--n_mfcc", type=int, default=13, help="Number of MFCCs.")
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    batch_generate_npz(args.audio_files, args.out_dir, args.feature, args.pool, args.n_mels, args.n_mfcc)

if __name__ == "__main__":
    main()