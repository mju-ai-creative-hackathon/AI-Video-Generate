"""[4] 구도 사례 검색: SQL 필터 → 유사도·점수 순위 → MMR로 중복 제거.

검색 대상은 `indexed` 상태이고 평가 분할이 아니며, 이용 조건을 확인한 데이터셋의 레코드뿐이다.
데이터가 작을 때는 정확 검색으로 충분하므로 파이썬에서 순위를 계산한다. 데이터가 커지면
PostgreSQL에서는 이미지 임베딩 거리로 후보를 먼저 줄이고, 그때 HNSW 인덱스를 추가한다.
"""

from __future__ import annotations

from typing import Any

import numpy as np
from pgvector.sqlalchemy import Vector
from sqlalchemy import Float, literal, select
from sqlalchemy.orm import Session, sessionmaker

from ..config import RagSettings
from ..db.models import IMAGE_EMBEDDING_DIM, DatasetSource, FramingExample
from .framing import FramingQuery, RetrievedExample


def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    return float(a @ b / denom) if denom > 0 else 0.0


def _array(value: Any) -> np.ndarray | None:
    return None if value is None else np.asarray(value, dtype=float)


class FramingRetriever:
    def __init__(self, factory: sessionmaker[Session], settings: RagSettings) -> None:
        self.factory = factory
        self.settings = settings

    def _candidates(self, session: Session, query: FramingQuery) -> list[FramingExample]:
        stmt = (
            select(FramingExample)
            .join(DatasetSource, FramingExample.dataset_id == DatasetSource.id)
            .where(
                FramingExample.status == "indexed",
                FramingExample.split == "search",
                DatasetSource.allowed_use.is_(True),
                FramingExample.shot["size"].as_string() == query.shot_size,
            )
        )
        if query.subject_type:
            stmt = stmt.where(FramingExample.subject["type"].as_string() == query.subject_type)
        dialect = session.get_bind().dialect.name
        if query.image_embedding is not None and dialect == "postgresql":
            distance = FramingExample.image_embedding.op("<=>", return_type=Float())(
                literal(query.image_embedding, Vector(IMAGE_EMBEDDING_DIM))
            )
            stmt = stmt.order_by(distance)
        stmt = stmt.limit(self.settings.candidates)
        return list(session.scalars(stmt))

    def search(self, query: FramingQuery) -> list[RetrievedExample]:
        with self.factory() as session:
            rows = self._candidates(session, query)
            if not rows:
                return []
            scored = [(self._score(row, query), row) for row in rows]
            picked = self._mmr(scored, query)
            return [
                RetrievedExample(example_id=row.id, framing=dict(row.framing or {}), score=score)
                for score, row in picked
            ]

    def _score(self, row: FramingExample, query: FramingQuery) -> float:
        cfg = self.settings
        parts: list[tuple[float, float]] = []
        image = _array(row.image_embedding)
        if query.image_embedding is not None and image is not None:
            parts.append((cfg.weight_image, _cosine(image, np.asarray(query.image_embedding))))
        layout = _array(row.layout_vector)
        if layout is not None:
            parts.append((cfg.weight_layout, _cosine(layout, np.asarray(query.layout_vector))))
        crop_score = 0.5 if row.crop_score is None else float(row.crop_score)
        parts.append((cfg.weight_score, crop_score))
        total_weight = sum(weight for weight, _ in parts)
        score = sum(weight * value for weight, value in parts) / total_weight
        # 비율을 변환한 크롭은 사람 점수가 그대로 유지되지 않으므로 낮춘다.
        return score * (cfg.derived_weight if row.crop_source == "derived" else 1.0)

    def _mmr(
        self, scored: list[tuple[float, FramingExample]], query: FramingQuery
    ) -> list[tuple[float, FramingExample]]:
        cfg = self.settings
        remaining = sorted(scored, key=lambda item: item[0], reverse=True)
        picked: list[tuple[float, FramingExample]] = []
        picked_vectors: list[np.ndarray] = []

        def mmr_value(item: tuple[float, FramingExample]) -> float:
            score, row = item
            vec = _array(row.layout_vector)
            similarity = 0.0
            if vec is not None and picked_vectors:
                similarity = max(_cosine(vec, other) for other in picked_vectors)
            return cfg.mmr_lambda * score - (1 - cfg.mmr_lambda) * similarity

        while remaining and len(picked) < cfg.top_k:
            best = max(remaining, key=mmr_value)
            remaining.remove(best)
            picked.append(best)
            vec = _array(best[1].layout_vector)
            if vec is not None:
                picked_vectors.append(vec)
        return picked
