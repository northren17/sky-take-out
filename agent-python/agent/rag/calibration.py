"""Offline threshold calibration for the labelled policy retrieval fixture."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from dotenv import dotenv_values

from agent.rag.knowledge_base import PolicyKnowledgeBase


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CASES = PROJECT_ROOT / "tests" / "fixtures" / "rag_cases.json"


def select_threshold(rows: list[dict[str, Any]]) -> dict[str, Any]:
    positive_scores: list[float | None] = []
    negative_scores: list[float] = []
    negative_rows = [
        row for row in rows if row["expected_action"] in {"reject", "clarify"}
    ]
    for row in rows:
        expected = set(row.get("expected_policy_ids") or [])
        results = row.get("results") or []
        if row["expected_action"] in {"answer", "cautious"}:
            for policy_id in expected:
                matching = [
                    float(result["score"])
                    for result in results
                    if result.get("policy_id") == policy_id
                ]
                positive_scores.append(min(matching) if matching else None)
        negative_scores.extend(
            float(result["score"])
            for result in results
            if result.get("policy_id") not in expected
        )
    if not positive_scores or not negative_rows or not negative_scores:
        raise ValueError("calibration requires both positive and negative cases")
    candidates = sorted(
        {
            round(score, 6)
            for score in [
                *(score for score in positive_scores if score is not None),
                *negative_scores,
            ]
        }
    )
    if not candidates:
        raise ValueError("calibration produced no retrieval scores")
    selected: tuple[tuple[float, int, int, float], dict[str, Any]] | None = None
    for threshold in candidates:
        true_positive = sum(
            score is not None and score <= threshold for score in positive_scores
        )
        false_positive = sum(score <= threshold for score in negative_scores)
        true_negative = len(negative_scores) - false_positive
        false_negative = len(positive_scores) - true_positive
        false_accept_queries = sum(
            any(float(result["score"]) <= threshold for result in row.get("results") or [])
            for row in negative_rows
        )
        balanced_accuracy = (
            true_positive / len(positive_scores)
            + true_negative / len(negative_scores)
        ) / 2
        sensitivity = true_positive / len(positive_scores)
        specificity = true_negative / len(negative_scores)
        metrics = {
            "max_cosine_distance": threshold,
            "true_positive": true_positive,
            "false_negative": false_negative,
            "false_positive": false_positive,
            "true_negative": true_negative,
            "false_accept_queries": false_accept_queries,
            "balanced_accuracy": balanced_accuracy,
            "sensitivity": sensitivity,
            "specificity": specificity,
        }
        rank = (balanced_accuracy, -false_positive, true_positive, -threshold)
        if selected is None or rank > selected[0]:
            selected = (rank, metrics)
    return selected[1]


def run_calibration(
    knowledge_base: PolicyKnowledgeBase, cases: list[dict[str, Any]]
) -> dict[str, Any]:
    rows = []
    for case in cases:
        rows.append(
            {
                **case,
                "results": knowledge_base.calibration_search(case.get("query", ""), 3),
            }
        )
    selection = select_threshold(rows)
    validation_criteria = {
        "minimum_balanced_accuracy": 0.8,
        "minimum_sensitivity": 0.8,
        "maximum_false_accept_queries": 0,
    }
    validated = bool(
        selection["balanced_accuracy"]
        >= validation_criteria["minimum_balanced_accuracy"]
        and selection["sensitivity"]
        >= validation_criteria["minimum_sensitivity"]
        and selection["false_accept_queries"]
        <= validation_criteria["maximum_false_accept_queries"]
    )
    binding = {
        "calibration_schema_version": 1,
        "index_version": knowledge_base.index_version,
        "embedding_config": knowledge_base.embedding_config,
        "rows": rows,
        "selection": selection,
        "validation_criteria": validation_criteria,
    }
    calibration_hash = hashlib.sha256(
        json.dumps(
            binding, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
    ).hexdigest()
    return {
        "validated": validated,
        "calibration_version": (
            f"policy-rag-{calibration_hash[:16]}" if validated else "unvalidated"
        ),
        "validation_criteria": validation_criteria,
        "score_type": "cosine_distance",
        "lower_is_better": True,
        "index_version": knowledge_base.index_version,
        "embedding_config": knowledge_base.embedding_config,
        "case_count": len(cases),
        "selection": selection,
        "rows": rows,
        "limitations": [
            "Small labelled set; this is a retrieval gate, not answer accuracy.",
            "Recalibrate after corpus, chunking, metric, or embedding changes.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Calibrate policy RAG cosine distance")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    api_key = str(dotenv_values(PROJECT_ROOT / ".env").get("DASHSCOPE_API_KEY") or "")
    if not api_key:
        raise RuntimeError("DASHSCOPE_API_KEY is not configured")
    result = run_calibration(PolicyKnowledgeBase(dashscope_api_key=api_key), cases)
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0 if result["validated"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
