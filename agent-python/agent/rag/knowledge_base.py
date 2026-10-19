"""Versioned Chroma index and evidence-preserving policy retrieval."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import tempfile
import contextlib
import re
from importlib.metadata import PackageNotFoundError, version
from collections.abc import Callable
from functools import lru_cache
from pathlib import Path
from typing import Any

from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import Chroma
from langchain_core.documents import Document

from agent.config import (
    RAG_CALIBRATION_VERSION,
    RAG_CALIBRATED_INDEX_VERSION,
    RAG_DISTANCE_METRIC,
    RAG_EMBEDDING_MODEL,
    RAG_INDEX_SCHEMA_VERSION,
    RAG_MAX_COSINE_DISTANCE,
)


logger = logging.getLogger("voice_agent.rag")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = PROJECT_ROOT / "data"
POLICIES_FILE = DATA_DIR / "policies.json"
CHROMA_DIR = DATA_DIR / "chroma_db"
COLLECTION_NAME = "customer_policies"
MANIFEST_NAME = f"{COLLECTION_NAME}.manifest.json"
_CALIBRATION_VERSION_RE = re.compile(r"^policy-rag-[a-f0-9]{16}$")

_FIELD_LABELS = {
    "conditions": "适用条件",
    "coverage": "保障范围",
    "exclusions": "不适用",
    "steps": "流程",
    "refund_method": "退款方式",
    "shipping_fee": "运费规则",
    "processing_time": "处理时效",
    "duration": "期限",
    "service_method": "服务方式",
    "types": "类型",
    "application_time": "申请时限",
    "delivery_method": "交付方式",
    "rules": "规则",
    "modification": "修改规则",
    "note": "说明",
    "channels": "获取渠道",
    "refund_rule": "退款规则",
    "tip": "提示",
}


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_json(value: Any) -> str:
    return _sha256_bytes(_canonical_json(value).encode("utf-8"))


def calibration_is_valid(
    max_distance: float | None,
    version_name: str,
    calibrated_index_version: str,
    current_index_version: str | None = None,
) -> bool:
    """A threshold is usable only when it names a completed calibration run."""

    normalized_version = str(version_name or "").strip().lower()
    normalized_index = str(calibrated_index_version or "").strip()
    return (
        max_distance is not None
        and bool(_CALIBRATION_VERSION_RE.fullmatch(normalized_version))
        and bool(normalized_index)
        and (
            current_index_version is None
            or normalized_index == current_index_version
        )
    )


def _load_policies(path: Path = POLICIES_FILE) -> list[dict[str, Any]]:
    try:
        policies = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise RuntimeError(f"缺少政策数据文件：{path.name}") from exc
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"政策数据文件格式错误：{path.name}") from exc
    except OSError as exc:
        raise RuntimeError(f"读取政策数据文件失败：{path.name}") from exc
    if not isinstance(policies, list):
        raise RuntimeError(f"政策数据必须是数组格式：{path.name}")
    return policies


def _policy_text(policy: dict[str, Any]) -> str:
    parts = [
        f"政策编号：{str(policy.get('policy_id') or '').strip()}",
        f"类别：{str(policy.get('category') or '').strip()}",
        f"标题：{str(policy.get('title') or '').strip()}",
        f"内容：{str(policy.get('content') or '').strip()}",
    ]
    for field, label in _FIELD_LABELS.items():
        value = policy.get(field)
        if isinstance(value, list):
            normalized = "；".join(str(item).strip() for item in value if str(item).strip())
        else:
            normalized = str(value or "").strip()
        if normalized:
            parts.append(f"{label}：{normalized}")
    return "\n".join(parts)


def _build_documents(
    policies: list[dict[str, Any]], *, source_id: str = "data/policies.json"
) -> list[Document]:
    documents: list[Document] = []
    seen: set[str] = set()
    for index, policy in enumerate(policies):
        if not isinstance(policy, dict):
            raise RuntimeError(f"政策第 {index + 1} 项必须是对象")
        policy_id = str(policy.get("policy_id") or "").strip()
        content = str(policy.get("content") or "").strip()
        if not policy_id or not content:
            raise RuntimeError(f"政策第 {index + 1} 项缺少 policy_id 或 content")
        if policy_id in seen:
            raise RuntimeError(f"政策编号重复：{policy_id}")
        seen.add(policy_id)
        chunk_id = f"policy:{policy_id}:whole"
        source_record_hash = _sha256_json(policy)
        evidence = _policy_text(policy)
        chunk_hash = _sha256_bytes(evidence.encode("utf-8"))
        documents.append(
            Document(
                id=chunk_id,
                page_content=evidence,
                metadata={
                    "policy_id": policy_id,
                    "category": str(policy.get("category") or "").strip(),
                    "title": str(policy.get("title") or "").strip(),
                    "evidence": evidence,
                    "source_id": source_id,
                    "chunk_id": chunk_id,
                    "document_hash": source_record_hash,
                    "source_record_hash": source_record_hash,
                    "chunk_hash": chunk_hash,
                    "metadata_version": source_record_hash[:16],
                },
            )
        )
    return documents


def _read_manifest(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except (OSError, json.JSONDecodeError):
        logger.warning("忽略无法读取的政策索引 manifest：%s", path)
        return None
    return value if isinstance(value, dict) else None


def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.stem}.",
            suffix=".tmp",
            delete=False,
        ) as file:
            temporary = file.name
            json.dump(manifest, file, ensure_ascii=False, indent=2, sort_keys=True)
            file.write("\n")
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary:
            try:
                Path(temporary).unlink()
            except OSError:
                pass


def _default_vectorstore_factory(
    *, embedding: Any, persist_directory: Path, collection_name: str
) -> Chroma:
    return Chroma(
        collection_name=collection_name,
        embedding_function=embedding,
        persist_directory=str(persist_directory),
        collection_metadata={"hnsw:space": RAG_DISTANCE_METRIC},
    )


class PolicyKnowledgeBase:
    """Policy retrieval with stable chunks, incremental writes, and score gating."""

    def __init__(
        self,
        *,
        dashscope_api_key: str | None = None,
        policies_file: Path = POLICIES_FILE,
        persist_directory: Path = CHROMA_DIR,
        embedding: Any | None = None,
        vectorstore_factory: Callable[..., Any] | None = None,
        max_distance: float | None = RAG_MAX_COSINE_DISTANCE,
        embedding_config: dict[str, Any] | None = None,
        calibration_version: str = RAG_CALIBRATION_VERSION,
        calibrated_index_version: str = RAG_CALIBRATED_INDEX_VERSION,
    ) -> None:
        self._policies_file = Path(policies_file)
        self._persist_directory = Path(persist_directory)
        self._manifest_path = self._persist_directory / MANIFEST_NAME
        self._max_distance = float(max_distance) if max_distance is not None else None
        self._calibration_version = calibration_version
        self._calibrated_index_version = calibrated_index_version
        self._embedding = embedding or DashScopeEmbeddings(
            model=RAG_EMBEDDING_MODEL,
            dashscope_api_key=dashscope_api_key,
        )
        self._factory = vectorstore_factory or _default_vectorstore_factory
        try:
            dashscope_package_version = version("dashscope")
        except PackageNotFoundError:
            dashscope_package_version = "unknown"
        self._embedding_config = embedding_config or {
            "provider": "dashscope",
            "model": RAG_EMBEDDING_MODEL,
            "model_revision": "provider-managed",
            "client_package_version": dashscope_package_version,
            "document_mode": "document",
            "query_mode": "query",
            "distance_metric": RAG_DISTANCE_METRIC,
        }
        current = _read_manifest(self._manifest_path)
        active_collection = (
            str(current.get("active_collection_name") or COLLECTION_NAME)
            if current
            else COLLECTION_NAME
        )
        self._vectorstore = self._new_vectorstore(active_collection)
        self._manifest = self._synchronize_index(current)

    @property
    def index_version(self) -> str:
        return str(self._manifest["index_version"])

    @property
    def embedding_config(self) -> dict[str, Any]:
        return dict(self._embedding_config)

    def _new_vectorstore(self, collection_name: str = COLLECTION_NAME):
        return self._factory(
            embedding=self._embedding,
            persist_directory=self._persist_directory,
            collection_name=collection_name,
        )

    def _current_spec(self, documents: list[Document]) -> dict[str, Any]:
        source_hash = _sha256_bytes(self._policies_file.read_bytes())
        document_hashes = {
            str(document.id): {
                "source_record_hash": str(document.metadata["source_record_hash"]),
                "chunk_hash": str(document.metadata["chunk_hash"]),
            }
            for document in documents
        }
        embedding_hash = _sha256_json(self._embedding_config)
        version_payload = {
            "schema_version": RAG_INDEX_SCHEMA_VERSION,
            "collection_name": COLLECTION_NAME,
            "chunking": "one-policy-one-chunk-v1",
            "source_file_sha256": source_hash,
            "documents": document_hashes,
            "embedding_config_hash": embedding_hash,
        }
        index_version = _sha256_json(version_payload)
        return {
            **version_payload,
            "index_version": index_version,
            "embedding_config": self._embedding_config,
            "calibration": {
                "version": self._calibration_version,
                "index_version": self._calibrated_index_version,
                "max_cosine_distance": self._max_distance,
                "validated": calibration_is_valid(
                    self._max_distance,
                    self._calibration_version,
                    self._calibrated_index_version,
                    index_version,
                ),
            },
        }

    @staticmethod
    def _copy_vectors(source: Any, target: Any, ids: list[str]) -> None:
        """Copy existing vectors into a staging collection without re-embedding."""

        if not ids:
            return
        custom_copy = getattr(source, "copy_documents_to", None)
        if callable(custom_copy):
            custom_copy(target, ids)
            return
        source_collection = getattr(source, "_collection", None)
        target_collection = getattr(target, "_collection", None)
        if source_collection is None or target_collection is None:
            raise RuntimeError("向量存储不支持安全的增量索引复制")
        records = source_collection.get(
            ids=ids,
            include=["embeddings", "documents", "metadatas"],
        )
        copied_ids = list(records.get("ids") or [])
        embeddings = records.get("embeddings")
        if len(copied_ids) != len(ids) or embeddings is None:
            raise RuntimeError("已发布索引缺少待复制的向量文档")
        target_collection.upsert(
            ids=copied_ids,
            embeddings=embeddings,
            documents=records.get("documents"),
            metadatas=records.get("metadatas"),
        )

    def _synchronize_index(
        self, current: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        try:
            source_id = self._policies_file.resolve().relative_to(PROJECT_ROOT).as_posix()
        except ValueError:
            source_id = self._policies_file.name
        documents = _build_documents(
            _load_policies(self._policies_file), source_id=source_id
        )
        if not documents:
            raise RuntimeError("政策数据为空，无法构建知识库")
        target = self._current_spec(documents)
        active_collection = (
            str(current.get("active_collection_name") or COLLECTION_NAME)
            if current
            else COLLECTION_NAME
        )
        target["active_collection_name"] = active_collection
        if current and current.get("index_version") == target["index_version"]:
            if not target["calibration"]["validated"]:
                target["calibration"] = current.get(
                    "calibration", target["calibration"]
                )
            elif current.get("calibration") != target.get("calibration"):
                _write_manifest(self._manifest_path, target)
                current = target
            logger.info("政策索引未变化，跳过向量写入：%s", target["index_version"][:12])
            return current

        current_docs = current.get("documents", {}) if current else {}
        same_embedding = bool(
            current
            and current.get("schema_version") == RAG_INDEX_SCHEMA_VERSION
            and current.get("embedding_config_hash") == target["embedding_config_hash"]
        )
        comparison_docs = current_docs if same_embedding else {}
        rebuilding = bool(current)
        previous_vectorstore = self._vectorstore
        working_vectorstore = self._vectorstore
        unpublished_collection = not current
        if unpublished_collection:
            with contextlib.suppress(Exception):
                working_vectorstore.delete_collection()
            working_vectorstore = self._new_vectorstore(COLLECTION_NAME)
        if rebuilding:
            active_collection = f"{COLLECTION_NAME}-{target['index_version'][:16]}"
            target["active_collection_name"] = active_collection
            working_vectorstore = self._new_vectorstore(active_collection)
            # A prior interrupted attempt may have left an unpublished staging
            # collection. Clear only that candidate; the published collection
            # remains queryable until the new manifest is committed.
            with contextlib.suppress(Exception):
                working_vectorstore.delete_collection()
            working_vectorstore = self._new_vectorstore(active_collection)

        target_docs = target["documents"]
        changed = [
            document
            for document in documents
            if comparison_docs.get(str(document.id)) != target_docs[str(document.id)]
        ]
        removed = sorted(set(current_docs) - set(target_docs))
        try:
            if rebuilding and same_embedding:
                unchanged = sorted(
                    document_id
                    for document_id in target_docs
                    if current_docs.get(document_id) == target_docs[document_id]
                )
                self._copy_vectors(previous_vectorstore, working_vectorstore, unchanged)
            if changed:
                working_vectorstore.add_documents(
                    changed, ids=[str(document.id) for document in changed]
                )
            if removed and not rebuilding:
                working_vectorstore.delete(ids=removed)
            _write_manifest(self._manifest_path, target)
        except Exception as exc:
            if rebuilding or unpublished_collection:
                with contextlib.suppress(Exception):
                    working_vectorstore.delete_collection()
            logger.error(
                "政策索引更新失败，manifest 未发布：error_type=%s",
                type(exc).__name__,
            )
            raise
        self._vectorstore = working_vectorstore
        if rebuilding and current and active_collection != str(
            current.get("active_collection_name") or COLLECTION_NAME
        ):
            try:
                previous_vectorstore.delete_collection()
            except Exception:
                logger.warning("旧政策 collection 清理失败，将在运维清理时重试")
        logger.info(
            "政策索引已同步：changed=%d removed=%d version=%s",
            len(changed),
            len(removed),
            target["index_version"][:12],
        )
        return target

    def search_with_diagnostics(self, query: str, top_k: int = 3) -> dict[str, Any]:
        normalized = " ".join((query or "").split())
        if not normalized:
            return {
                "results": [],
                "candidate_count": 0,
                "best_distance": None,
                "max_distance": self._max_distance,
                "score_type": "cosine_distance",
                "index_version": self.index_version,
                "calibration_validated": calibration_is_valid(
                    self._max_distance,
                    self._calibration_version,
                    self._calibrated_index_version,
                    self.index_version,
                ),
                "calibration_version": self._calibration_version,
                "calibrated_index_version": self._calibrated_index_version,
            }
        raw = self._vectorstore.similarity_search_with_score(
            query=normalized, k=max(1, min(int(top_k), 10))
        )
        formatted: list[dict[str, Any]] = []
        distances: list[float] = []
        for document, score in raw:
            distance = float(score)
            distances.append(distance)
            if self._max_distance is None or distance > self._max_distance:
                continue
            metadata = document.metadata
            formatted.append(
                {
                    "policy_id": metadata.get("policy_id", ""),
                    "category": metadata.get("category", ""),
                    "title": metadata.get("title", ""),
                    "content": document.page_content,
                    "snippet": metadata.get("evidence", ""),
                    "source_id": metadata.get("source_id", ""),
                    "chunk_id": metadata.get("chunk_id", document.id or ""),
                    "document_hash": metadata.get("document_hash", ""),
                    "chunk_hash": metadata.get("chunk_hash", ""),
                    "metadata_version": metadata.get("metadata_version", ""),
                    "score": distance,
                    "score_type": "cosine_distance",
                    "lower_is_better": True,
                    "index_version": self.index_version,
                    "calibration_version": self._calibration_version,
                }
            )
        return {
            "results": formatted,
            "candidate_count": len(raw),
            "best_distance": min(distances) if distances else None,
            "max_distance": self._max_distance,
            "score_type": "cosine_distance",
            "index_version": self.index_version,
            "calibration_validated": calibration_is_valid(
                self._max_distance,
                self._calibration_version,
                self._calibrated_index_version,
                self.index_version,
            ),
            "calibration_version": self._calibration_version,
            "calibrated_index_version": self._calibrated_index_version,
        }

    def search(self, query: str, top_k: int = 3) -> list[dict[str, Any]]:
        return self.search_with_diagnostics(query=query, top_k=top_k)["results"]

    def calibration_search(self, query: str, top_k: int = 3) -> list[dict[str, Any]]:
        """Return raw candidate distances for an offline labelled calibration run."""

        normalized = " ".join((query or "").split())
        if not normalized:
            return []
        raw = self._vectorstore.similarity_search_with_score(
            query=normalized, k=max(1, min(int(top_k), 10))
        )
        return [
            {
                "policy_id": document.metadata.get("policy_id", ""),
                "score": float(score),
                "score_type": "cosine_distance",
            }
            for document, score in raw
        ]


@lru_cache(maxsize=4)
def get_knowledge_base(
    dashscope_api_key: str,
    max_distance: float | None = RAG_MAX_COSINE_DISTANCE,
    calibration_version: str = RAG_CALIBRATION_VERSION,
    calibrated_index_version: str = RAG_CALIBRATED_INDEX_VERSION,
) -> PolicyKnowledgeBase:
    return PolicyKnowledgeBase(
        dashscope_api_key=dashscope_api_key,
        max_distance=max_distance,
        calibration_version=calibration_version,
        calibrated_index_version=calibrated_index_version,
    )
