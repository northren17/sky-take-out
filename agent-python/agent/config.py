"""VoiceAgent 的集中配置模块。

配置被有意分成两类：

1. 模型名、采样率、音色、服务地址等非敏感运行参数直接保存在本文件中，
   便于代码审查、环境对比和版本追踪。
2. API Key、Workspace ID 等凭据只从项目根目录的 ``.env`` 读取，避免将
   私密信息写入源码。

其他后端模块应通过 :func:`get_settings` 获取私密配置，并从本模块导入普通
常量，不要在各个 Provider 中重复解析环境变量。
"""

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


# 模型名称、采样率等非敏感参数随代码进入版本控制，不放入 .env。
#
# ASR_SAMPLE_RATE 的单位是 Hz。前端 AudioWorklet 必须把麦克风音频重采样成
# 相同的 16 kHz、单声道、有符号 16 位小端 PCM；否则语速和识别准确率会异常。
ASR_MODEL = "fun-asr-realtime"
ASR_SAMPLE_RATE = 16_000

# TTS_SAMPLE_RATE 的单位同样是 Hz。该值要和 Provider 请求的 AudioFormat、
# 前端 PcmPlayer 的播放采样率保持一致。Cherry 是当前产品默认中文音色。
TTS_MODEL = "qwen3-tts-flash-realtime"
TTS_VOICE = "Cherry"
TTS_SAMPLE_RATE = 24_000

# Qwen uses DashScope's OpenAI-compatible endpoint. The same DashScope key is used
# for ASR, chat completions, and TTS. Output length is a per-turn upper bound.
QWEN_MODEL = "qwen-plus"
DASHSCOPE_COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
QWEN_MAX_TOKENS = 8_192

# Phase 1 reliability limits. Keeping these together makes the complete timeout
# and backpressure policy reviewable and easy to tune per deployment.
PROVIDER_CONNECT_TIMEOUT_SECONDS = 10.0
LLM_TURN_TIMEOUT_SECONDS = 45.0
TOOL_TIMEOUT_SECONDS = 15.0
TTS_OPERATION_TIMEOUT_SECONDS = 8.0
TTS_RESPONSE_TIMEOUT_SECONDS = 30.0
PROVIDER_CLOSE_TIMEOUT_SECONDS = 5.0
SEND_QUEUE_MAX_ITEMS = 64
SEND_QUEUE_PUT_TIMEOUT_SECONDS = 2.0
ASR_FINAL_DEDUPE_SECONDS = 1.5

# A distinct operation id is still recorded for requests suppressed by this business
# debounce window. Idempotency replay and debounce therefore remain separate rules.
TICKET_URGE_DEBOUNCE_SECONDS = 60

# Phase 3 RAG contract. This is a cosine distance (lower is better), not an
# answer probability. The calibration version is recorded with the index.
RAG_EMBEDDING_MODEL = "text-embedding-v3"
RAG_DISTANCE_METRIC = "cosine"
RAG_MAX_COSINE_DISTANCE: float | None = None
RAG_CALIBRATION_VERSION = "unvalidated"
RAG_CALIBRATED_INDEX_VERSION = ""
RAG_INDEX_SCHEMA_VERSION = 3


class Settings(BaseSettings):
    """从项目根目录的 ``.env`` 加载私密配置。

    ``SecretStr`` 会在对象打印和验证错误中隐藏真实值，降低密钥意外进入日志
    的风险。真正调用供应商 SDK 时，调用方才通过 ``get_secret_value()`` 取值。

    Attributes:
        dashscope_api_key: Fun-ASR、Qwen Chat 和 Qwen TTS 共用的阿里云百炼 API Key。
        dashscope_workspace_id: 可选的百炼 Workspace ID；为空时使用账号默认空间。
        zhipu_api_key: 智谱网络搜索密钥；仅在 Agent 实际调用搜索工具时使用。
    """

    # env_file_encoding 明确为 UTF-8，避免 Windows 默认编码导致中文或特殊字符
    # 解析错误。extra="ignore" 允许 .env 暂存其他环境变量而不阻止服务启动。
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_ignore_empty=True,
        extra="ignore",
    )

    dashscope_api_key: SecretStr = Field(alias="DASHSCOPE_API_KEY")
    dashscope_workspace_id: str | None = Field(
        default=None,
        alias="DASHSCOPE_WORKSPACE_ID",
    )
    zhipu_api_key: SecretStr | None = Field(default=None, alias="ZHIPU_API_KEY")
    agent_java_internal_url: str = Field(default="", alias="AGENT_JAVA_INTERNAL_URL")
    agent_allowed_origins: str = Field(
        default="http://localhost:5173,http://127.0.0.1:5173",
        alias="AGENT_ALLOWED_ORIGINS",
    )
    agent_python_hmac_secret: SecretStr | None = Field(
        default=None, alias="AGENT_PYTHON_HMAC_SECRET"
    )
    agent_java_hmac_secret: SecretStr | None = Field(
        default=None, alias="AGENT_JAVA_HMAC_SECRET"
    )
    rag_max_cosine_distance: float | None = Field(
        default=RAG_MAX_COSINE_DISTANCE,
        alias="RAG_MAX_COSINE_DISTANCE",
        ge=0,
        le=2,
    )
    rag_calibration_version: str = Field(
        default=RAG_CALIBRATION_VERSION,
        alias="RAG_CALIBRATION_VERSION",
    )
    rag_calibrated_index_version: str = Field(
        default=RAG_CALIBRATED_INDEX_VERSION,
        alias="RAG_CALIBRATED_INDEX_VERSION",
    )


@lru_cache
def get_settings() -> Settings:
    """创建并缓存全局配置对象。

    首次调用时 Pydantic 会读取并验证 ``.env``；缺少必填密钥会在这里抛出
    ValidationError，使问题尽早暴露。后续调用返回同一对象，避免每条
    WebSocket 消息都重新读取磁盘。

    Returns:
        已完成环境变量解析和类型验证的 :class:`Settings` 实例。

    Note:
        进程启动后修改 ``.env`` 不会自动刷新缓存，需要重启后端服务。
    """

    return Settings()
