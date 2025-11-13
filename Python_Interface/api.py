import logging
import os
from typing import Any, Dict, List, Optional, Union

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .Recommender import Recommender, build_recommender

logger = logging.getLogger("music-player-go.recommender")


class RecommendRequest(BaseModel):
    user_id: Optional[str] = Field(
        default=None, description="Unique identifier for the caller, optional."
    )
    recent_played_list: Optional[List[Union[str, Dict[str, Any]]]] = Field(
        default=None,
        description="Recent songs the user played. Accepts song IDs or song payloads.",
    )
    liked_song_ids: Optional[List[Union[str, Dict[str, Any]]]] = Field(
        default=None,
        description="Songs the user marked as favorite. Same format as recent_played_list.",
    )
    limit: int = Field(default=10, ge=1, le=50, description="Number of songs to return.")
    policy: str = Field(
        default="recommend",
        description="Recommendation policy. Use 'recommend' to enable the content-based model.",
    )


class RecommendResponse(BaseModel):
    user_id: Optional[str]
    count: int
    results: List[Dict[str, Any]]


def _build_recommender() -> Recommender:
    storage_path = os.getenv("RECOMMENDER_STORAGE")
    try:
        return build_recommender(storage=storage_path)
    except Exception as exc:  # pragma: no cover - fail fast during boot
        logger.error("Failed to initialize recommender: %s", exc)
        raise


app = FastAPI(title="Music Player GO Recommender API", version="1.0.0")
recommender = _build_recommender()


@app.get("/health", tags=["meta"])
def health_check() -> Dict[str, str]:
    return {"status": "ok"}


@app.post("/recommend", response_model=RecommendResponse, tags=["recommendation"])
def recommend(request: RecommendRequest) -> RecommendResponse:
    try:
        items = recommender.selection(
            policy=request.policy,
            n=request.limit,
            recent_played=request.recent_played_list,
            liked_song_ids=request.liked_song_ids,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except NotImplementedError as exc:
        raise HTTPException(status_code=501, detail=str(exc)) from exc

    return RecommendResponse(user_id=request.user_id, count=len(items), results=items)
