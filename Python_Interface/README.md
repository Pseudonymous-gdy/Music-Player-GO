Python Interface (models, algorithms in local recommendation)
---

### Key Interface

**`class Recommender(storage, playlist)`**: class for local recommendation
- `storage`: database/file to access for feature learning and update.
- `playlist`: actual music in the playlist

**Attributes**
`selection(self, policy: str='random', n: int=1)`: function for selecting further `n` music
- `policy`: string, ranging from {'random', 'LinUCB', 'LinUCB+'}. It's the policy to be implemented.
- `n`: number of music to recommend.

