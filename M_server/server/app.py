from __future__ import annotations

import json
import os
import re
import time
import uuid
from pathlib import Path
from typing import Dict, List, Tuple, Optional

import numpy as np
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

# -----------------------------------------------------------------------------
# Paths
# -----------------------------------------------------------------------------

PROJECT_ROOT = Path(__file__).resolve().parents[2]  # .../python-interface-recommender
SRC_DIR = PROJECT_ROOT / "src"
STORAGE_DIR = PROJECT_ROOT / "storage"
AUDIO_DIR = STORAGE_DIR / "audio"
FEATURE_DIR = STORAGE_DIR / "features"
PARAM_PATH = STORAGE_DIR / "recommender_params.npz"
SONGS_FILE = STORAGE_DIR / "songs.json"
FEEDBACK_LOG = STORAGE_DIR / "feedback_log.json"

for d in (STORAGE_DIR, AUDIO_DIR, FEATURE_DIR):
    d.mkdir(parents=True, exist_ok=True)

# module import path
import sys  # noqa: E402

if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from python_interface.recommender import MusicItem, Recommender  # noqa: E402
from python_interface.service.file_service.audio_features_fixed import (  # noqa: E402
    make_fixed_vector,
    save_npz,
)
from python_interface.utils import load_npz  # noqa: E402

# -----------------------------------------------------------------------------
# App
# -----------------------------------------------------------------------------

app = FastAPI(title="python-interface-recommender API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# -----------------------------------------------------------------------------
# Utilities
# -----------------------------------------------------------------------------

def load_songs() -> Dict[str, Dict]:
    if SONGS_FILE.exists():
        try:
            return json.loads(SONGS_FILE.read_text("utf-8"))
        except json.JSONDecodeError:
            return {}
    return {}


def save_songs(data: Dict[str, Dict]) -> None:
    SONGS_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def append_feedback_log(entry: Dict) -> None:
    log: List[Dict] = []
    if FEEDBACK_LOG.exists():
        try:
            log = json.loads(FEEDBACK_LOG.read_text("utf-8"))
        except json.JSONDecodeError:
            log = []
    log.append(entry)
    FEEDBACK_LOG.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")


def ensure_unique_song_id(base: str, songs: Dict[str, Dict]) -> str:
    base = base.strip()
    candidate = base or uuid.uuid4().hex[:8]
    if candidate not in songs:
        return candidate
    idx = 2
    while True:
        candidate_id = f"{candidate}-{idx}"
        if candidate_id not in songs:
            return candidate_id
        idx += 1


def extract_and_save_features(audio_path: Path, song_id: str) -> Tuple[str, Dict]:
    x, meta = make_fixed_vector(str(audio_path), feature="logmel", n_mels=128, pool="meanstd")
    feature_path = FEATURE_DIR / f"{song_id}.npz"
    save_npz(str(feature_path), x, meta)
    return str(feature_path), meta


def build_music_item(song_id: str, info: Dict) -> MusicItem:
    features, _ = load_npz(info["feature_path"])
    return MusicItem(id=song_id, features=features, name=info.get("name"), artist=info.get("artist"))


def build_recommender(candidate_ids: List[str]) -> Tuple[Recommender, Dict[str, MusicItem]]:
    songs = load_songs()
    items: Dict[str, MusicItem] = {}
    for sid in candidate_ids:
        info = songs.get(sid)
        if not info:
            continue
        try:
            items[sid] = build_music_item(sid, info)
        except FileNotFoundError:
            continue
    if not items:
        raise ValueError("no candidates with features available")

    rec = Recommender(storage=str(PARAM_PATH), playlist=list(items.values()), initialization=False)
    return rec, items


def linucb_score(rec: Recommender, item: MusicItem) -> float:
    A = rec._A[item.id]
    b = rec._b[item.id]
    theta = np.linalg.solve(A, b)
    A_inv = np.linalg.inv(A)
    x = item.features
    return float(np.dot(theta, x) + rec.alpha * np.sqrt(np.dot(x, A_inv.dot(x))))


# -----------------------------------------------------------------------------
# Routes
# -----------------------------------------------------------------------------

@app.get("/ping")
def ping():
    return {"msg": "pong", "ts": time.time()}


@app.post("/api/audio/upload")
async def upload_audio(file: UploadFile = File(...), artist: Optional[str] = Form(None)):
    filename = file.filename or f"audio-{uuid.uuid4().hex[:8]}.wav"
    filename = Path(filename).name  # strip directory traversal
    original_name = Path(filename).stem.strip()
    ext = (Path(filename).suffix or ".wav")

    songs = load_songs()

    song_id = ensure_unique_song_id(original_name, songs)
    safe_song_id = re.sub(r"[\\/]+", "_", song_id).strip()

    display_title = original_name
    artist_name = (artist or "").strip() or None
    if not artist_name and " - " in original_name:
        artist_part, title_part = original_name.split(" - ", 1)
        artist_name = artist_part.strip() or None
        display_title = title_part.strip() or display_title

    audio_filename = f"{safe_song_id}{ext}"
    audio_path = AUDIO_DIR / audio_filename
    audio_path.parent.mkdir(parents=True, exist_ok=True)
    audio_bytes = await file.read()
    audio_path.write_bytes(audio_bytes)

    feature_path, meta = extract_and_save_features(audio_path, safe_song_id)

    record = {
        "id": song_id,
        "name": display_title,
        "artist": artist_name,
        "safe_id": safe_song_id,
        "original_filename": filename,
        "original_name": original_name,
        "file_path": str(audio_path),
        "feature_path": feature_path,
        "meta": meta,
        "uploaded_at": time.time(),
    }
    songs[song_id] = record
    save_songs(songs)

    return {
        "song_id": song_id,
        "name": display_title,
        "artist": artist_name,
        "feature_path": feature_path,
        "meta": meta,
        "original_filename": filename,
    }


@app.get("/api/songs")
def list_songs():
    return {"songs": list(load_songs().values())}


@app.post("/api/recommend/query")
def recommend_query(body: Dict):
    songs = load_songs()
    playlist_ids: List[str] = body.get("playlist", [])
    candidate_ids: List[str] = body.get("candidates") or list(songs.keys())
    exclude_playlist: bool = bool(body.get("exclude_playlist", True))
    n: int = int(body.get("n", 5))

    if exclude_playlist:
        candidate_ids = [sid for sid in candidate_ids if sid not in playlist_ids]
    if not candidate_ids:
        return JSONResponse({"error": "no candidates provided"}, status_code=400)

    try:
        rec, items = build_recommender(candidate_ids)
    except ValueError as e:
        return JSONResponse({"error": str(e)}, status_code=400)

    top_items = rec.selection(n=n)
    result = []
    for it in top_items:
        info = songs.get(it.id, {})
        result.append(
            {
                "id": it.id,
                "name": info.get("name"),
                "artist": info.get("artist"),
                "score": linucb_score(rec, it),
            }
        )
    return {"recommendations": result}


@app.post("/api/recommend/feedback")
def recommend_feedback(body: Dict):
    song_id = body.get("song_id")
    reward = body.get("reward")
    if song_id is None or reward is None:
        return JSONResponse({"error": "song_id and reward are required"}, status_code=400)

    try:
        reward = float(reward)
    except (TypeError, ValueError):
        return JSONResponse({"error": "invalid reward"}, status_code=400)

    songs = load_songs()
    if song_id not in songs:
        return JSONResponse({"error": f"song_id {song_id} not found"}, status_code=404)

    candidate_ids: List[str] = body.get("candidates") or list(songs.keys())
    if song_id not in candidate_ids:
        candidate_ids.append(song_id)

    try:
        rec, items = build_recommender(candidate_ids)
    except ValueError as e:
        return JSONResponse({"error": str(e)}, status_code=400)

    item = items.get(song_id)
    if not item:
        return JSONResponse({"error": f"unable to load features for {song_id}"}, status_code=400)

    rec.feedback(item, reward)
    rec.save_params()

    append_feedback_log(
        {"song_id": song_id, "reward": reward, "ts": time.time(), "playlist": body.get("playlist")}
    )
    return {"status": "ok"}


@app.get("/api/recommend/history")
def recommend_history():
    if not FEEDBACK_LOG.exists():
        return {"feedback": []}
    try:
        data = json.loads(FEEDBACK_LOG.read_text("utf-8"))
    except json.JSONDecodeError:
        data = []
    return {"feedback": data}


if __name__ == "__main__":
    import uvicorn

    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "6000"))
    reload = os.environ.get("RELOAD", "1") == "1"
    print(f"Starting FastAPI server at http://{host}:{port} (reload={reload})")
    uvicorn.run("server.app:app", host=host, port=port, reload=reload)


