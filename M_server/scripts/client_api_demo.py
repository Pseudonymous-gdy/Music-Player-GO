"""
Client demo script for interacting with the FastAPI recommender service.

Usage examples:
    python client_api_demo.py upload --folder ../test_data/audio
    python client_api_demo.py recommend --n 5
    python client_api_demo.py feedback --song-id some-id --reward 1
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import List, Optional

import requests


DEFAULT_BASE_URL = "http://127.0.0.1:8000"  # adjust if server runs elsewhere


def upload_folder(folder: Path, base_url: str) -> None:
    audio_files = []
    for ext in ("*.mp3", "*.flac", "*.wav", "*.m4a", "*.ogg"):
        audio_files.extend(folder.glob(ext))
        audio_files.extend(folder.glob(ext.upper()))
    audio_files = sorted(set(audio_files))

    if not audio_files:
        print(f"No audio files found in {folder}")
        return

    for audio in audio_files:
        print(f"Uploading {audio.name} ...", end=" ")
        with audio.open("rb") as f:
            files = {"file": (audio.name, f, "audio/*")}
            resp = requests.post(f"{base_url}/api/audio/upload", files=files)
        if resp.status_code == 200:
            data = resp.json()
            print(f"OK (song_id={data.get('song_id')})")
        else:
            print(f"FAILED ({resp.status_code}: {resp.text})")


def list_songs(base_url: str) -> Optional[List[dict]]:
    resp = requests.get(f"{base_url}/api/songs")
    if resp.status_code != 200:
        print(f"Error fetching songs: {resp.status_code} {resp.text}")
        return None
    data = resp.json()
    songs = data.get("songs", [])
    for song in songs:
        name = song.get("name") or song.get("original_name") or "unknown"
        artist = song.get("artist") or "unknown artist"
        print(f"{song.get('id')}: {name} ({artist})")
    return songs


def request_recommendation(base_url: str, playlist: List[str], n: int) -> List[dict]:
    payload = {"playlist": playlist, "n": n, "exclude_playlist": True}
    resp = requests.post(f"{base_url}/api/recommend/query", json=payload)
    if resp.status_code != 200:
        print(f"Recommendation error: {resp.status_code} {resp.text}")
        return []
    data = resp.json()
    recs = data.get("recommendations", [])
    for idx, rec in enumerate(recs, 1):
        print(f"{idx}. {rec.get('id')} ({rec.get('name')}) score={rec.get('score'):.4f}")
    return recs


def send_feedback(base_url: str, song_id: str, reward: float) -> None:
    payload = {"song_id": song_id, "reward": reward}
    resp = requests.post(f"{base_url}/api/recommend/feedback", json=payload)
    if resp.status_code == 200:
        print("Feedback accepted.")
    else:
        print(f"Feedback failed: {resp.status_code} {resp.text}")


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Client for recommender API")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="API base URL")

    subparsers = parser.add_subparsers(dest="command", required=True)

    upload_parser = subparsers.add_parser("upload", help="Upload audio files in a folder")
    upload_parser.add_argument("--folder", type=Path, required=True, help="Folder containing audio files")

    rec_parser = subparsers.add_parser("recommend", help="Request recommendations")
    rec_parser.add_argument("--playlist", nargs="*", default=[], help="Playlist song_ids")
    rec_parser.add_argument("--n", type=int, default=5, help="Number of recommendations")

    fb_parser = subparsers.add_parser("feedback", help="Send feedback for a song")
    fb_parser.add_argument("--song-id", required=True, help="Song ID to update")
    fb_parser.add_argument("--reward", type=float, choices=[0.0, 0.5, 1.0], required=True, help="Reward value")

    subparsers.add_parser("songs", help="List songs")

    return parser.parse_args(argv)


def main(argv: List[str]) -> None:
    args = parse_args(argv)
    base_url = args.base_url.rstrip("/")

    if args.command == "upload":
        upload_folder(args.folder, base_url)
    elif args.command == "songs":
        list_songs(base_url)
    elif args.command == "recommend":
        request_recommendation(base_url, args.playlist, args.n)
    elif args.command == "feedback":
        send_feedback(base_url, args.song_id, args.reward)
    else:
        raise ValueError(f"Unknown command {args.command}")


if __name__ == "__main__":
    main(sys.argv[1:])

