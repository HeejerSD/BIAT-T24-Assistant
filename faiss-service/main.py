import json
import os
import re
from typing import Dict, List

import faiss
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import CrossEncoder, SentenceTransformer

DATA_DIR = os.environ.get("FAISS_DATA_DIR", "./faiss_data")
EMBEDDING_MODEL_NAME = os.environ.get(
    "EMBEDDING_MODEL_NAME", "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
)
# Cross-encoder multilingue (FR/EN) : réordonne un petit lot de candidats (question, contenu) avec une précision
# supérieure à la seule similarité cosinus, qui compare des embeddings calculés indépendamment.
RERANKER_MODEL_NAME = os.environ.get("RERANKER_MODEL_NAME", "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1")

app = FastAPI(title="BIAT T24 - FAISS Retriever Service")

# Chargés une seule fois au démarrage (pas par requête) : modèles Hugging Face locaux, gratuits, sans limite.
_embedder = SentenceTransformer(EMBEDDING_MODEL_NAME)
_reranker = CrossEncoder(RERANKER_MODEL_NAME)

_indices: Dict[str, faiss.IndexIDMap] = {}
_dims: Dict[str, int] = {}


class UpsertItem(BaseModel):
    id: int
    vector: List[float]


class UpsertRequest(BaseModel):
    items: List[UpsertItem]


class DeleteRequest(BaseModel):
    ids: List[int]


class SearchRequest(BaseModel):
    vector: List[float]
    k: int = 5


class EmbedRequest(BaseModel):
    texts: List[str]


class EmbedResponse(BaseModel):
    vectors: List[List[float]]


class RerankCandidate(BaseModel):
    id: int
    text: str


class RerankRequest(BaseModel):
    query: str
    candidates: List[RerankCandidate]


def _safe_scope(scope: str) -> str:
    """Un scope = un nom de module T24 (ex: 'AA') ou '__global__'. On évite tout caractère de chemin dangereux."""
    cleaned = re.sub(r"[^A-Za-z0-9_.-]", "_", scope)
    if not cleaned:
        raise HTTPException(400, "Scope invalide")
    return cleaned


def _scope_dir(scope: str) -> str:
    path = os.path.join(DATA_DIR, scope)
    os.makedirs(path, exist_ok=True)
    return path


def _load_scope(scope: str) -> None:
    """Charge l'index depuis le disque s'il existe et n'est pas déjà en mémoire."""
    if scope in _indices:
        return
    path = _scope_dir(scope)
    index_path = os.path.join(path, "index.faiss")
    meta_path = os.path.join(path, "meta.json")
    if os.path.exists(index_path) and os.path.exists(meta_path):
        _indices[scope] = faiss.read_index(index_path)
        with open(meta_path, encoding="utf-8") as f:
            _dims[scope] = json.load(f)["dim"]


def _get_or_create(scope: str, dim: int) -> faiss.IndexIDMap:
    _load_scope(scope)
    if scope not in _indices:
        base = faiss.IndexFlatIP(dim)
        _indices[scope] = faiss.IndexIDMap(base)
        _dims[scope] = dim
    if _dims[scope] != dim:
        raise HTTPException(
            400,
            f"Dimension incohérente pour le scope '{scope}' : index existant={_dims[scope]}, reçu={dim}",
        )
    return _indices[scope]


def _save_scope(scope: str) -> None:
    path = _scope_dir(scope)
    faiss.write_index(_indices[scope], os.path.join(path, "index.faiss"))
    with open(os.path.join(path, "meta.json"), "w", encoding="utf-8") as f:
        json.dump({"dim": _dims[scope]}, f)


@app.post("/indices/{scope}/upsert")
def upsert(scope: str, req: UpsertRequest):
    scope = _safe_scope(scope)
    if not req.items:
        return {"upserted": 0}

    dim = len(req.items[0].vector)
    index = _get_or_create(scope, dim)

    ids = np.array([item.id for item in req.items], dtype=np.int64)
    vectors = np.array([item.vector for item in req.items], dtype=np.float32)
    faiss.normalize_L2(vectors)

    # Un upsert = on retire d'abord les ids déjà présents (s'ils existent) pour éviter les doublons,
    # puis on les rajoute avec leur nouveau vecteur.
    index.remove_ids(ids)
    index.add_with_ids(vectors, ids)

    _save_scope(scope)
    return {"upserted": len(req.items)}


@app.post("/indices/{scope}/delete")
def delete(scope: str, req: DeleteRequest):
    scope = _safe_scope(scope)
    _load_scope(scope)
    if scope not in _indices or not req.ids:
        return {"deleted": 0}

    index = _indices[scope]
    ids = np.array(req.ids, dtype=np.int64)
    removed = index.remove_ids(ids)

    _save_scope(scope)
    return {"deleted": int(removed)}


@app.post("/indices/{scope}/search")
def search(scope: str, req: SearchRequest):
    scope = _safe_scope(scope)
    _load_scope(scope)
    if scope not in _indices or _indices[scope].ntotal == 0:
        return {"hits": []}

    index = _indices[scope]
    if _dims[scope] != len(req.vector):
        raise HTTPException(
            400,
            f"Dimension incohérente pour le scope '{scope}' : index={_dims[scope]}, requête={len(req.vector)}",
        )

    query = np.array([req.vector], dtype=np.float32)
    faiss.normalize_L2(query)
    k = min(req.k, index.ntotal)

    scores, ids = index.search(query, k)
    hits = [
        {"id": int(doc_id), "score": float(score)}
        for doc_id, score in zip(ids[0], scores[0])
        if doc_id != -1
    ]
    return {"hits": hits}


@app.post("/embed")
def embed(req: EmbedRequest):
    if not req.texts:
        return EmbedResponse(vectors=[])
    vectors = _embedder.encode(req.texts, batch_size=32, show_progress_bar=False, convert_to_numpy=True)
    return EmbedResponse(vectors=vectors.tolist())


@app.post("/rerank")
def rerank(req: RerankRequest):
    """Réordonne un petit lot de candidats déjà présélectionnés (ex: par similarité cosinus) selon un score de
    pertinence (question, texte du candidat) calculé jointement par un cross-encoder : plus précis que la seule
    similarité cosinus, mais trop coûteux pour être utilisé sur un index entier, d'où le pipeline "retrieve puis
    rerank" (voir RetrieverServiceImpl côté backend)."""
    if not req.candidates:
        return {"scores": []}
    pairs = [(req.query, c.text) for c in req.candidates]
    raw_scores = _reranker.predict(pairs)
    scored = [{"id": c.id, "score": float(s)} for c, s in zip(req.candidates, raw_scores)]
    scored.sort(key=lambda x: x["score"], reverse=True)
    return {"scores": scored}


@app.get("/health")
def health():
    return {
        "status": "UP",
        "embeddingModel": EMBEDDING_MODEL_NAME,
        "rerankerModel": RERANKER_MODEL_NAME,
        "scopes": {s: int(idx.ntotal) for s, idx in _indices.items()},
    }
