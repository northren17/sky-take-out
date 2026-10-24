# 智享云膳 · 用户端网页版（响应式点餐平台）

把微信小程序 **v2.0** 的用户端复刻成响应式网页版，优先适配桌面端，同时支持平板与手机。技术栈：**Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router**。

- 请求、字段、业务规则全部以 **小程序 v2.0 的真实源码**为依据（源码来自资料盘里的 `origin/updateV2.0` 分支）；
- **没有任何 mock 数据**，所有页面直接调用真实接口；
- 金额、状态、路由、跳转全部按源码语义实现，源码里的缺陷逐条记录在 `docs/` 与代码注释里。

---

## 1. 快速开始

```bash
pnpm install

# 1) 启动真实后端（Spring Boot，默认 8080）
#    或先用仓库自带的桩服务看效果（演示数据，仅用于自检/演示）：
pnpm mock:backend

# 2) 启动前端
pnpm dev            # http://localhost:5173
```

浏览器打开 `http://localhost:5173`。

### 登录

浏览器登录页当前支持账号密码登录（图形验证码）和账号注册；注册成功后直接获得登录 token。
微信授权仍只在浏览器 URL 带有微信回调 `code` 且运行于相应微信授权流程时使用。登录 token
保存在用户端 Pinia/localStorage 状态中，业务 HTTP 请求由请求拦截器放入小写
`authentication` 请求头。生产环境应使用有效 HTTPS，并避免在 URL、日志或截图中暴露 token。

### 后端地址与代理

`/api` 前缀由 Vite 代理转发到后端并去掉前缀（`vite.config.ts` 里 `server.proxy` 与 `preview.proxy` 同一套配置）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `/api` | 前端请求前缀 |
| `VITE_PROXY_TARGET` | `http://localhost:8080` | 代理目标（环境变量，可覆盖） |
| `VITE_IMAGE_PREFIX` | `/api/common/download?name=` | 图片地址前缀 |
| `VITE_SHOP_NAME` | `智享云膳` | 店铺显示名 |
| `VITE_SHOP_PHONE` | `400-618-4000` | 店铺电话 |
| `VITE_AGENT_PROXY_TARGET` | `http://localhost:8000` | 开发/预览环境的语音 Agent WebSocket 代理目标 |
| `VITE_AGENT_WS_URL` | 当前站点的 `/ws/voice` | 可选的语音 WebSocket 完整地址（生产建议使用同源 HTTPS/WSS） |

> **图片前缀为什么是 `/common/download`（没有 `/user`）？**
> 源码里 `getNewImage()` 拼的是 `${baseUrl}/common/download?name=${image}`，
> 而 `pages/api/api.js` 里另有一个从未被调用的 `/user/common/download`。
> 两者不一致是源码事实，本工程把前缀做成可配置项，默认取源码真正在用的那个。

### 语音助手（只读）

登录后，页面右下角提供语音助手（`src/components/VoiceAssistant.vue`）。前端先通过已登录的 Java 会话申请一次性 ticket，
再以 WebSocket 首帧完成 Agent 鉴权；鉴权和会话初始化成功后，用户按住按钮才会申请麦克风并发送
16 kHz 单声道 PCM。语音会话只开放当前用户的订单、优惠券只读查询，不支持取消订单、退款或其他写操作。

本地开发时，Vite 将 `/ws/voice` 代理到 `VITE_AGENT_PROXY_TARGET`（默认
`http://localhost:8000`）；Agent 可按其 README 在 8000 端口启动。生产环境需由 HTTPS 站点的
Nginx 将 `/ws/voice` 以 WebSocket Upgrade 转发到内部 Agent 服务，并设置匹配的 Origin；
不要把 Agent API Key 或 Java↔Agent 服务凭据配置在前端环境变量中。语音依赖的 ASR、LLM 或 TTS
不可用时，语音助手会报告连接/处理失败，普通点餐和订单页面仍使用原有 Java API。

---

## 2. 常用命令

```bash
pnpm dev               # 开发服务器
pnpm build             # vue-tsc 类型检查 + 生产构建
pnpm preview           # 预览构建产物（同样带 /api 代理）
pnpm typecheck         # 仅类型检查
pnpm lint              # ESLint（--max-warnings 0）
pnpm lint:fix          # 自动修复

pnpm gen:api           # 由 Apifox 导出重新生成 src/types/openapi.d.ts
pnpm check:consistency # 路由/静态资源/事件绑定/API 覆盖度 四项一致性检查
pnpm check:render      # 无头浏览器渲染 + 接口链路自检（自建 preview 与后端桩）
pnpm mock:backend      # 后端桩服务（演示数据，仅自检/演示用）
```

---

## 3. 目录结构

```
├─ scripts/
│  ├─ apifox-to-openapi.mjs   Apifox 导出 -> OpenAPI 3.0（纯格式转换，不新增字段）
│  ├─ mock-backend.mjs        后端桩服务（演示数据，独立于 src，不参与构建）
│  ├─ check-consistency.mjs   一致性检查
│  ├─ check-render.mjs        渲染 + 接口链路自检
│  └─ screenshot.mjs          批量截图（开发期人工核对视觉）
├─ src/
│  ├─ api/
│  │  ├─ request.ts           axios 实例：authentication 头、code 判定、图片前缀
│  │  ├─ index.ts             六个业务域统一出口
│  │  └─ modules/             user / dish / cart / order / address / shop / table
│  ├─ constants/business.ts   运行时业务常量（响应码、订单状态、支付/配送/餐具枚举）
│  ├─ data/region.ts          省市区三级数据（由 element-china-area-data 提取为纯标签）
│  ├─ components/
│  │  └─ VoiceAssistant.vue   语音助手界面、WebSocket 与音频交互
│  ├─ router/index.ts         12 条路由 + 登录守卫
│  ├─ stores/                 user / cart / shop / checkout
│  ├─ types/
│  │  ├─ openapi.d.ts         openapi-typescript 生成（72 接口 / 62 路径，勿手改）
│  │  └─ api.d.ts             响应包装器与类型别名、源码独有接口的类型
│  ├─ utils/                  order.ts（状态文案/超时/标签）、wechat.ts（微信降级）
│  └─ views/                  12 个页面
└─ public/static/             从原小程序 static/ 原样复制的 37 个素材
```

---

## 4. 页面与路由对照

| 小程序路径 | 本工程路由 | 说明 |
|---|---|---|
| `pages/index/index` | `/index` | 点餐首页：配送地址、实时搜索、分类商品网格、规格/套餐详情、购物车侧栏 |
| `pages/order/index` | `/order` | 提交订单：地址、明细、打包/配送费、备注、餐具、送达时间 |
| `pages/pay/index` | `/pay/:orderId` | 支付：15 分钟倒计时、支付方式、超时转取消 |
| `pages/success/index` | `/success/:orderId` | 下单成功 |
| `pages/remark/index` | `/remark` | 订单备注（50 字） |
| `pages/address/address` | `/address` | 地址管理：选中、设为默认、编辑、删除 |
| `pages/addOrEditAddress/...` | `/address/edit` | 新增/编辑地址，含省市区级联 |
| `pages/historyOrder/...` | `/history` | 历史订单：全部/待付款/退款 三个 tab + 分页 |
| `pages/details/index` | `/order/detail/:orderId` | 订单详情：状态、取消/支付/催单/退款/再来一单 |
| `pages/my/my` | `/my` | 个人中心：店铺电话、地址/订单入口、最近订单 |
| `pages/nonet/index` | `/nonet` | 网络无法连接 |
| — | `/login` | 登录（账号密码、注册；微信回调 code 流程按运行环境启用） |

---

## 5. 与源码/文档的差异（都做了明确标注）

### 5.1 有意修正的源码缺陷

这些是源码里客观存在的 bug，若照抄会导致页面不可用，已按语义修正并在代码注释中标明：

| 源码问题 | 处理 |
|---|---|
| 订单页金额单位不一致：累加的是「分」，又 `+6`（等于 6 分），界面全部没有 `/100` | 统一按分计算（配送费 600 分、打包费 100 分/件），展示 `/100`，提交后端的仍是分 |
| `payOrderHandle` 里 `isHandlePy` 置 true 后失败不复位 → 「去支付」永久禁用 | 用 `:loading` 表达提交中，失败后自动恢复 |
| `handlePiker` 条件 `a !== 'A' \|\| a !== 'B'` 恒真 → 文案总被拼上「份」 | 按语义分支处理 |
| 「立即派送」永远识别不了（`arrivalTime` 被写成 `HH:mm`，恒走定时分支） | 用 `deliveryImmediately` 明确区分 |
| 详情页 `statusWord` 通过 `$emit` 当渲染函数 → 状态标题永远为空 | 正常渲染 |
| 详情页未绑定 `@handleCancel` / `@oneMoreOrder` → 两个按钮无反应 | 正常绑定 |
| `$refs.status.$refs.commonPopup` ref 路径错误 → 运行时 TypeError | 改用 `ElMessageBox` / `ElMessage` |
| 地址页 `getLableVal` 用字符串比较而接口返回数字 → 标签永远是「其他」 | 先转字符串再匹配 |
| `msg` 为 `null` 的接口文案缺失、空 catch 吞错 | 请求层统一提示错误（可被调用方 catch 覆盖） |

### 5.2 原样保留的源码行为

- `GET /user/addressBook/list` 在文档里 `data` 是**单个对象**、但源码按数组消费 → 本工程做了形态归一化（两种都能用），并在 `address.ts` 注明；
- 减菜只取规格数组第 0 个（加菜取全部）—— 源码语义，注释标明；
- 套餐加购整体丢弃 `dishFlavor` —— 源码语义；
- 打烊遮罩文案源码写的是「本店已打样」（错别字），本工程改为「本店已打烊」；
- 源码打烊时不拦截下单（只有视觉遮罩），本工程**同时禁用下单入口**（按"功能等价 + 修明显缺陷"的选项）。

### 5.3 源码独有、文档中不存在的接口

小程序实际调用了 41 个接口，其中 **15 条路径在 day01 的 72 个接口文档里完全不存在**（已逐条核对）。这些能力的类型按源码用法定义，并在 `src/types/api.d.ts` 里集中标注：

`/user/dish/category`、`/user/dish/dishPageList/*`、`/user/dish/flavor/*`、`/user/dish/setmealDishList/*`、`/user/common/download`、`/user/shop/phone`、`/user/order/again`、`/user/order/userPage`、`/user/order/addDish`、`/user/order/decreaseDish/*`、`/user/order/cleanShopCart/*`、`/user/order/pay/*`、`/user/order/shopCart/*`、`/user/table/open/*`、`/user/table/tableStatus/*`

其中 `/user/table/*` 与 `/user/order/shopCart/*`（桌台扫码点餐）在 v2.0 的 11 个页面里**没有任何调用点**，本工程只在 `src/api/modules/table.ts` 登记，不实现该流程。

### 5.4 文档自身的缺陷（未做美化）

- 所有 query 参数在源文档中**没有 `type`**，转换脚本按 `example` 保守推断并在 description 标注；
- 17 个接口的响应 `data` 在文档里只有 `{}`，其类型退化为空 object；
- `GET /user/order/historyOrders` 由示例响应反推，`msg`/`userName`/`cancelReason` 等的原文类型是 `null`。

---

## 6. 已验证的内容

| 检查 | 命令 | 结果 |
|---|---|---|
| 类型检查 | `pnpm typecheck` | 通过（exit 0） |
| 代码规范 | `pnpm lint` | 通过（0 error / 0 warning） |
| 生产构建 | `pnpm build` | 通过 |
| 一致性（路由文件、静态资源、事件绑定、API 覆盖度） | `pnpm check:consistency` | 12 路由 / 27 资源 / 16 事件 / 源码 41 个 API 函数全覆盖 |
| 渲染 + 接口链路 | `pnpm check:render` | **12 个路由全部通过**（无 JS 异常、无 console.error，且数据来自后端桩服务） |

### 尚未验证的部分（后端未启动，无法覆盖）

- **真实后端的字段一致性**：桩服务按文档与源码构造响应，真实后端可能有差异（尤其账户/订单等业务字段）；
- **微信授权登录**：需要后端配置 AppID 与 `redirect_uri`，仓库中没有这类配置；
- **微信支付**：`PUT /user/order/payment` 只回传签证参数，网页端无法发起小程序支付，当前实现为支付成功即跳转（与源码一致）；
- **WebSocket 桌台推送**：源码有 `utils/stomp.js` 与 `utils/webscoket.js`，v2.0 页面未使用，本工程未实现。

---

## 7. 参考材料位置

`.reverse-engineering/`（已加入 `.gitignore`，不参与构建）：

- `v2-source/`、`v2-source-utf8/` —— 从 `origin/updateV2.0` 分支导出的**原始小程序源码**（后者为 UTF-8 转码副本）；
- `source-ref/` —— 编译产物的反编译参考（WXML/WXSS 反格式化）；
- `spec/`、`spec-vue3/`、`v2-pages-vue3-ts-spec.md`、`苍穹外卖-接口契约规格.md` —— 逆向出的接口契约与逐页规格；
- `screenshots/` —— 各页面截图。
