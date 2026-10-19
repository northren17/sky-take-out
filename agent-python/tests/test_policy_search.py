import unittest
from types import SimpleNamespace
from unittest.mock import patch

from langchain_core.messages import ToolMessage

from agent.tools.policy_search import policy_search


def invoke(query):
    return policy_search.invoke(
        {
            "type": "tool_call",
            "name": "policy_search",
            "args": {"query": query},
            "id": "policy-call",
        }
    )


class PolicySearchTests(unittest.TestCase):
    def test_real_retrieval_result_becomes_traceable_citation(self):
        result = {
            "policy_id": "RETURN_001",
            "category": "退货政策",
            "title": "七天无理由退货",
            "content": "真实文档内容",
            "snippet": "真实证据摘要",
            "source_id": "data/policies.json",
            "chunk_id": "policy:RETURN_001:whole",
            "document_hash": "abc",
            "metadata_version": "abc",
            "score": 0.21,
            "score_type": "cosine_distance",
            "lower_is_better": True,
            "index_version": "index-1",
        }
        knowledge_base = SimpleNamespace(
            search_with_diagnostics=lambda **kwargs: {
                "results": [result],
                "candidate_count": 3,
                "best_distance": 0.21,
                "max_distance": 0.45,
                "score_type": "cosine_distance",
                "index_version": "index-1",
                "calibration_validated": True,
                "calibration_version": "test-v1",
            }
        )
        settings = SimpleNamespace(
            dashscope_api_key=SimpleNamespace(get_secret_value=lambda: "fake"),
            rag_max_cosine_distance=0.45,
            rag_calibration_version="policy-rag-0123456789abcdef",
            rag_calibrated_index_version="index-1",
        )
        with patch("agent.tools.policy_search.get_settings", return_value=settings), patch(
            "agent.tools.policy_search.get_knowledge_base", return_value=knowledge_base
        ):
            message = invoke("七天退货")
        self.assertIsInstance(message, ToolMessage)
        citation = message.artifact["citations"][0]
        self.assertEqual(citation["policy_id"], "RETURN_001")
        self.assertEqual(citation["snippet"], "真实证据摘要")
        self.assertEqual(citation["score_type"], "cosine_distance")

    def test_rejected_candidates_return_no_citations(self):
        knowledge_base = SimpleNamespace(
            search_with_diagnostics=lambda **kwargs: {
                "results": [],
                "candidate_count": 3,
                "best_distance": 0.77,
                "max_distance": 0.45,
                "score_type": "cosine_distance",
                "index_version": "index-1",
                "calibration_validated": True,
                "calibration_version": "test-v1",
            }
        )
        settings = SimpleNamespace(
            dashscope_api_key=SimpleNamespace(get_secret_value=lambda: "fake"),
            rag_max_cosine_distance=0.45,
            rag_calibration_version="policy-rag-0123456789abcdef",
            rag_calibrated_index_version="index-1",
        )
        with patch("agent.tools.policy_search.get_settings", return_value=settings), patch(
            "agent.tools.policy_search.get_knowledge_base", return_value=knowledge_base
        ):
            message = invoke("天气")
        self.assertEqual(message.artifact["code"], "POLICY_REJECTED")
        self.assertEqual(message.artifact["business_status"], "no_match")
        self.assertEqual(message.artifact["citations"], [])

    def test_empty_query_and_technical_failure_are_distinct(self):
        empty = invoke("   ")
        self.assertEqual(empty.artifact["business_status"], "invalid_arguments")
        self.assertEqual(empty.artifact["execution_status"], "completed")
        with patch("agent.tools.policy_search.get_settings", side_effect=RuntimeError("key=secret")):
            failed = invoke("退货")
        self.assertEqual(failed.artifact["execution_status"], "failed")
        self.assertEqual(failed.artifact["citations"], [])
        self.assertNotIn("secret", failed.content)

    def test_unvalidated_threshold_fails_closed(self):
        settings = SimpleNamespace(
            dashscope_api_key=SimpleNamespace(get_secret_value=lambda: "fake"),
            rag_max_cosine_distance=None,
            rag_calibration_version="unvalidated",
            rag_calibrated_index_version="",
        )
        with patch("agent.tools.policy_search.get_settings", return_value=settings), patch(
            "agent.tools.policy_search.get_knowledge_base"
        ) as get_knowledge_base:
            message = invoke("退货")
        self.assertEqual(message.artifact["code"], "RAG_CALIBRATION_REQUIRED")
        self.assertEqual(message.artifact["citations"], [])
        get_knowledge_base.assert_not_called()

    def test_numeric_threshold_with_unvalidated_version_still_fails_closed(self):
        settings = SimpleNamespace(
            dashscope_api_key=SimpleNamespace(get_secret_value=lambda: "fake"),
            rag_max_cosine_distance=0.45,
            rag_calibration_version="unvalidated",
            rag_calibrated_index_version="index-1",
        )
        with patch("agent.tools.policy_search.get_settings", return_value=settings), patch(
            "agent.tools.policy_search.get_knowledge_base"
        ) as get_knowledge_base:
            message = invoke("退货")
        self.assertEqual(message.artifact["code"], "RAG_CALIBRATION_REQUIRED")
        get_knowledge_base.assert_not_called()

    def test_model_text_cannot_create_a_citation(self):
        message = "根据不存在的政策 FAKE_999 可以退款"
        self.assertNotIn("FAKE_999", [item.get("policy_id") for item in invoke(" ").artifact["citations"]])
        self.assertIn("FAKE_999", message)


if __name__ == "__main__":
    unittest.main()
