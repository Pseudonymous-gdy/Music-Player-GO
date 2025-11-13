from Python_Interface.Recommender import Recommender


def _build_song(
    song_id: str,
    *,  # force keyword args for clarity in the test data
    title: str,
    artist: str,
    album: str,
    genre: str,
    tags: str,
    duration: int,
    year: int,
    tempo: int,
):
    return {
        "id": song_id,
        "title": title,
        "artist": artist,
        "album": album,
        "genre": genre,
        "tags": tags,
        "duration": duration,
        "year": year,
        "tempo": tempo,
    }


def test_random_policy_uses_year_based_fallback_when_no_history():
    playlist = [
        _build_song(
            "newest",
            title="Newest Song",
            artist="Artist A",
            album="Fresh Cuts",
            genre="Indie",
            tags="fresh, indie",
            duration=210_000,
            year=2023,
            tempo=95,
        ),
        _build_song(
            "middle",
            title="Middle Song",
            artist="Artist B",
            album="Steady Waves",
            genre="Pop",
            tags="pop, upbeat",
            duration=205_000,
            year=2021,
            tempo=110,
        ),
        _build_song(
            "older",
            title="Old Song",
            artist="Artist C",
            album="Vintage",
            genre="Rock",
            tags="classic, rock",
            duration=198_000,
            year=2018,
            tempo=120,
        ),
    ]

    recommender = Recommender(playlist=playlist)

    results = recommender.selection(policy="random", n=2)

    assert [song["id"] for song in results] == ["newest", "middle"]
    assert all(song["score"] is None for song in results)


def test_random_policy_prefers_songs_similar_to_recent_history():
    seed = _build_song(
        "seed",
        title="Lo-Fi Window",
        artist="Calm Collective",
        album="City Nights",
        genre="Lo-Fi",
        tags="study, chill",
        duration=180_000,
        year=2022,
        tempo=82,
    )
    similar = _build_song(
        "similar",
        title="Lo-Fi Alley",
        artist="Calm Collective",
        album="City Nights",
        genre="Lo-Fi",
        tags="study, chill",
        duration=179_000,
        year=2021,
        tempo=82,
    )
    contrast = _build_song(
        "contrast",
        title="Metal Blast",
        artist="Loud Crew",
        album="Riffs",
        genre="Metal",
        tags="heavy, guitars",
        duration=220_000,
        year=2016,
        tempo=150,
    )

    recommender = Recommender(playlist=[seed, similar, contrast])

    results = recommender.selection(policy="random", n=2, recent_played=["seed"])

    assert results[0]["id"] == "similar"
    assert results[0]["score"] is not None
    assert results[1]["id"] == "contrast"
    assert results[1]["score"] is not None
    assert results[0]["score"] > results[1]["score"]
