Python Interface (models, algorithms & API)
---

The Python implementation mirrors the on-device recommender used by the Android app
and exposes it through a FastAPI service for local prototyping or backend usage.

## Recommender class

`class Recommender(storage=None, playlist=None)`

- `storage`: optional path to a JSON/CSV catalog. Defaults to `sample_songs.json`.
- `playlist`: optional in-memory playlist (list of dicts or pandas DataFrame).

The constructor builds a feature matrix (`artist`, `album`, `genre`, `tags`, `duration`,
`year`, `tempo`) and stores normalized vectors for cosine-similarity search.

### `selection(policy='recommend', n=1, recent_played=None, liked_song_ids=None)`

- `policy`: `'recommend'` or `'random'` will invoke the content-based recommender.
  (`LinUCB*` policies raise `NotImplementedError` for now.)
- `n`: number of songs to return.
- `recent_played`, `liked_song_ids`: lists of song IDs or song payloads to build the
  user preference vector.

Returns a list of song dictionaries with an optional `score`.

## FastAPI service

Install dependencies:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r Python_Interface/requirements.txt
```

Run the API:

```bash
uvicorn Python_Interface.api:app --reload
```

`POST /recommend`

```json
{
  "user_id": "demo_user",
  "recent_played_list": ["lofi_beat", "hiphop_lofi"],
  "liked_song_ids": ["neo_soul"],
  "limit": 5,
  "policy": "recommend"
}
```

Response:

```json
{
  "user_id": "demo_user",
  "count": 5,
  "results": [
    {"id": "hiphop_lofi", "title": "Fading Lines", "...": "...", "score": 0.83},
    ...
  ]
}
```

`GET /health` returns a simple status payload.
