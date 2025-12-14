from __future__ import annotations

import json
import os
import re
import time
import uuid
import subprocess
from pathlib import Path
from typing import Dict, List, Tuple, Optional

import logging
import numpy as np
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

# -----------------------------------------------------------------------------
# Logger
# -----------------------------------------------------------------------------

logger = logging.getLogger(__name__)

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

# 可选：RNN 预训练参数文件（LinUCB+ 用）
# - 默认用 storage/rnn_pretrained.npz
# - 也可以通过环境变量 RNN_PRETRAINED_PATH 覆盖
PRETRAINED_RNN_PATH = STORAGE_DIR / "rnn_pretrained.npz"

# 之前的 RNN 训练脚本（你现在可以不用，但接口留着无所谓）
TRAIN_SCRIPT = SRC_DIR / "python_interface" / "train" / "training.py"

for d in (STORAGE_DIR, AUDIO_DIR, FEATURE_DIR):
    d.mkdir(parents=True, exist_ok=True)

# -----------------------------------------------------------------------------
# module import path
# -----------------------------------------------------------------------------

import sys  # noqa: E402

if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

# 使用新的 Recommend_new 里的 Recommender 和 MusicItem
from python_interface.Recommend_new import MusicItem, Recommender  # noqa: E402
from python_interface.service.file_service.audio_features_fixed import (  # noqa: E402
    make_fixed_vector,
    save_npz,
)
from python_interface.utils import load_npz  # noqa: E402

# -----------------------------------------------------------------------------
# App
# -----------------------------------------------------------------------------

app = FastAPI(title="python-interface-recommender API (per-user, LinUCB+)", version="2.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# -----------------------------------------------------------------------------
# Per-user storage helpers
# -----------------------------------------------------------------------------

def get_user_paths(user_id: Optional[str] = None) -> Tuple[Path, Path, Path, Path, Path]:
    """
    根据 user_id 返回该用户的 audio_dir, feature_dir, param_path, songs_file, feedback_log。

    - 如果 user_id 为 None，则使用原来的全局路径（兼容旧逻辑）
    - 如果 user_id 不为空，则使用 storage/users/<user_id>/...
    """
    if not user_id:
        # 兼容原有单用户逻辑
        return AUDIO_DIR, FEATURE_DIR, PARAM_PATH, SONGS_FILE, FEEDBACK_LOG

    base = STORAGE_DIR / "users" / user_id
    audio_dir = base / "audio"
    feature_dir = base / "features"
    param_path = base / "recommender_params.npz"
    songs_file = base / "songs.json"
    feedback_log = base / "feedback_log.json"

    for d in (base, audio_dir, feature_dir):
        d.mkdir(parents=True, exist_ok=True)

    return audio_dir, feature_dir, param_path, songs_file, feedback_log


# -----------------------------------------------------------------------------
# Utilities
# -----------------------------------------------------------------------------

def load_songs(user_id: Optional[str] = None) -> Dict[str, Dict]:
    """读取某个用户的 songs.json"""
    _, _, _, songs_file, _ = get_user_paths(user_id)
    if songs_file.exists():
        try:
            return json.loads(songs_file.read_text("utf-8"))
        except json.JSONDecodeError:
            logger.exception("Failed to decode songs_file JSON: %s", songs_file)
            return {}
    return {}


def save_songs(data: Dict[str, Dict], user_id: Optional[str] = None) -> None:
    """保存某个用户的 songs.json"""
    _, _, _, songs_file, _ = get_user_paths(user_id)
    try:
        songs_file.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception:
        logger.exception("Failed to save songs_file JSON: %s", songs_file)
        raise


def append_feedback_log(entry: Dict, user_id: Optional[str] = None) -> None:
    """给某个用户的 feedback_log.json 追加一条记录"""
    _, _, _, _, feedback_log = get_user_paths(user_id)
    log: List[Dict] = []
    if feedback_log.exists():
        try:
            log = json.loads(feedback_log.read_text("utf-8"))
        except json.JSONDecodeError:
            logger.exception("Failed to decode feedback_log JSON: %s", feedback_log)
            log = []
    log.append(entry)
    try:
        feedback_log.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception:
        logger.exception("Failed to save feedback_log JSON: %s", feedback_log)
        raise


def get_song_name_from_filename(filename: str) -> str:
    """从文件名提取音乐名（去掉扩展名）"""
    return Path(filename).stem.strip()


def extract_and_save_features(
    audio_path: Path,
    song_name: str,
    user_id: Optional[str] = None,
) -> Tuple[str, Dict]:
    """提取特征并保存，使用音乐名作为文件名"""
    _, feature_dir, _, _, _ = get_user_paths(user_id)
    logger.info("Extracting features for song '%s' (user_id=%s) from %s",
                song_name, user_id, audio_path)
    x, meta = make_fixed_vector(str(audio_path), feature="logmel", n_mels=128, pool="meanstd")
    # 文件名中不能有路径分隔符，替换为下划线
    safe_name = re.sub(r"[\\/]+", "_", song_name)
    feature_path = feature_dir / f"{safe_name}.npz"
    save_npz(str(feature_path), x, meta)
    logger.debug("Saved features to %s", feature_path)
    return str(feature_path), meta


def build_music_item(song_name: str, info: Dict) -> MusicItem:
    """使用音乐名构建 MusicItem"""
    features, _ = load_npz(info["feature_path"])
    return MusicItem(
        id=song_name,        # 使用音乐名作为ID
        features=features,   # 直接用特征作为 x_{t,a}
        name=song_name,      # 展示名
        artist=None,         # 不解析艺术家
    )


def _load_pretrained_rnn_if_needed(rec: Recommender) -> None:
    """
    如果策略是 LinUCB+，尝试从预训练文件加载 RNN 参数。
    - 优先使用环境变量 RNN_PRETRAINED_PATH
    - 否则使用 PRETRAINED_RNN_PATH (storage/rnn_pretrained.npz)
    """
    if getattr(rec, "policy", None) != "LinUCB+":
        return
    if not hasattr(rec, "rnn_model"):
        logger.warning("Recommender has policy LinUCB+ but no rnn_model attribute")
        return

    env_path = os.environ.get("RNN_PRETRAINED_PATH")
    if env_path:
        pretrained_path = Path(env_path)
    else:
        pretrained_path = PRETRAINED_RNN_PATH

    if not pretrained_path.exists():
        logger.warning("[LinUCB+] pretrained RNN file not found: %s", pretrained_path)
        return

    try:
        rec.rnn_model.load_model_from_pretrained(str(pretrained_path))
        logger.info("[LinUCB+] loaded pretrained RNN from %s", pretrained_path)
    except Exception:
        logger.exception("[LinUCB+] failed to load pretrained RNN from %s", pretrained_path)


def build_recommender(
    candidate_names: List[str],
    user_id: Optional[str] = None,
    policy: str = "LinUCB",  # "LinUCB" 或 "LinUCB+"
) -> Tuple[Recommender, Dict[str, MusicItem]]:
    """
    使用音乐名列表构建推荐器（按用户隔离参数和歌曲）

    对于每个 user_id：
    - 参数文件路径为 storage/users/<user_id>/recommender_params.npz
    """
    _, _, param_path, _, _ = get_user_paths(user_id)
    songs = load_songs(user_id)
    items: Dict[str, MusicItem] = {}

    for song_name in candidate_names:
        info = songs.get(song_name)
        if not info:
            logger.warning("Candidate %s not found in songs for user_id=%s", song_name, user_id)
            continue
        try:
            items[song_name] = build_music_item(song_name, info)
        except FileNotFoundError:
            logger.error("Feature file not found for song '%s' (user_id=%s)", song_name, user_id)
            continue

    if not items:
        logger.error("No candidates with features available for user_id=%s", user_id)
        raise ValueError("no candidates with features available")

    logger.info("Building recommender for user_id=%s, policy=%s, candidates=%d, storage=%s",
                user_id, policy, len(items), param_path)

    # 使用新的 Recommender，storage 指向该用户自己的 npz
    rec = Recommender(
        storage=str(param_path),
        playlist=list(items.values()),
        initialization=False,   # 如果找不到文件，Recommender 内部会自动初始化并保存
        policy=policy,
    )

    # 如果是 LinUCB+，尝试加载预训练 RNN 参数
    _load_pretrained_rnn_if_needed(rec)

    return rec, items


def compute_score(rec: Recommender, item: MusicItem) -> float:
    """
    计算一个 item 的打分：
    - LinUCB:   θ^T x + α * sqrt(x^T A^{-1} x)
    - LinUCB+:  上面那一项 + β_t^T x （β_t 来自 RNN）
    并考虑 discount（避免重复推荐同一首）
    """
    A = rec._A[item.id]
    b = rec._b[item.id]
    theta = np.linalg.solve(A, b)
    A_inv = np.linalg.inv(A)
    x = item.features

    pta = float(np.dot(theta, x) + rec.alpha * np.sqrt(np.dot(x, A_inv.dot(x))))

    # LinUCB+ 多加一项 RNN 评分
    if getattr(rec, "policy", None) == "LinUCB+" and hasattr(rec, "rnn_model"):
        try:
            _, beta_t = rec.rnn_model.forward(x, rec.rnn_model.h_t_1)
            beta_np = beta_t.detach().cpu().numpy()
            pta += float(np.dot(beta_np, x))
        except Exception:
            logger.exception("[LinUCB+] compute_score RNN error for item %s", item.id)

    # 避免重复推荐上一次选中的歌
    if item.id == getattr(rec, "last_selected_id", None):
        pta *= rec.discount

    return pta


# -----------------------------------------------------------------------------
# Routes
# -----------------------------------------------------------------------------

@app.get("/ping")
def ping():
    return {"msg": "pong", "ts": time.time()}


@app.post("/api/audio/upload")
async def upload_audio(
    file: UploadFile = File(...),
    artist: Optional[str] = Form(None),        # 现在没用，可以先忽略
    user_id: Optional[str] = Form(None),       # 前端表单里传 user_id
):
    """
    上传音频 + 提取特征 + 写入该用户的 songs.json
    """
    filename = file.filename or f"audio-{uuid.uuid4().hex[:8]}.wav"
    filename = Path(filename).name  # strip directory traversal
    song_name = get_song_name_from_filename(filename)  # 使用音乐名作为唯一标识

    if not song_name:
        logger.error("Invalid filename for upload: %r (user_id=%s)", filename, user_id)
        return JSONResponse({"error": "invalid filename"}, status_code=400)

    ext = Path(filename).suffix or ".wav"
    songs = load_songs(user_id=user_id)

    # 已存在同名歌曲，直接返回
    if song_name in songs:
        logger.info("Song %s already exists for user_id=%s", song_name, user_id)
        return {
            "song_name": song_name,
            "already_exists": True,
        }

    # 新歌曲，保存文件（按用户划分目录）
    safe_name = re.sub(r"[\\/]+", "_", song_name)
    audio_dir, _, _, _, _ = get_user_paths(user_id)
    audio_filename = f"{safe_name}{ext}"
    audio_path = audio_dir / audio_filename
    audio_path.parent.mkdir(parents=True, exist_ok=True)
    audio_bytes = await file.read()
    audio_path.write_bytes(audio_bytes)
    logger.info("Saved audio file for song '%s' (user_id=%s) to %s",
                song_name, user_id, audio_path)

    # 提取特征（按用户存放）
    try:
        feature_path, meta = extract_and_save_features(audio_path, song_name, user_id=user_id)
    except Exception as e:
        logger.exception("Feature extraction failed for song '%s' (user_id=%s): %s",
                         song_name, user_id, e)
        # 特征提取失败，删掉音频文件
        try:
            audio_path.unlink()
        except OSError:
            logger.warning("Failed to remove audio file after feature error: %s", audio_path)
        return JSONResponse(
            {
                "error": "feature_extraction_failed",
                "song_name": song_name,
                "detail": str(e),
            },
            status_code=400,
        )

    # 保存记录（使用音乐名作为 key）
    record = {
        "song_name": song_name,        # 唯一标识
        "file_path": str(audio_path),
        "feature_path": feature_path,
        "meta": meta,
        "uploaded_at": time.time(),
    }
    songs[song_name] = record
    save_songs(songs, user_id=user_id)

    logger.info("Song '%s' registered for user_id=%s", song_name, user_id)

    return {
        "song_name": song_name,
        "already_exists": False,
    }


@app.get("/api/songs")
def list_songs(user_id: Optional[str] = None):
    """
    列出某个用户的所有歌曲
    GET /api/songs?user_id=xxx
    """
    songs = load_songs(user_id=user_id)
    result = []
    for song_name, info in songs.items():
        result.append({
            "song_name": song_name,
        })
    logger.debug("Listing %d songs for user_id=%s", len(result), user_id)
    return {"songs": result}


@app.post("/api/recommend/query")
def recommend_query(body: Dict):
    """
    获取推荐结果，playlist 和返回都使用音乐名；按 user_id 划分

    body:
    {
        "user_id": "demo",
        "playlist": ["当前或需要排除的 song_name"],
        "candidates": ["候选 song_name"],   # 可选，默认=该用户所有歌
        "exclude_playlist": true,
        "n": 5,
        "policy": "LinUCB" 或 "LinUCB+"     # 可选，默认 "LinUCB"
    }
    """
    user_id = body.get("user_id")
    policy = body.get("policy", "LinUCB")   # ⭐ 选择 LinUCB / LinUCB+
    songs = load_songs(user_id=user_id)
    playlist_names: List[str] = body.get("playlist", [])
    candidate_names: List[str] = body.get("candidates") or list(songs.keys())
    exclude_playlist: bool = bool(body.get("exclude_playlist", True))
    n: int = int(body.get("n", 5))

    logger.info("Recommend query for user_id=%s, policy=%s, playlist_size=%d, candidates_size=%d, n=%d",
                user_id, policy, len(playlist_names), len(candidate_names), n)

    if exclude_playlist:
        candidate_names = [name for name in candidate_names if name not in playlist_names]
    if not candidate_names:
        logger.error("Recommend query has no candidates after filtering (user_id=%s)", user_id)
        return JSONResponse({"error": "no candidates provided"}, status_code=400)

    try:
        rec, items = build_recommender(candidate_names, user_id=user_id, policy=policy)
    except ValueError as e:
        logger.error("Failed to build recommender for user_id=%s: %s", user_id, e)
        return JSONResponse({"error": str(e)}, status_code=400)

    top_items = rec.selection(n=n)
    logger.debug("Recommender selected %d items for user_id=%s", len(top_items), user_id)

    result = []
    for it in top_items:
        result.append(
            {
                "song_name": it.id,
                "score": compute_score(rec, it),
            }
        )
    return {"recommendations": result}


@app.post("/api/recommend/feedback")
def recommend_feedback(body: Dict):
    """
    提交反馈，使用音乐名；按 user_id 划分

    body:
    {
        "user_id": "demo",
        "song_name": "某首歌",
        "reward": 1.0,
        "playlist": [...],     # 可选
        "candidates": [...],   # 可选，默认=该用户所有歌
        "policy": "LinUCB" 或 "LinUCB+"  # 可选，保持和推荐时一致
    }
    """
    user_id = body.get("user_id")
    policy = body.get("policy", "LinUCB")

    song_name = body.get("song_name") or body.get("song_id")
    reward = body.get("reward")

    if song_name is None or reward is None:
        logger.error("Feedback missing song_name or reward (user_id=%s, body=%s)", user_id, body)
        return JSONResponse({"error": "song_name and reward are required"}, status_code=400)

    try:
        reward = float(reward)
    except (TypeError, ValueError):
        logger.error("Invalid reward value in feedback (user_id=%s, song_name=%s, reward=%r)",
                     user_id, song_name, reward)
        return JSONResponse({"error": "invalid reward"}, status_code=400)

    songs = load_songs(user_id=user_id)
    if song_name not in songs:
        logger.error("Feedback song_name not found (user_id=%s, song_name=%s)", user_id, song_name)
        return JSONResponse({"error": f"song_name {song_name} not found"}, status_code=404)

    candidate_names: List[str] = body.get("candidates") or list(songs.keys())
    if song_name not in candidate_names:
        candidate_names.append(song_name)

    try:
        rec, items = build_recommender(candidate_names, user_id=user_id, policy=policy)
    except ValueError as e:
        logger.error("Failed to build recommender for feedback (user_id=%s, song_name=%s): %s",
                     user_id, song_name, e)
        return JSONResponse({"error": str(e)}, status_code=400)

    item = items.get(song_name)
    if not item:
        logger.error("Unable to load features for song '%s' in feedback (user_id=%s)",
                     song_name, user_id)
        return JSONResponse({"error": f"unable to load features for {song_name}"}, status_code=400)

    logger.info("Applying feedback: user_id=%s, song_name=%s, reward=%s, policy=%s",
                user_id, song_name, reward, policy)

    rec.feedback(item, reward)
    rec.save_params()   # ✅ 写到该用户自己的 recommender_params.npz

    append_feedback_log(
        {"song_name": song_name, "reward": reward, "ts": time.time(), "playlist": body.get("playlist")},
        user_id=user_id,
    )
    return {"status": "ok"}


@app.get("/api/recommend/history")
def recommend_history(user_id: Optional[str] = None):
    """
    按 user_id 返回反馈日志
    GET /api/recommend/history?user_id=xxx
    """
    _, _, _, _, feedback_log = get_user_paths(user_id)
    if not feedback_log.exists():
        logger.debug("Feedback history requested but file not found (user_id=%s)", user_id)
        return {"feedback": []}
    try:
        data = json.loads(feedback_log.read_text("utf-8"))
    except json.JSONDecodeError:
        logger.exception("Failed to decode feedback_log JSON when reading history: %s",
                         feedback_log)
        data = []
    return {"feedback": data}


# -----------------------------------------------------------------------------
# RNN 训练接口（你现在可以不用它，只是留个口）
# -----------------------------------------------------------------------------

@app.post("/api/rnn/train")
def rnn_train(body: Dict):
    epochs = body.get("epochs")
    user_id = body.get("user_id")
    extra_args: List[str] = []
    if epochs is not None:
        extra_args += ["--epochs", str(epochs)]
    if user_id is not None:
        extra_args += ["--user_id", str(user_id)]

    if not TRAIN_SCRIPT.exists():
        logger.error("Train script not found: %s", TRAIN_SCRIPT)
        return JSONResponse(
            {"error": f"train script not found: {TRAIN_SCRIPT}"},
            status_code=500,
        )

    logger.info("Launching RNN train script %s with args=%s", TRAIN_SCRIPT, extra_args)

    try:
        result = subprocess.run(
            ["python", str(TRAIN_SCRIPT), *extra_args],
            cwd=str(PROJECT_ROOT),
            capture_output=True,
            text=True,
            check=True,
        )
    except subprocess.CalledProcessError as e:
        logger.error("RNN train script failed: returncode=%s, stdout=%r, stderr=%r",
                     e.returncode, e.stdout, e.stderr)
        return JSONResponse(
            {
                "status": "error",
                "returncode": e.returncode,
                "stdout": e.stdout,
                "stderr": e.stderr,
            },
            status_code=500,
        )

    logger.info("RNN train script finished successfully")
    return {
        "status": "ok",
        "stdout": result.stdout,
        "stderr": result.stderr,
    }


# -----------------------------------------------------------------------------
# 启动入口
# -----------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn

    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "6000"))
    reload = os.environ.get("RELOAD", "1") == "1"
    print(f"Starting FastAPI server at http://{host}:{port} (reload={reload})")
    uvicorn.run("server.app_new:app", host=host, port=port, reload=reload)
