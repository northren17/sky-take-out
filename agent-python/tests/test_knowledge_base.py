import json
import tempfile
import unittest
from pathlib import Path

from agent.rag.knowledge_base import MANIFEST_NAME, PolicyKnowledgeBase, _build_documents
from agent.rag.calibration import run_calibration, select_threshold


class FakeBackend:
    def __init__(self):
        self.collections = {}
        self.last_collection = "customer_policies"
        self.add_batches = []
        self.deleted_batches = []
        self.reset_count = 0
        self.fail_add = False
        self.search_results = []

    @property
    def documents(self):
        return self.collections.setdefault(self.last_collection, {})


class FakeVectorStore:
    def __init__(self, backend, collection_name):
        self.backend = backend
        self.collection_name = collection_name
        self.backend.last_collection = collection_name
        self.backend.collections.setdefault(collection_name, {})

    @property
    def documents(self):
        return self.backend.collections.setdefault(self.collection_name, {})

    def delete_collection(self):
        self.backend.reset_count += 1
        self.backend.collections.pop(self.collection_name, None)

    def add_documents(self, documents, ids):
        if self.backend.fail_add:
            raise RuntimeError("embedding failed")
        self.backend.add_batches.append(list(ids))
        self.documents.update(zip(ids, documents))

    def delete(self, ids):
        self.backend.deleted_batches.append(list(ids))
        for item in ids:
            self.documents.pop(item, None)

    def similarity_search_with_score(self, query, k):
        return self.backend.search_results[:k]

    def copy_documents_to(self, target, ids):
        target.documents.update(
            (document_id, self.documents[document_id]) for document_id in ids
        )


def policy(policy_id, content="内容"):
    return {
        "policy_id": policy_id,
        "category": "测试政策",
        "title": f"标题 {policy_id}",
        "content": content,
        "shipping_fee": "平台承担",
    }


class KnowledgeBaseIndexTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.policies_file = root / "policies.json"
        self.chroma_dir = root / "chroma"
        self.backend = FakeBackend()
        self.embedding_config = {
            "provider": "fake",
            "model": "fixed-v1",
            "distance_metric": "cosine",
        }
        self.write([policy("P001"), policy("P002")])

    def write(self, records):
        self.policies_file.write_text(
            json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    def build(self, **overrides):
        values = {
            "policies_file": self.policies_file,
            "persist_directory": self.chroma_dir,
            "embedding": object(),
            "embedding_config": self.embedding_config,
            "vectorstore_factory": lambda **kwargs: FakeVectorStore(
                self.backend, kwargs["collection_name"]
            ),
        }
        values.update(overrides)
        return PolicyKnowledgeBase(**values)

    def test_stable_ids_and_unchanged_startup_skip_all_writes(self):
        first = self.build()
        first_writes = len(self.backend.add_batches)
        first_ids = sorted(self.backend.documents)
        second = self.build()
        self.assertEqual(first_ids, ["policy:P001:whole", "policy:P002:whole"])
        self.assertEqual(first.index_version, second.index_version)
        self.assertEqual(len(self.backend.add_batches), first_writes)

    def test_modified_and_deleted_documents_are_updated_incrementally(self):
        self.build()
        self.write([policy("P001", "修改后的内容")])
        self.build()
        self.assertEqual(self.backend.add_batches[-1], ["policy:P001:whole"])
        self.assertEqual(sorted(self.backend.documents), ["policy:P001:whole"])

    def test_embedding_config_change_resets_collection(self):
        first = self.build()
        old_collection = first._manifest["active_collection_name"]
        reset_count = self.backend.reset_count
        rebuilt = self.build(
            embedding_config={
                "provider": "fake",
                "model": "fixed-v2",
                "distance_metric": "cosine",
            }
        )
        self.assertGreaterEqual(self.backend.reset_count, reset_count + 1)
        self.assertNotEqual(
            rebuilt._manifest["active_collection_name"], old_collection
        )
        self.assertEqual(sorted(self.backend.documents), ["policy:P001:whole", "policy:P002:whole"])

    def test_failed_embedding_rebuild_preserves_published_collection(self):
        first = self.build()
        manifest_path = self.chroma_dir / MANIFEST_NAME
        before_manifest = manifest_path.read_text(encoding="utf-8")
        old_collection = first._manifest["active_collection_name"]
        old_ids = sorted(self.backend.collections[old_collection])
        self.backend.fail_add = True
        with self.assertRaises(RuntimeError):
            self.build(
                embedding_config={
                    "provider": "fake",
                    "model": "fixed-v2",
                    "distance_metric": "cosine",
                }
            )
        self.assertEqual(manifest_path.read_text(encoding="utf-8"), before_manifest)
        self.assertEqual(sorted(self.backend.collections[old_collection]), old_ids)

    def test_failed_update_does_not_publish_manifest(self):
        self.build()
        manifest_path = self.chroma_dir / MANIFEST_NAME
        before = manifest_path.read_text(encoding="utf-8")
        self.write([policy("P001", "会失败的修改"), policy("P002")])
        self.backend.fail_add = True
        with self.assertRaises(RuntimeError):
            self.build()
        self.assertEqual(manifest_path.read_text(encoding="utf-8"), before)

    def test_failed_first_build_leaves_no_partial_unpublished_collection(self):
        self.backend.fail_add = True
        with self.assertRaises(RuntimeError):
            self.build()
        self.assertFalse((self.chroma_dir / MANIFEST_NAME).exists())
        self.assertEqual(self.backend.documents, {})

    def test_distance_threshold_filters_candidates(self):
        knowledge_base = self.build(max_distance=0.45)
        documents = list(self.backend.documents.values())
        self.backend.search_results = [(documents[0], 0.2), (documents[1], 0.8)]
        diagnostics = knowledge_base.search_with_diagnostics("问题", top_k=2)
        self.assertEqual([item["policy_id"] for item in diagnostics["results"]], ["P001"])
        self.assertEqual(diagnostics["score_type"], "cosine_distance")
        self.assertEqual(diagnostics["best_distance"], 0.2)

    def test_empty_query_retains_calibration_binding(self):
        index_version = self.build().index_version
        knowledge_base = self.build(
            max_distance=0.45,
            calibration_version="policy-rag-0123456789abcdef",
            calibrated_index_version=index_version,
        )
        diagnostics = knowledge_base.search_with_diagnostics("   ")
        self.assertEqual(diagnostics["results"], [])
        self.assertEqual(
            diagnostics["calibration_version"], "policy-rag-0123456789abcdef"
        )
        self.assertTrue(diagnostics["calibration_validated"])

    def test_threshold_without_named_calibration_is_not_validated(self):
        index_version = self.build().index_version
        knowledge_base = self.build(
            max_distance=0.45,
            calibration_version="unvalidated",
            calibrated_index_version=index_version,
        )
        diagnostics = knowledge_base.search_with_diagnostics("   ")
        self.assertFalse(diagnostics["calibration_validated"])

    def test_empty_or_invalid_policy_sources_fail_closed(self):
        self.write([])
        with self.assertRaisesRegex(RuntimeError, "为空"):
            self.build()
        self.write([policy("P001"), policy("P001")])
        with self.assertRaisesRegex(RuntimeError, "重复"):
            self.build()

    def test_document_hash_covers_policy_fields_used_as_evidence(self):
        first = _build_documents([policy("P001", "原文")])[0]
        changed = policy("P001", "原文")
        changed["shipping_fee"] = "客户承担"
        second = _build_documents([changed])[0]
        self.assertNotEqual(first.metadata["document_hash"], second.metadata["document_hash"])
        self.assertNotEqual(first.metadata["chunk_hash"], second.metadata["chunk_hash"])
        self.assertIn("运费规则：客户承担", second.page_content)

    def test_source_id_tracks_the_actual_policy_source(self):
        knowledge_base = self.build(max_distance=1.0)
        document = next(iter(self.backend.documents.values()))
        self.assertEqual(document.metadata["source_id"], "policies.json")

    def test_threshold_selection_prefers_fewer_false_accepts(self):
        rows = [
            {"expected_action": "answer", "expected_policy_ids": ["P1"], "results": [{"policy_id": "P1", "score": 0.2}]},
            {"expected_action": "answer", "expected_policy_ids": ["P2"], "results": [{"policy_id": "P2", "score": 0.4}]},
            {"expected_action": "reject", "expected_policy_ids": [], "results": [{"policy_id": "P3", "score": 0.35}]},
            {"expected_action": "reject", "expected_policy_ids": [], "results": [{"policy_id": "P4", "score": 0.8}]},
        ]
        selected = select_threshold(rows)
        self.assertEqual(selected["max_cosine_distance"], 0.2)
        self.assertEqual(selected["false_positive"], 0)

    def test_threshold_calibration_counts_irrelevant_lower_ranked_evidence(self):
        rows = [
            {
                "expected_action": "answer",
                "expected_policy_ids": ["P1"],
                "results": [
                    {"policy_id": "P1", "score": 0.2},
                    {"policy_id": "WRONG", "score": 0.25},
                ],
            },
            {
                "expected_action": "reject",
                "expected_policy_ids": [],
                "results": [{"policy_id": "OTHER", "score": 0.8}],
            },
        ]
        selected = select_threshold(rows)
        self.assertEqual(selected["max_cosine_distance"], 0.2)
        self.assertEqual(selected["false_positive"], 0)

    def test_calibration_does_not_validate_poor_retrieval(self):
        class PoorKnowledgeBase:
            index_version = "index-poor"
            embedding_config = {"model": "fake"}

            def calibration_search(self, query, top_k):
                return [{"policy_id": "WRONG", "score": 0.1}]

        cases = [
            {
                "id": "positive",
                "query": "known",
                "expected_action": "answer",
                "expected_policy_ids": ["RIGHT"],
            },
            {
                "id": "negative",
                "query": "unknown",
                "expected_action": "reject",
                "expected_policy_ids": [],
            },
        ]
        result = run_calibration(PoorKnowledgeBase(), cases)
        self.assertFalse(result["validated"])
        self.assertEqual(result["calibration_version"], "unvalidated")


if __name__ == "__main__":
    unittest.main()
