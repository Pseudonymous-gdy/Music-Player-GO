from typing import Any, Dict, List, Optional

from mobile_recommender import recommend as _recommend


def recommend(
    catalog: List[Dict[str, Any]],
    history: Optional[List[Dict[str, Any]]] = None,
    favorites: Optional[List[Dict[str, Any]]] = None,
    limit: int = 1,
) -> List[str]:
    """
    Proxy entry-point used by PythonRecommenderBridge on Android.
    Delegates to the richer mobile_recommender implementation which
    internally caches the encoded feature matrix for the current catalog.
    """
    return _recommend(
        catalog=catalog,
        history=history,
        favorites=favorites,
        limit=limit,
    )
