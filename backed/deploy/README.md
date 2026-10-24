# 单机 Compose 部署

该目录提供单服务器 Compose 部署模板：用户端 Nginx、Java 后端、可选 Python Agent、MySQL、Redis 和 RabbitMQ。
Redis 使用单机持久化；不配置 Cluster、Sentinel 或多节点。Compose 不会自动应用数据库迁移。

## 启动前置条件

1. 在 Linux 部署主机安装 Docker Engine 与 Docker Compose v2，并确认磁盘空间、时区、DNS 和外网访问符合业务要求。
2. 准备真实域名和有效 TLS 证书，将证书链放到 `deploy/certs/fullchain.pem`，私钥放到 `deploy/certs/privkey.pem`。证书目录不会提交。
3. 复制 `.env.example` 为 `deploy/.env`，按业务启用情况填写数据库、Redis、RabbitMQ、JWT、HMAC、模型及对象存储/微信支付变量。启用语音助手时，必须为两个通信方向分别生成至少 32 字节随机 HMAC secret，并提供所需模型凭证；不能使用相同 secret。不可把真实凭证写入源码、前端变量、提示词或日志。
4. 微信支付启用时，将商户私钥和平台证书放到 `deploy/secrets/wechat/apiclient_key.pem` 与 `deploy/secrets/wechat/platform_cert.pem`。`deploy/secrets/` 已加入忽略规则。
5. 核对现有建表/迁移脚本与目标 MySQL 数据库的实际版本，并由数据库负责人按已审查顺序手工执行。Compose 不挂载或自动执行 `db/` 下脚本，避免在已有数据库上意外重复或越序迁移。
6. 检查 Agent 的 `data/policies.json` 是否符合苍穹外卖真实业务；当前文件包含通用电商示例政策，未确认前不得把其退货/优惠券规则当作本项目正式政策。RAG 阈值保持未校准时，不应向生产用户开放政策问答。
7. 上线前评估并锁定镜像补丁版本或 digest，完成镜像更新和漏洞修复流程；本模板中的基础镜像版本标签不是不可变引用。

## 启动与检查

### 普通部署（含语音助手）

在项目根目录执行：

```powershell
Copy-Item .\backed\deploy\.env.example .\backed\deploy\.env
# 在 .env 中设置 VITE_ENABLE_VOICE_ASSISTANT=true，并填好语音 HMAC 与模型服务凭证
docker compose --env-file .\backed\deploy\.env -f .\backed\deploy\docker-compose.yml --profile voice config --quiet
docker compose --env-file .\backed\deploy\.env -f .\backed\deploy\docker-compose.yml --profile voice build
docker compose --env-file .\backed\deploy\.env -f .\backed\deploy\docker-compose.yml --profile voice up -d
docker compose --env-file .\backed\deploy\.env -f .\backed\deploy\docker-compose.yml --profile voice ps
```

### 低配演示部署（不含语音助手）

适用于约 2 核 2 GB 的低流量演示服务器。此模式不启动 `voice-agent`，并在用户端构建时隐藏语音助手入口；语音 WebSocket 不可用。无需填写 DashScope、DeepSeek 或语音专用 HMAC 密钥。演示覆盖文件为 Java、MySQL、Redis、RabbitMQ 和 Web 设置了内存上限；Java 堆上限约为 288 MB，适合低并发演示，不代表生产容量。

```powershell
docker compose --env-file .\backed\deploy\.env -f .\backed\deploy\docker-compose.yml -f .\backed\deploy\docker-compose.demo.yml config --quiet
docker compose --env-file .\backed\deploy\.env -f .\backed\deploy\docker-compose.yml -f .\backed\deploy\docker-compose.demo.yml build
docker compose --env-file .\backed\deploy\.env -f .\backed\deploy\docker-compose.yml -f .\backed\deploy\docker-compose.demo.yml up -d
docker compose --env-file .\backed\deploy\.env -f .\backed\deploy\docker-compose.yml -f .\backed\deploy\docker-compose.demo.yml ps
```

只把 Web/Nginx 的 80、443 端口发布到主机；Java、Agent、MySQL、Redis、RabbitMQ 不发布宿主机端口。Nginx 将 `/api/` 转发至 Java 并剥离 `/api` 前缀；含语音助手时，`/ws/voice` 以 Upgrade 方式转发至 Agent，读写超时为 75 秒。浏览器 Origin 必须与 `.env` 中 `DOMAIN` 对应。

## 持久化、备份与恢复

- MySQL、Redis、RabbitMQ、RAG Chroma 数据和 Agent 错误日志分别使用命名卷；删除容器不会删除这些卷。
- Redis 使用 AOF `appendfsync everysec`，并配置每 15 分钟在有写入时生成 RDB 快照。AOF/RDB 只能降低单机故障影响，不能代替异机备份。
- 本模板没有指定云厂商或异机备份目的地，因此**尚未配置每日异机备份**。上线前必须由部署负责人选择备份目标、加密方式、保留期、权限和恢复演练流程；至少验证从异机备份恢复 Redis 与 MySQL 数据。不要将备份凭证放进仓库。
- 备份/恢复演练前不要运行 `docker compose down -v`；该命令会删除数据库、Redis、RabbitMQ 和 Agent 数据卷。

## 运行限制与验收

- 这是单机 Compose 基线，不含自动数据库迁移、自动证书签发、异机备份上传、监控告警或高可用。
- `/api/health` 仅证明 Agent 进程可响应，不代表 ASR、DeepSeek、TTS、Java 数据库或 RAG 已可用。
- DeepSeek、DashScope 凭证必须有效；当前 DeepSeek 凭证过期时真实语音对话不会通过验收。填入新凭证前，本地单测和模拟渲染通过不构成真实模型验收。
- 发布前分别验证：账号密码真实登录、ticket 单次消费与过期、登出关闭现有 WebSocket、订单/优惠券只读及跨用户隔离、Chrome/Edge 真实麦克风语音、Nginx HTTPS/WSS、备份恢复。
- 秘密轮换时重建对应容器并验证双方同步更新。Agent 重启会终止其进程内 WebSocket 会话，客户端应重新申请 ticket 并重新认证。
