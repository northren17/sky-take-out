"""Structured policy retrieval tool with traceable citations."""

from __future__ import annotations

import hashlib
import logging
from typing import Any

from langchain_core.tools import tool

from agent.config import RAG_CALIBRATION_VERSION, get_settings
from agent.rag.knowledge_base import calibration_is_valid, get_knowledge_base


logger = logging.getLogger("voice_agent.policy_search")


def _citation(result: dict[str, Any]) -> dict[str, Any]:
    chunk_id = str(result.get("chunk_id") or result.get("policy_id") or "")
    citation_key = hashlib.sha256(chunk_id.encode("utf-8")).hexdigest()[:20]
    return {
        "citation_id": f"citation:{citation_key}",
        "policy_id": str(result.get("policy_id") or ""),
        "title": str(result.get("title") or ""),
        "category": str(result.get("category") or ""),
        "snippet": str(result.get("snippet") or "")[:500],
        "source_id": str(result.get("source_id") or ""),
        "chunk_id": chunk_id,
        "score": float(result.get("score") or 0.0),
        "score_type": str(result.get("score_type") or "cosine_distance"),
        "lower_is_better": bool(result.get("lower_is_better", True)),
        "metadata_version": str(result.get("metadata_version") or ""),
        "document_hash": str(result.get("document_hash") or ""),
        "chunk_hash": str(result.get("chunk_hash") or ""),
        "index_version": str(result.get("index_version") or ""),
        "calibration_version": str(
            result.get("calibration_version") or RAG_CALIBRATION_VERSION
        ),
        "citation_role": "retrieved_evidence",
    }


def _format_policy(result: dict[str, Any]) -> str:
    return "\n".join(
        [
            f"【{result.get('category', '')}】{result.get('title', '')}",
            f"政策编号：{result.get('policy_id', '')}",
            f"证据：{result.get('content', '')}",
            f"余弦距离：{float(result.get('score', 0.0)):.4f}（越小越相关）",
        ]
    )


def _artifact(
    *,
    code: str,
    execution_status: str,
    business_status: str,
    message: str,
    diagnostics: dict[str, Any] | None = None,
    citations: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    citations = citations or []
    diagnostics = diagnostics or {}
    return {
        "success": execution_status == "completed",
        "execution_status": execution_status,
        "business_status": business_status,
        "code": code,
        "message": message,
        "citations": citations,
        "data": {
            "citation_count": len(citations),
            "candidate_count": diagnostics.get("candidate_count", 0),
            "best_distance": diagnostics.get("best_distance"),
            "max_distance": diagnostics.get("max_distance"),
            "score_type": diagnostics.get("score_type", "cosine_distance"),
            "index_version": diagnostics.get("index_version", ""),
            "calibration_version": diagnostics.get(
                "calibration_version", RAG_CALIBRATION_VERSION
            ),
            "calibrated_index_version": diagnostics.get(
                "calibrated_index_version", ""
            ),
        },
    }


@tool("policy_search", parse_docstring=True, response_format="content_and_artifact")
def policy_search(query: str = "") -> tuple[str, dict[str, Any]]:
    """检索售后政策，并返回通过阈值验证的政策证据。

    Args:
        query: 用户问题或政策关键词，例如“退货政策”或“保修多久”。

    Returns:
        给模型阅读的政策文本，以及包含真实检索 citation 的结构化结果。
    """

    normalized_query = " ".join((query or "").split())
    if not normalized_query:
        message = "政策检索失败：query 不能为空"
        return message, _artifact(
            code="INVALID_ARGUMENT",
            execution_status="completed",
            business_status="invalid_arguments",
            message=message,
        )

    try:
        settings = get_settings()
        if not calibration_is_valid(
            settings.rag_max_cosine_distance,
            settings.rag_calibration_version,
            settings.rag_calibrated_index_version,
        ):
            message = "政策检索暂未完成相关性校准，请咨询人工客服。"
            return message, _artifact(
                code="RAG_CALIBRATION_REQUIRED",
                execution_status="completed",
                business_status="no_match",
                message=message,
                diagnostics={
                    "max_distance": settings.rag_max_cosine_distance,
                    "calibration_version": settings.rag_calibration_version,
                    "calibrated_index_version": settings.rag_calibrated_index_version,
                },
            )
        knowledge_base = get_knowledge_base(
            dashscope_api_key=settings.dashscope_api_key.get_secret_value(),
            max_distance=settings.rag_max_cosine_distance,
            calibration_version=settings.rag_calibration_version,
            calibrated_index_version=settings.rag_calibrated_index_version,
        )
        diagnostics = knowledge_base.search_with_diagnostics(
            query=normalized_query, top_k=3
        )
        results = diagnostics["results"]
        if not diagnostics.get("calibration_validated", False):
            message = "政策检索暂未完成相关性校准，请咨询人工客服。"
            return message, _artifact(
                code="RAG_CALIBRATION_REQUIRED",
                execution_status="completed",
                business_status="no_match",
                message=message,
                diagnostics=diagnostics,
            )
        if not results:
            message = "没有找到足够相关的政策证据，请补充信息或咨询人工客服。"
            return message, _artifact(
                code=(
                    "POLICY_REJECTED"
                    if diagnostics.get("candidate_count")
                    else "POLICY_NOT_FOUND"
                ),
                execution_status="completed",
                business_status="no_match",
                message=message,
                diagnostics=diagnostics,
            )

        citations = [_citation(result) for result in results]
        message = "\n\n".join(
            f"政策 {index}：\n{_format_policy(result)}"
            for index, result in enumerate(results, start=1)
        )
        return message, _artifact(
            code="POLICY_FOUND",
            execution_status="completed",
            business_status="hit",
            message="已找到通过相关性阈值的政策证据。",
            diagnostics=diagnostics,
            citations=citations,
        )
    except Exception as exc:
        logger.error(
            "政策检索失败：query_length=%d error_type=%s",
            len(normalized_query),
            type(exc).__name__,
        )
        message = "政策检索失败：知识库暂时不可用，请稍后重试或咨询人工客服。"
        return message, _artifact(
            code="POLICY_SEARCH_FAILED",
            execution_status="failed",
            business_status="unknown",
            message=message,
        )


__all__ = ["policy_search"]
