from __future__ import annotations

from pathlib import Path
import sys
import io

import numpy as np
import soundfile as sf
from fastapi.testclient import TestClient

# ------------------------------------------------------------
# 把项目的 src/ 加进 sys.path，这样可以 import server.app
# ------------------------------------------------------------
ROOT_DIR = Path(__file__).resolve().parents[1]  # .../python-interface-recommender
SRC_DIR = ROOT_DIR / "src"

if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from server.app import app  # noqa: E402

client = TestClient(app)


# ------------------------------------------------------------
# 工具：生成一段合法的 WAV 音频（给 /api/audio/upload 用）
# ------------------------------------------------------------
def create_dummy_wav_bytes(duration_sec: float = 0.2, sr: int = 16000) -> io.BytesIO:
    """
    生成一段 440 Hz 的正弦波，写入内存中的 WAV 文件，
    返回 BytesIO，让 FastAPI 当成上传文件来处理。
    """
    t = np.linspace(0, duration_sec, int(sr * duration_sec), endpoint=False)
    y = 0.1 * np.sin(2 * np.pi * 440 * t)

    buf = io.BytesIO()
    sf.write(buf, y, sr, format="WAV")
    buf.seek(0)
    return buf


# ------------------------------------------------------------
# 1. 基础心跳接口
# ------------------------------------------------------------
def test_ping():
    resp = client.get("/ping")
    assert resp.status_code == 200

    data = resp.json()
    assert data.get("msg") == "pong"
    assert "ts" in data
    assert isinstance(data["ts"], (int, float))


# ------------------------------------------------------------
# 2. 初始 songs 接口：至少返回一个 list
# ------------------------------------------------------------
def test_songs_endpoint_initial():
    resp = client.get("/api/songs")
    assert resp.status_code == 200

    data = resp.json()
    assert "songs" in data
    assert isinstance(data["songs"], list)


# ------------------------------------------------------------
# 3. 完整流程：上传 -> songs -> recommend -> feedback -> history
# ------------------------------------------------------------
def test_full_upload_recommend_feedback_cycle():
    # ---------- 3.1 上传一首假的测试歌曲 ----------
    audio_buf = create_dummy_wav_bytes()
    files = {
        "file": ("test_ci.wav", audio_buf, "audio/wav"),
    }
    data = {
        "artist": "CI Test Artist",
    }

    resp_upload = client.post("/api/audio/upload", files=files, data=data)
    assert resp_upload.status_code == 200

    upload_json = resp_upload.json()
    song_id = upload_json.get("song_id")
    assert song_id is not None
    assert upload_json.get("feature_path")  # 特征路径应该存在

    # ---------- 3.2 再次查询 songs，确认新歌在列表里 ----------
    resp_songs = client.get("/api/songs")
    assert resp_songs.status_code == 200

    songs_json = resp_songs.json()
    songs_list = songs_json.get("songs", [])
    # 至少有一首歌，并且包含刚刚上传的那首
    assert any(s.get("id") == song_id for s in songs_list)

    # ---------- 3.3 调用推荐接口 ----------
    rec_body = {
        "playlist": [],           # 当前播放列表为空
        "candidates": [song_id],  # 只用这一首作为候选，方便断言
        "exclude_playlist": True,
        "n": 1,
    }
    resp_rec = client.post("/api/recommend/query", json=rec_body)
    assert resp_rec.status_code == 200

    rec_json = resp_rec.json()
    assert "recommendations" in rec_json
    recs = rec_json["recommendations"]
    assert len(recs) == 1

    rec_item = recs[0]
    assert rec_item.get("id") == song_id
    assert isinstance(rec_item.get("score"), float)

    # ---------- 3.4 查看反馈历史（更新前） ----------
    resp_hist_before = client.get("/api/recommend/history")
    assert resp_hist_before.status_code == 200

    hist_before = resp_hist_before.json().get("feedback", [])
    before_len = len(hist_before)

    # ---------- 3.5 提交 feedback ----------
    fb_body = {
        "song_id": song_id,
        "reward": 1.0,
    }
    resp_fb = client.post("/api/recommend/feedback", json=fb_body)
    assert resp_fb.status_code == 200

    fb_json = resp_fb.json()
    assert fb_json.get("status") == "ok"

    # ---------- 3.6 再次查看反馈历史，长度应该增加 ----------
    resp_hist_after = client.get("/api/recommend/history")
    assert resp_hist_after.status_code == 200

    hist_after = resp_hist_after.json().get("feedback", [])
    assert len(hist_after) >= before_len + 1
    # 并且应该能找到这次 song_id 的记录
    assert any(entry.get("song_id") == song_id for entry in hist_after)
