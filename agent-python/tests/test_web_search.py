"""网络搜索工具的无网络单元测试。

测试通过 ``unittest.mock.patch`` 替换真实智谱客户端，不读取用户 API Key，也
不会产生外部请求或费用。覆盖的契约包括：空查询必须短路、查询空白必须被规范
化、服务端超量响应必须在本地截断，以及异常必须同时返回可读文本并记录日志。
"""

import unittest
from types import SimpleNamespace
from unittest.mock import patch

from agent.tools.web_search import web_search


class _FakeSearchApi:
    """记录调用参数并返回预设响应的轻量 SDK 替身。

    真实调用链是 ``client.web_search.web_search(**kwargs)``。该替身模拟最内层
    对象，使测试既能检查最终响应，也能断言工具传给 SDK 的参数。
    """

    def __init__(self, response):
        """保存预设响应，并把最近一次参数初始化为 ``None``。"""

        self.response = response
        self.arguments = None

    def web_search(self, **kwargs):
        """记录所有关键字参数，然后同步返回预设响应对象。"""

        self.arguments = kwargs
        return self.response


class WebSearchToolTests(unittest.TestCase):
    """验证 ``web_search`` LangChain Tool 对外可见行为。"""

    def test_empty_query_does_not_call_client(self):
        """空白查询应在本地失败，不能创建客户端或产生 API 请求。

        除了验证返回文案，还通过 ``assert_not_called`` 防止未来重构把无效查询
        发送到计费接口。
        """

        with patch("agent.tools.web_search._get_zhipu_client") as get_client:
            result = web_search.invoke({"query": "  \n "})
        self.assertEqual(result, "搜索失败：query 不能为空")
        get_client.assert_not_called()

    def test_formats_results_and_normalizes_query(self):
        """验证查询规范化、SDK 参数、结果格式和本地三条上限。

        Fake 故意返回五条结果，模拟供应商忽略 ``count=3`` 的真实情况。测试同时
        证明前三条被保留、第四条未泄漏给 Agent，并检查 query 和 search_engine。
        """

        # 模拟服务端忽略 count 并返回五条，验证本地二次限制仍然生效。
        response = SimpleNamespace(
            search_result=[
                SimpleNamespace(
                    title=f"示例标题 {index}",
                    link=f"https://example.com/{index}",
                    content=f"示例摘要 {index}",
                )
                for index in range(1, 6)
            ]
        )
        search_api = _FakeSearchApi(response)
        client = SimpleNamespace(web_search=search_api)
        with patch("agent.tools.web_search._get_zhipu_client", return_value=client):
            result = web_search.invoke({"query": " 最新   模型\n信息 "})

        self.assertIn("示例标题 1", result)
        self.assertIn("示例标题 3", result)
        self.assertNotIn("示例标题 4", result)
        self.assertEqual(result.count("结果 "), 3)
        self.assertEqual(search_api.arguments["search_query"], "最新 模型 信息")
        self.assertEqual(search_api.arguments["count"], 3)
        self.assertEqual(search_api.arguments["search_engine"], "search_pro")

    def test_returns_readable_error(self):
        """供应商异常应转换为工具结果，同时产生可诊断的 ERROR 日志。

        ``assertLogs`` 不依赖磁盘 FileHandler，直接捕获指定 Logger，从而让测试
        在任意目录和 CI 环境都能稳定运行。
        """

        with patch(
            "agent.tools.web_search._get_zhipu_client",
            side_effect=RuntimeError("服务不可用"),
        ):
            with self.assertLogs("voice_agent.web_search", level="ERROR") as logs:
                result = web_search.invoke({"query": "测试"})
        self.assertEqual(result, "搜索失败：联网搜索服务暂时不可用。")
        self.assertIn("query_length=2", logs.output[0])
        self.assertNotIn("query=测试", logs.output[0])
        self.assertIn("error_type=RuntimeError", logs.output[0])
        self.assertNotIn("服务不可用", logs.output[0])


if __name__ == "__main__":
    unittest.main()
