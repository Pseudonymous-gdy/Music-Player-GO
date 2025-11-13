# FastAPI 接口文档
script 里面是测试，不需要
服务入口：`src/server/app.py`

## 启动方式

```bash
cd python-interface-recommender
python src/server/app.py
```

默认监听 `http://0.0.0.0:6000`

```bash
HOST=127.0.0.1 PORT=6000 RELOAD=0 python src/server/app.py
```

数据存储目录：`storage/`
- **audio/** 上传的原始音频
- **features/** 提取的特征（npz）
- **songs.json** 曲目元数据
- **recommender_params.npz** LinUCB 模型参数
- **feedback_log.json** 反馈日志

---

## API 总览

| Method | Path | Description |
|--------|------|-------------|
| GET | `/ping` | 心跳检测 |
| POST | `/api/audio/upload` | 上传音频并提取特征 |
| GET | `/api/songs` | 查询所有曲目 |
| POST | `/api/recommend/query` | 获取推荐结果 |
| POST | `/api/recommend/feedback` | 提交反馈（更新模型） |
| GET | `/api/recommend/history` | 查询反馈日志 |

---

## 1. `GET /ping`

### Request
无

### Response
```json
{
  "msg": "pong",
  "ts": 1700000000.123
}
```

---

## 2. `POST /api/audio/upload`

上传一首音频，服务端完成特征提取。

### Request
- Content-Type: `multipart/form-data`
- Fields:
  - `file` (**必填**): 音频文件
  - `artist` (*可选*): 歌手名

### Response
```json
{
  "song_id": "原始文件名（可含空格/中文）",
  "name": "展示名称（默认=文件名）",
  "artist": "歌手或 null",
  "feature_path": "storage/features/xxx.npz",
  "meta": {...},
  "original_filename": "上传时的文件名"
}
```

---

## 3. `GET /api/songs`

返回所有已上传曲目的元信息。

### Request
无

### Response
```json
{
  "songs": [
    {
      "id": "song_id",
      "name": "歌曲名",
      "artist": "歌手或 null",
      "original_name": "原始文件名去扩展",
      "original_filename": "原始文件名",
      "file_path": "storage/audio/xxx.mp3",
      "feature_path": "storage/features/xxx.npz",
      "meta": {...},
      "uploaded_at": 1700000000.123
    },
    ...
  ]
}
```

---

## 4. `POST /api/recommend/query`

根据候选列表返回前 N 个推荐，使用 Disjoint LinUCB。

### Request
- Content-Type: `application/json`
- Body:
```json
{
  "playlist": ["当前正在播放或需排除的 song_id"],
  "candidates": ["参与推荐的 song_id"],    // 可选，默认 = 全部歌曲
  "exclude_playlist": true,                 // 可选，默认 true
  "n": 5                                    // 可选，默认 5
}
```

### Response
```json
{
  "recommendations": [
    {
      "id": "song_id",
      "name": "歌曲名",
      "artist": "歌手或 null",
      "score": 1.2345
    },
    ...
  ]
}
```

---

## 5. `POST /api/recommend/feedback`

提交用户对某首歌的反馈（reward），更新 LinUCB。

### Request
- Content-Type: `application/json`
- Body:
```json
{
  "song_id": "被评分的 song_id",
  "reward": 1.0,                            // 典型值: 0, 0.5, 1
  "playlist": ["当时的播放列表"],            // 可选，仅用于记录
  "candidates": ["参与推荐时的候选"]          // 可选，默认=全部
}
```

### Response
```json
{
  "status": "ok"
}
```

---

## 6. `GET /api/recommend/history`

返回历史反馈记录。

### Response
```json
{
  "feedback": [
    {
      "song_id": "xxx",
      "reward": 1.0,
      "ts": 1700000000.123,
      "playlist": [...]
    },
    ...
  ]
}
```

---

## 常见操作示例

### 上传
```bash
curl -F "file=@/path/song.mp3" \
     -F "artist=Artist Name" \
     http://localhost:8000/api/audio/upload
```

### 推荐
```bash
curl -X POST http://localhost:8000/api/recommend/query \
     -H "Content-Type: application/json" \
     -d '{"playlist": ["songA"], "n": 3}'
```

### 反馈
```bash
curl -X POST http://localhost:8000/api/recommend/feedback \
     -H "Content-Type: application/json" \
     -d '{"song_id": "songA", "reward": 1}'
```

---

服务即插即用：上传 → 推荐 → 反馈 → 更新模型。Android 端调用时只需使用对应的 API 即可。*** End Patch

