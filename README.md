# 智享云膳（苍穹外卖二次开发）

智享云膳是在经典外卖项目 **苍穹外卖** 基础上进行二次开发的全栈餐饮点餐平台，包含 **Java 后端、管理员 Web、顾客点餐 H5、Python 语音智能体** 四个应用，形成"传统业务 + AI 语音交互"的完整闭环。

核心业务：

- 外卖主流程：员工 / 分类 / 菜品 / 套餐 / 购物车 / 订单 / 工作台数据看板 / Excel 报表导出
- 优惠券体系：模板与活动管理、高并发领取、兑换码、下单核销、退款返还、库存对账
- 订座与预点餐：时段容量控制、预约单管理、超时自动处理
- 订单评价：订单评分、菜品级评价、评价图片
- 积分体系：积分账户、流水、规则引擎、订单完成后结算
- 用餐偏好：顾客口味 / 忌口偏好管理
- 语音点餐助手：实时语音对话点餐、菜品推荐、政策问答（RAG）、可管理的长期记忆

> 仓库不包含任何真实密钥、数据库密码、支付证书或用户数据；所有敏感配置均通过环境变量或被忽略的本地配置文件注入。

---

## 项目架构

| 应用 | 目录 | 职责 | 技术 |
|---|---|---|---|
| Java 后端 | `backed/` | 全部业务 API、认证鉴权、订单 / 优惠券 / 订座 / 评价 / 积分 | Spring Boot 3.3.5 + MyBatis + MySQL + Redis + RabbitMQ |
| 管理端 | `admin-web/` | 商家后台管理 | Vue 3 + TypeScript + Vite + Element Plus + ECharts |
| 用户端 | `user-web/` | 顾客点餐 H5 与语音助手 UI | Vue 3 + TypeScript + Vite + Element Plus + Pinia |
| 语音智能体 | `agent-python/` | 语音会话后端（无浏览器 UI） | FastAPI + LangGraph + DashScope |

浏览器通过用户端的开发代理（或生产环境 Nginx）的同源 `/ws/voice` 连接 Python Agent；Java 是认证与业务数据的唯一权威，Python Agent 通过 HMAC 签名调用 Java 只读业务接口，**不直接访问业务数据库**。

```
┌──────────┐   /api    ┌─────────────┐   HMAC    ┌──────────────┐
│  浏览器   │──────────▶│ Java 后端    │◀────────▶│ Python Agent │
│ user-web │   /ws ───▶│ Spring Boot │  只读接口  │ FastAPI      │
│ admin-web│           │ MySQL/Redis │           │ ASR/LLM/TTS  │
└──────────┘           │ RabbitMQ    │           │ RAG(Chroma)  │
                       └─────────────┘           └──────────────┘
```

## 技术栈

**后端**

- JDK 21、Spring Boot 3.3.5、Maven 多模块（`sky-common` / `sky-pojo` / `sky-server`）
- MyBatis、MySQL 8、Druid 连接池
- Redis（Lua 脚本、分布式锁）、Caffeine 本地缓存
- RabbitMQ（持久化队列、死信队列、publisher confirm）
- Spring Validation、Lombok、Apache POI（Excel 导出）
- JWT 双端鉴权（管理端 / 用户端独立密钥）、阿里云 OSS、微信支付与退款

**前端**

- Vue 3.5 + TypeScript + Vite 6
- Element Plus、Pinia / Vuex、Vue Router、Axios、ECharts
- ESLint、Vitest、Cypress

**语音智能体**

- Python 3.11、FastAPI、WebSocket、uvicorn
- LangGraph 1.x + LangChain（工具编排）
- DashScope：Fun-ASR 实时流式识别、`qwen-plus` 对话、`qwen3-tts-flash-realtime` 流式合成
- Chroma 向量库（RAG 政策问答）、智谱联网搜索
- Pydantic Settings（`SecretStr` 密钥管理）

**部署**

- Docker / Docker Compose、Nginx（HTTPS / WSS 反向代理）

## 项目亮点

### 1. 优惠券高并发领取：Redis Lua + Outbox + RabbitMQ 最终一致

**场景**：热门券开放领取瞬间高并发，需要防超发、防重复领取，并在 Redis 与 MySQL 不共享事务的前提下保证最终一致。

**方案**：

- MySQL 先创建**唯一业务流水**（幂等锚点），Redis **Lua 脚本原子完成**活动状态校验、库存扣减预占、每人限领校验、幂等判断，避免多命令竞态；
- 领取流水作为**持久化 Outbox**，定时投递器在确认 Redis 预占存在后，才向 RabbitMQ 持久队列发布消息（开启 publisher confirm / return）；
- 消费者按业务流水号**幂等落库**：数据库事务内创建用户券、更新领取流水与兑换码计数；
- 失败按退避策略重试（最多 8 次），超限进入**死信队列（DLQ）**并补偿释放 Redis 预占，Outbox 仍是兜底重试来源；
- 定时**对账任务**比对 Redis 库存 / 领取计数与数据库，存在处理中流水时跳过该活动，防止用不完整的 DB 快照覆盖 Redis；对账修复使用 Redis 锁 + Lua 保证安全。

**订单侧**：下单时服务端重读券 / 购物车 / 规则并用 `BigDecimal` 计算优惠（不信任客户端金额），**条件更新**将用户券从"未使用"锁定到订单防止一券多单；订单优惠写入快照表隔离模板变更；支付成功核销、取消 / 超时释放、全额退款成功后返还优惠券。

### 2. 订单状态机与延迟消息

**场景**：订单超时未支付自动取消、支付成功回调驱动后续流程，需要可靠、可重放的状态流转。

**方案**：

- RabbitMQ 延迟交换机（`sky.order.delay.*`）处理订单超时；优惠券领取队列配置 `x-dead-letter-exchange` 兜底异常消息；
- 所有状态变更使用**数据库条件更新**（`UPDATE ... WHERE status = 期望值`）+ 快照状态检查，天然防并发重复处理；
- 监听器（`OrderListener` / `CouponClaimListener`）幂等消费，配合定时任务（`OrderTask` / 对账任务）双保险。

### 3. 全链路实时语音点餐助手

**场景**：顾客在点餐页直接语音对话完成点餐、查询、推荐，要求低延迟、防跨服务伪造请求。

**方案**：

- **安全链路**：登录后向 Java 申请一次性 ticket，Python 侧校验 ticket 并建立会话；Java ↔ Python 双向使用**不同的 HMAC-SHA-256 密钥**签名，密钥仅存在于服务端环境变量；
- **流式管线**：WebSocket 分片上行音频 → Fun-ASR 实时流式识别 → LangGraph 编排 `qwen-plus`（工具调用：菜品查询、推荐、订单只读查询，均走 HMAC 内部接口）→ `qwen3-tts-flash-realtime` 流式合成下行，识别 / 合成均不等待整段完成；
- **RAG 政策问答**： Chroma 向量库 + 余弦距离阈值过滤（阈值未校准时不对生产用户开放政策问答），可选智谱联网搜索；
- **可控记忆**：会话内短期上下文随 WebSocket 生命周期；长期偏好仅存 Java/MySQL，默认关闭、用户显式要求才写入，支持查看 / 删除 / 一键清空，不落敏感信息。

### 4. JDK 21 虚拟线程升级

`spring.threads.virtual.enabled=true` + `AsyncTaskCustomizer` 虚拟线程，Tomcat 请求处理与 `@Async` 任务切换到虚拟线程，提升 IO 密集型接口（订单查询、报表导出）的吞吐，配套 `VirtualThreadUpgradeTest` 验证。

### 5. 工程化与安全基线

- 三个应用独立依赖 / 测试 / 构建，单仓多端边界清晰（见 `PROJECT_STRUCTURE.md`）；
- 后端 JUnit 测试覆盖优惠券生命周期、语音会话隔离、积分、订座偏好等核心链路；
- 前端 `vue-tsc` 类型检查 + ESLint + OpenAPI 类型生成（`gen:api`）；
- 所有密钥通过 `.env`（已忽略）注入，仓库内 `.env.example` 全部为空占位符。

## Redis 与 RabbitMQ 应用场景

| 中间件 | 场景 | 设计说明 |
|---|---|---|
| Redis | 优惠券库存预占 / 限领 | Lua 脚本原子执行"校验 + 预占 + 幂等"，避免超发 |
| Redis | 规则缓存 | Caffeine 本地缓存 + Redis 二级缓存优惠券规则 |
| Redis | 对账修复锁 | 分布式锁 + Lua，保证对账任务串行化与安全释放 |
| Redis | 数据持久化 | AOF everysec + RDB 快照，降低单机故障影响 |
| RabbitMQ | 优惠券领取异步化 | Outbox → 持久队列 → 幂等消费者，削峰并最终一致 |
| RabbitMQ | 领取异常兜底 | 死信交换机 + DLQ 收集消费失败消息，可人工恢复 |
| RabbitMQ | 订单延迟处理 | 延迟交换机 / 队列驱动订单超时取消 |

## 项目结构

```
sky-take-out/
├── backed/                        # Java 后端（Maven 多模块）
│   ├── sky-common/                #   通用工具、常量、异常、上下文
│   ├── sky-pojo/                  #   Entity / DTO / VO
│   ├── sky-server/                #   Controller / Service / Mapper / 配置
│   │   └── src/main/resources/
│   │       ├── db/                #   数据库迁移脚本（按阶段手工执行）
│   │       ├── lua/               #   优惠券库存 / 对账 Lua 脚本
│   │       └── mapper/            #   MyBatis XML
│   └── deploy/                    #   Compose 部署模板（Nginx/Java/Agent/中间件）
├── admin-web/                     # 管理端（Vue3 + TS + Element Plus）
├── user-web/                      # 用户端 H5（Vue3 + TS + Pinia）+ 语音助手 UI
├── agent-python/                  # 语音智能体（FastAPI + LangGraph）
│   ├── agent/                     #   WebSocket、ASR、Agent、RAG、TTS
│   └── tests/                     #   pytest
└── docs/                          # 设计文档（积分与智能优惠等）
```

## 本地启动

### 环境要求

- JDK 21、Maven 3.9+、Node.js ≥ 20.19（推荐 pnpm）、Python 3.11
- MySQL 8+、Redis、RabbitMQ（可用 Docker 快速起中间件）：

```bash
docker run -d --name sky-mysql -e MYSQL_ROOT_PASSWORD=你的密码 -p 3306:3306 mysql:8
docker run -d --name sky-redis -p 6379:6379 redis:7
docker run -d --name sky-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

### 后端（`backed/`）

1. 准备本地配置：复制 `sky-server/src/main/resources/application.yml` 所需环境变量，或创建 `sky-server/config/application-dev-local.yml`（已被 .gitignore 忽略）填写本地数据库 / Redis / RabbitMQ 连接；
2. 按 `sky-server/src/main/resources/db/` 下脚本顺序初始化数据库（迁移需人工按序执行，见 `backed/COUPON_IMPLEMENTATION.md`）；
3. 启动：

```bash
cd backed
mvn spring-boot:run -pl sky-server
```

### 管理端（`admin-web/`，端口 8888）

```bash
cd admin-web
pnpm install
pnpm serve
```

### 用户端（`user-web/`，端口 5173）

```bash
cd user-web
pnpm install
pnpm dev          # /api 由 Vite 代理转发到 http://localhost:8080
```

### 语音智能体（`agent-python/`，端口 8000）

```bash
cd agent-python
py -3.11 -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
copy .env.example .env    # 填入 DASHSCOPE_API_KEY 等，不要提交 .env
python start_backend.py
```

用户端 Vite 代理会把 `/ws/voice` 转发到 `http://localhost:8000`；需同步在 Java 侧配置与 Python 相同的双向 HMAC 密钥。

### 推荐启动顺序

MySQL / Redis / RabbitMQ → Java 后端 → 用户端 / 管理端 →（可选）Python 语音智能体。

## 服务器部署

单机 Docker Compose 模板见 [`backed/deploy/`](backed/deploy/README.md)，包含用户端 Nginx、Java 后端、可选 Python Agent、MySQL、Redis、RabbitMQ：

```bash
cp backed/deploy/.env.example backed/deploy/.env   # 填写凭证，不要提交
docker compose --env-file backed/deploy/.env \
  -f backed/deploy/docker-compose.yml --profile voice up -d
```

要点：

- 仅发布 80 / 443 端口；`/api/` 反代 Java 并剥离前缀，`/ws/voice` 以 Upgrade 方式反代 Agent；
- 低配服务器（约 2C2G）可用 `docker-compose.demo.yml` 覆盖文件，不启动语音助手并限制内存；
- 数据库迁移不自动执行，部署前需 DBA 按审查顺序手工执行；
- 详细的前置条件、备份恢复与验收清单见 [`backed/deploy/README.md`](backed/deploy/README.md)。

## 安全与敏感信息

- `.gitignore` 已排除：`.env`、`deploy/secrets/`、`deploy/certs/`、`application-dev-local.yml`、日志、依赖与构建产物；
- 仓库中的 `application-dev.yml` 仅导入本地可选配置，不含任何真实连接串或密钥；
- 所有 `.env.example` 均为空占位符；微信支付商户私钥、TLS 证书只在部署机上填写；
- 严禁将模型 Key、数据库密码、HMAC 密钥写入前端变量、源码、提示词或日志。

## 致谢

基于黑马程序员「苍穹外卖」教学项目二次开发，在其基础上新增了优惠券、订座、评价、积分、语音智能体等模块。
