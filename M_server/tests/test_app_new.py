from __future__ import annotations

import io
import json
import pathlib
import sys

import numpy as np
import pytest
from fastapi.testclient import TestClient

# --------------------------------------------------------------------
# 让 Python 找到 src/server/app_new.py
# --------------------------------------------------------------------
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

import server.app_new as app_mod  # noqa: E402
from server.app_new import app  # noqa: E402

client = TestClient(app)


# --------------------------------------------------------------------
# 通用 fixture：重定向 storage、打补丁假特征提取
# --------------------------------------------------------------------
@pytest.fixture(autouse=True)
def _patch_storage_and_features(tmp_path, monkeypatch):
    """
    - 把 STORAGE_DIR / AUDIO_DIR / FEATURE_DIR / SONGS_FILE / FEEDBACK_LOG
      都重定向到一个临时目录
    - 把 make_fixed_vector/save_npz/load_npz monkeypatch 成简单版，
      避免依赖真实音频 / librosa
    """
    storage = tmp_path / "storage"
    audio = storage / "audio"
    features = storage / "features"
    storage.mkdir()
    audio.mkdir()
    features.mkdir()

    # 覆盖 app_new 里用到的全局路径
    monkeypatch.setattr(app_mod, "STORAGE_DIR", storage, raising=False)
    monkeypatch.setattr(app_mod, "AUDIO_DIR", audio, raising=False)
    monkeypatch.setattr(app_mod, "FEATURE_DIR", features, raising=False)
    monkeypatch.setattr(app_mod, "SONGS_FILE", storage / "songs.json", raising=False)
    monkeypatch.setattr(app_mod, "FEEDBACK_LOG", storage / "feedback_log.json", raising=False)
    monkeypatch.setattr(app_mod, "PARAM_PATH", storage / "recommender_params.npz", raising=False)
    monkeypatch.setattr(app_mod, "PRETRAINED_RNN_PATH", storage / "rnn_pretrained.npz", raising=False)

    # 假的特征提取函数：返回一个固定长度的小向量即可
    def fake_make_fixed_vector(path, feature="logmel", n_mels=128, pool="meanstd"):
        x = np.ones(8, dtype=np.float32)
        meta = {"fake": True, "path": str(path)}
        return x, meta

    def fake_save_npz(path, x, meta):
        np.savez(path, x=x, meta=json.dumps(meta))

    def fake_load_npz(path):
        d = np.load(path, allow_pickle=True)
        return d["x"], json.loads(str(d["meta"]))

    monkeypatch.setattr(app_mod, "make_fixed_vector", fake_make_fixed_vector, raising=False)
    monkeypatch.setattr(app_mod, "save_npz", fake_save_npz, raising=False)
    monkeypatch.setattr(app_mod, "load_npz", fake_load_npz, raising=False)

    yield  # 测试执行
    # tmp_path 会自动清理，无需手动删除


# --------------------------------------------------------------------
# 具体测试用例
# --------------------------------------------------------------------

def test_ping():
    resp = client.get("/ping")
    assert resp.status_code == 200
    data = resp.json()
    assert data["msg"] == "pong"
    assert "ts" in data


def _upload_one_song(user_id: str = "test_user", name: str = "testsong") -> None:
    """
    工具函数：上传一首伪音频
    """
    file_content = b"fake-audio-data"
    files = {"file": (f"{name}.wav", io.BytesIO(file_content), "audio/wav")}
    data = {"user_id": user_id}
    r = client.post("/api/audio/upload", files=files, data=data)
    assert r.status_code == 200
    js = r.json()
    assert js["song_name"] == name
    assert js["already_exists"] is False


def test_upload_recommend_feedback_flow_linucb():
    """
    正常闭环（默认 LinUCB）：
      upload -> songs -> recommend -> feedback -> history
    """
    user_id = "test_user"

    # 1) 上传
    _upload_one_song(user_id=user_id, name="testsong")

    # 2) 列歌
    r = client.get("/api/songs", params={"user_id": user_id})
    assert r.status_code == 200
    songs = r.json()["songs"]
    assert len(songs) == 1
    assert songs[0]["song_name"] == "testsong"

    # 3) 推荐
    r = client.post(
        "/api/recommend/query",
        json={
            "user_id": user_id,
            "playlist": [],
            "n": 1,
            # 不传 policy -> 默认 LinUCB
        },
    )
    assert r.status_code == 200
    js = r.json()
    recs = js["recommendations"]
    assert len(recs) == 1
    assert recs[0]["song_name"] == "testsong"
    assert isinstance(recs[0]["score"], float)

    # 4) 反馈
    r = client.post(
        "/api/recommend/feedback",
        json={
            "user_id": user_id,
            "song_name": "testsong",
            "reward": 1.0,
        },
    )
    assert r.status_code == 200
    assert r.json()["status"] == "ok"

    # 5) 历史
    r = client.get("/api/recommend/history", params={"user_id": user_id})
    assert r.status_code == 200
    feedback = r.json()["feedback"]
    assert len(feedback) == 1
    assert feedback[0]["song_name"] == "testsong"
    assert feedback[0]["reward"] == 1.0


def test_upload_recommend_feedback_flow_linucb_plus():
    """
    正常闭环（LinUCB+）：
      policy="LinUCB+" 时也能跑通（RNN 用的是假特征 + 可选预训练）
    """
    user_id = "test_user_plus"

    _upload_one_song(user_id=user_id, name="song_plus")

    # 推荐时显式传 policy="LinUCB+"
    r = client.post(
        "/api/recommend/query",
        json={
            "user_id": user_id,
            "playlist": [],
            "n": 1,
            "policy": "LinUCB+",
        },
    )
    assert r.status_code == 200
    js = r.json()
    recs = js["recommendations"]
    assert len(recs) == 1
    assert recs[0]["song_name"] == "song_plus"
    assert isinstance(recs[0]["score"], float)

    # 反馈时也传 policy="LinUCB+"
    r = client.post(
        "/api/recommend/feedback",
        json={
            "user_id": user_id,
            "song_name": "song_plus",
            "reward": 0.5,
            "policy": "LinUCB+",
        },
    )
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_recommend_query_no_candidates_error():
    """
    推荐时如果过滤完候选为空，应返回 400 和错误信息，
    同时触发日志（这里我们只测 HTTP 与 JSON）。
    """
    user_id = "test_user_empty"

    # 先上传一首
    _upload_one_song(user_id=user_id, name="only_song")

    # playlist 把所有歌都放进去，exclude_playlist=True -> 候选为空
    r = client.post(
        "/api/recommend/query",
        json={
            "user_id": user_id,
            "playlist": ["only_song"],
            "exclude_playlist": True,
            "n": 5,
        },
    )
    assert r.status_code == 400
    js = r.json()
    assert "error" in js
    assert "no candidates" in js["error"]


def test_feedback_missing_fields_error():
    """
    反馈缺少必要字段，应返回 400。
    """
    r = client.post("/api/recommend/feedback", json={})
    assert r.status_code == 400
    js = r.json()
    assert js["error"] == "song_name and reward are required"


def test_feedback_invalid_reward_error():
    """
    反馈 reward 不是数字，应返回 400。
    """
    user_id = "test_user_invalid_reward"
    _upload_one_song(user_id=user_id, name="song_ir")

    r = client.post(
        "/api/recommend/feedback",
        json={
            "user_id": user_id,
            "song_name": "song_ir",
            "reward": "not-a-number",
        },
    )
    assert r.status_code == 400
    js = r.json()
    assert js["error"] == "invalid reward"


def test_feedback_song_not_found_error():
    """
    反馈给一个不存在的 song_name，应返回 404。
    """
    user_id = "test_user_notfound"

    # 不上传任何歌，直接反馈
    r = client.post(
        "/api/recommend/feedback",
        json={
            "user_id": user_id,
            "song_name": "no_such_song",
            "reward": 1.0,
        },
    )
    assert r.status_code == 404
    js = r.json()
    assert "song_name no_such_song not found" in js["error"]
