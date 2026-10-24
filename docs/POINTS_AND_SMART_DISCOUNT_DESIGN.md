# 积分成长与智能优惠推荐系统设计

> 状态：审计与设计稿；不是实施脚本。本文中的 SQL 是草案，**未执行**。本阶段不修改既有业务代码、数据库、接口或配置。
>
> 审计基线：当前工作区代码。优惠券相关 SQL 位于 `backed/sky-server/src/main/resources/db/migration-coupon-phase*.sql`，并非所有环境都能据此推断已经执行了全部迁移；上线前必须核对目标库实际 schema。

## 1. 项目现状审计

### 1.1 技术与调用约定

| 范围 | 实际实现 | 对本设计的意义 |
|---|---|---|
| 后端 | Spring Boot 3、MyBatis、MySQL、Redis、RabbitMQ；控制器返回 `Result`，分页使用 `PageResult` | 新模块可沿用现有单体服务、Mapper、事务与响应包装，不需要新微服务 |
| 用户身份 | `JwtTokenUserInterceptor` 校验用户 Token 和 Redis session，将用户 ID 写入 `BaseContext`；用户业务服务通过 `BaseContext.getCurrentId()` 取身份 | 用户 API 不接收 userId 作为可信参数；兑换、查询和结算均从上下文取用户 ID |
| 管理端身份 | `WebMvcConfiguration` 对 `/admin/**` 注册 `JwtTokenAdminInterceptor`，除登录外均需管理员 Token；拦截器将员工 ID 写入 `BaseContext` | 管理端新接口复用管理员鉴权并记录操作员工 |
| 用户与个人中心 | 用户实体为 `User`；用户端已有个人中心和“我的优惠券”入口，暂未发现积分账户、积分流水和积分页面 | 个人中心增加积分入口，不新建独立商城 |
| 请求与页面 | 用户端通过 `user-web/src/api/request.ts` 统一携带 `authentication`；管理端通过既有 admin API 模块请求 | 前端只负责展示；后端重新计算积分、库存、资格与金额 |

### 1.2 审计表

| 领域 | 已有能力（代码依据） | 缺失能力 | 可复用代码 | 主要改造风险 |
|---|---|---|---|---|
| 用户与鉴权 | `User`、用户 JWT 拦截器、`BaseContext`；管理员也由单独拦截器保护 | 积分账户及用户积分 API | `BaseContext.getCurrentId()`、统一 `Result`/`PageResult` | 从请求体接收 userId 会造成越权；新页面错误复用管理端身份 |
| 订单与定价 | `OrderServiceImpl.submitOrder` 从服务端购物车取商品金额、计算套餐/菜品小计、打包费与配送费，写入订单金额和 `order_coupon_snapshot`；客户端提交的 `OrdersSubmitDTO.amount` 不是最终金额 | 结算推荐端点、订单商品级优惠分摊快照、部分退款 | `CouponOrderPricingService`、优惠策略、订单价格快照 | 购物车中已持久化的价格可能早于提交时商品现价；推荐与最终下单若各自读取价格或用不同规则会出现金额漂移 |
| 支付与取消 | 审计基线已有支付订单行锁、状态条件更新及重复状态检查；未支付取消释放套餐库存和锁定券；已支付待接单取消走退款流程。Phase 1 实施后，支付成功事务会生成待发积分，完成订单使用行锁并由单独管理员 API 审核释放 | 正式支付流程当前未端到端启用 | `OrderServiceImpl.paySuccess`、订单状态更新和库存补偿逻辑 | 当前 `OrderServiceImpl.payment` 会直接调用 `paySuccess` 模拟支付成功，不能将其视为真实支付回调；模拟支付不可作为生产支付验收 |
| 退款 | 审计基线 `order_refund_record` 以订单 ID 唯一；微信退款通知校验退款号、订单号和金额，并在事务中更新订单/优惠券状态。Phase 1 实施后支持每订单多个退款事件、客户端请求幂等号和商品净实付退款归因 | 无商品行级退款明细；商品净实付退款金额由管理员请求字段提供并需人工核账 | `OrderRefundRecord`、`OrderCouponSnapshot`、`OrderServiceImpl.completeRefund`、`WeChatPayUtil.refund` | 请求记录先提交再调用微信；外部调用失败时保留处理中记录，需运维核查微信退款单，不能以数据库回滚撤销已发生的外部退款 |
| 优惠券模板 | `CouponTemplate`：类型 1 满减、2 折扣、3 无门槛减；范围 1 全场、2 指定菜品、3 指定分类；固定/相对有效期；模板状态启停 | 配送费券、通用互斥/叠加配置 | `CouponDiscountStrategy` 及三个策略类、模板快照 | 修改模板后不得影响已发放券和历史订单，继续使用用户券/订单快照 |
| 优惠券活动 | `CouponActivity` 支持库存、每人限领、时间窗口、草稿/启用/暂停；管理端管理普通领券活动 | 积分成本、积分活动与兑券记录 | 活动管理校验及管理端活动 UI | 积分兑换活动不能错误地复用“免费领券”语义或绕过积分扣减 |
| 用户券/兑换码 | `UserCoupon` 快照模板规则和有效期；状态 0 未使用、1 锁定、2 已使用、3 已过期；已有兑换码和核销记录 | 积分兑换来源追踪；推荐最优券接口 | 现有用户券发放、优惠券列表、兑换码流水 | 需给用户券增加可追溯的积分兑换来源，确保一次兑活动只发一张券 |
| 领取并发 | 领取预占使用 Redis Lua 扣库存和限领计数；DB 记录有唯一业务号；RabbitMQ、Outbox dispatcher、Listener、失败补偿及定时对账 | 积分兑换的一致性编排 | `CouponClaimRedisService`、`CouponClaimPersistenceService`、现有定时补偿结构 | 现有复杂异步领券链路不可直接等同积分兑换事务；Redis 预扣不代表积分扣款与用户券已经成功 |
| 管理端 | `admin-web/src/views/coupon/index.vue` 包含模板、抢券活动和兑换码管理 | 积分规则、积分兑券活动、积分流水/活动报表 | Element Plus 表格、分页、表单和既有管理 API 风格 | 券模板与兑换活动需分别管理；发布规则需审计员工、库存及已发放券快照 |
| 用户端 | `user-web/src/views/coupon/index.vue` 有领券/兑换码/我的券；`user-web/src/views/order/index.vue` 允许用户选择单张券并提交 `userCouponId` | 积分余额/流水/兑换记录/积分活动页；按订单自动择优接口 | Pinia 用户身份、优惠券列表、结算页 | 当前结算页展示的是预估金额，最终金额以服务端为准；推荐结果必须可重新校验 |

### 1.3 优惠券真实状态与规则

实际代码与数据库迁移草案给出的类型如下：

| `coupon_type` | 代码策略 | 计算规则 | 说明 |
|---|---|---|---|
| 1 | `FullReductionCouponDiscountStrategy` | 达到门槛后，固定减 `discount_amount`，不超过适用商品金额 | 满减 |
| 2 | `RateDiscountCouponStrategy` | 达到门槛后按 `discount_rate` 计算折扣，可受 `max_discount_amount` 封顶 | 折扣率是乘数，例如 `0.8500` 表示按 85% 计价 |
| 3 | `NoThresholdCouponDiscountStrategy` | 无门槛固定减，优惠额不超过适用商品金额 | 无门槛减 |

优惠范围为全场、菜品 ID、分类 ID。有效期支持相对天数或固定区间。订单当前只选择一张用户券；没有券间互斥/叠加配置，也没有配送费优惠券。订单优惠按适用商品金额计算，打包费和配送费不参与优惠。用户端结算调用 `getMyCoupons(1, 100, 0)` 后只过滤未开始券；不具备按当前购物车范围和金额筛选的推荐服务。

当前券发放不是单一 Redis 流程：Lua 原子预占后创建 DB 领取记录，dispatcher 投递 RabbitMQ，listener 在独立事务里锁活动/模板、扣 DB 库存、创建用户券并完成流水；失败会补偿，定时任务对账 Redis 库存与成功记录。因此积分兑换应采用新的 DB 原子事务直接产生可识别的 `UserCoupon`，不把 Redis 计数或 RabbitMQ 消息单独作为兑换成功依据。

### 1.4 订单金额与退款边界

- `OrderServiceImpl.submitOrder` 从服务端购物车计算 `itemsAmount`，当前费率实现为每件打包费 1 元、配送费 6 元；最终应付为 `商品小计 + 打包费 + 配送费 - 券优惠`，写入 `Orders.amount` 和金额快照。
- 前端 `user-web/src/views/order/index.vue` 提交的 `amount` 是显示估算值，提交接口会由后端覆盖重算；新推荐接口必须与下单共享同一计价服务，不能信任客户端总金额、优惠金额或商品单价。
- 订单下单时使用用户券行锁与状态条件更新，防止同一张券并发重复锁定；取消/退款按既有规则释放或恢复券。
- 当前支付入口存在模拟调用 `paySuccess` 的路径，真实微信通知控制器虽存在验签/解密处理，但当前部署和支付链路需另行确认。
- 审计基线退款仅支持一单一笔整单退款；Phase 1 当前改造已扩展为每订单多笔退款事件。仍没有订单商品级退货明细，因此 `eligibleGoodsRefundAmount` 由管理员按实际商品净实付金额录入，后端校验其累计不超过订单快照中的计分基数。

## 2. 业务规则建议与待确认事项

以下规则已在 Phase 1 实施前由需求方确认；未覆盖的积分有效期暂按永久有效处理。

### 2.1 积分获取

| 决策项 | 推荐方案 | 替代方案与理由 |
|---|---|---|
| 触发时间 | 支付成功时按订单快照计算并持久化为不可用待发积分；订单完成后，由管理员调用独立审核释放接口入账；存在处理中退款时禁止释放 | 支付成功立即入账更及时，但不满足人工审核及取消/退款控制要求 |
| 计分金额 | 仅按商品实付金额计分：商品小计减去实际优惠分配到商品的金额；不含打包费、配送费 | 按整笔实付计分较易说明，但会奖励运费、包装费，且费用政策变化影响积分 |
| 比例与取整 | 每满 10.00 元得 1 分；金额统一 `BigDecimal`，向下取整 `floor(商品实付金额 / 10)`；不足 10 元不计 | 四舍五入更慷慨，但小额订单可通过拆单增加积分；不推荐 |
| 退款扣回 | 真实微信退款成功后，按每个退款事件实际退还的商品净实付金额（不含配送/打包费）累计重算目标积分；同一退款事件以退款单号幂等，累计扣回不超过实际已释放积分 | 当前没有商品行级退款分摊，管理员部分退款接口必须同时提交退款总额和商品净实付退款额；后端校验两种累计金额不超过订单余额 |
| 已消费积分不足 | 允许账户净余额为负表示积分债务；后续积分收入先抵消负值；积分兑换阶段必须禁止负余额用户兑换 | 冻结待到账积分或追回已兑换券价值复杂且影响用户权益；本阶段不实现兑换功能 |
| 取消/支付失败 | 未支付取消、支付失败不发积分；如果未来存在已发积分后取消，按退款事件唯一键扣回 | 无 |
| 有效期 | 首期积分永久有效，避免批量过期任务与消费者负担；以后若需要有效期，再引入按批次到期的积分批次表和过期流水 | 固定有效期需准确支持先进先出和过期事件，不能只用账户总余额 |

**已确认：** 支付后待发、管理员独立审核释放；退款积分基数只计商品净实付，排除配送/打包费；部分退款通过真实微信退款实现（本次不发起）；允许负余额。积分有效期当前按永久处理，进入 Phase 2 前仍需产品确认积分兑换券过期和退款策略。

### 2.2 账户与流水一致性

- 每个用户一条账户；`balance_points` 为当前净余额（可负），对外可用积分为 `GREATEST(balance_points, 0)`。总获得、总消费分别累计，不因退款删除历史流水。
- 每次加减必须在同一 DB 事务中锁定账户行、校验可用积分、插入唯一流水、更新账户余额；流水的 `balance_before + change_points = balance_after`。
- 业务唯一键由业务类型与业务事件 ID 构成，订单奖励键例如 `ORDER_EARN:<orderId>`，退款扣回键 `REFUND_REVERSAL:<outRefundNo>`，兑换扣分键 `REDEMPTION:<redemptionId>`。数据库唯一索引是幂等最终保障。
- 同步校验账户余额与流水尾值，并提供可重复执行的对账脚本/运维查询；不允许通过直接改账户余额修复。
- 规则变化采用版本化配置，流水保存规则版本、计分基数及原因快照，以便解释历史积分。

### 2.3 积分兑换活动

管理员配置名称、券模板、兑换积分、时间窗口、库存、每人限兑次数、券有效期策略和活动状态。用户券由现有模板生成并保存优惠规则快照。活动有效期控制“可兑换时间”，用户券有效期策略支持继承模板规则或明确的发券后 N 天；不允许活动结束使已发券失效。

首期建议只发放一张券，不在 Redis 预扣积分。单个兑换事务按固定锁顺序执行：创建/锁定用户积分账户 → 锁活动行并校验状态/时间/库存 → 校验每人限兑 → 校验幂等请求 → 条件扣库存 → 扣积分并写流水 → 插入兑换记录 → 创建 `user_coupon` 快照并关联兑换记录 → 将兑换置成功。任意失败回滚全部变化。

同用户的账户行锁可串行化该用户并发兑换，并发限次计数在账户锁保护下检查；活动行锁与 `UPDATE ... SET stock_remaining=stock_remaining-1 WHERE stock_remaining>0` 防止超兑。对外请求需客户端生成幂等键；相同用户、相同键重放返回原兑换结果，不重复扣分或发券。数据库唯一索引为最后防线。

若未来兑换量足以使活动行锁成为瓶颈，再评估 Redis Lua 作为库存预占；此时必须补充 DB outbox、失败补偿、重试状态和 Redis-DB 对账，不得先引入复杂架构。

## 3. 核心流程图

### 3.1 订单积分发放与退款扣回

```text
支付成功通知
  -> 锁定订单并执行支付状态幂等迁移
  -> 读取服务端订单优惠快照，按商品净实付计算待发积分
  -> points_pending_order 以 order_id 唯一保存规则快照和待发数量
  -> 提交支付状态及待发积分

管理员审核释放
  -> 锁订单，要求订单已完成、已支付且无处理中退款
  -> 锁定积分待发记录与用户账户
  -> 扣除负余额债务（余额仍可为负），写 ORDER_EARN 唯一流水
  -> 同事务更新账户、流水和待发状态

退款成功（同步响应或微信通知）
  -> 校验退款号、订单号、实际退款金额并锁定退款事件/订单
  -> 累计商品净实付退款额，拒绝超过订单计分基数
  -> 未释放积分：下调待发数量；已释放：计算本事件应扣差额
  -> 写 REFUND_REVERSAL 唯一流水并更新账户/退款状态
  -> 全额现金退款才更新订单退款状态；取消订单全额退款后才恢复套餐库存
```

### 3.2 积分兑换

```text
用户提交 activityId + idempotencyKey
  -> 由 JWT 上下文取得 userId（忽略客户端 userId）
  -> 锁定/创建 points_account
  -> 查询同键兑换记录；已成功则返回原结果
  -> 锁 points_exchange_activity，校验状态、时间、库存、限兑
  -> 锁 points_rule（如该活动成本无需动态规则则不需要）
  -> 条件扣积分、扣库存
  -> 写 points_ledger 与 processing redemption
  -> 复用现有模板校验与 UserCoupon 快照创建
  -> 写 user_coupon.points_redemption_id，标记兑换成功
  -> 同一 MySQL 事务提交；任何失败全部回滚
```

### 3.3 结算推荐

```text
用户请求预览（仅传商品/数量或当前购物车标识）
  -> 后端按当前登录用户读取购物车及商品/分类现价
  -> 统一构造商品行、商品小计、打包费、配送费
  -> 读取用户未使用且有效的券快照
  -> 按当前规则过滤门槛、范围、状态和有效期
  -> 计算无券 + 每张单券的合法应付金额
  -> 按应付最少排序；平局使用稳定规则
  -> 返回推荐、备选、不可用券原因和价格版本/时间
  -> 用户提交推荐 userCouponId
  -> submitOrder 在事务内重读价格/券状态并重算、锁券；过期或金额改变则明确失败并要求刷新
```

## 4. 数据库设计与 SQL 草案

### 4.1 与现有表的关系及迁移原则

券基础表来自 `migration-coupon-phase1.sql`：`coupon_template`、`coupon_activity`、`coupon_claim_record`、`user_coupon`；订单优惠及整单退款来自 `migration-coupon-phase2.sql`：`order_coupon_snapshot`、`order_refund_record`。不能再创建重复模板或用户券表。

建议新增：

1. `points_account`：用户账户。
2. `points_ledger`：不可变积分流水。
3. `points_rule`：版本化积分规则。
4. `points_exchange_activity`：积分兑券活动。
5. `points_exchange_redemption`：兑换请求/结果记录。
6. 对 `user_coupon` 增加积分兑换来源列及唯一索引。

以下 DDL 是拟议结构；上线前根据实际数据库版本、字符集、现有列/索引及迁移历史修订。项目现有 SQL 多使用逻辑关联、不依赖外键，本草案保持该风格，应用事务和唯一约束维护关系。

Phase 1 实际迁移 `migration-points-001-phase1.sql` 仅包含账户、规则、流水三表；兑换活动/兑换记录及 `user_coupon` 来源扩展属于 Phase 2，不能在 Phase 1 提前执行。项目配置只指定 MySQL JDBC 驱动，没有锁定服务器版本；迁移采用现有脚本使用的 InnoDB、`BIGINT`、`DECIMAL(10,2)`、`DATETIME`、唯一索引和 `INSERT ... ON DUPLICATE KEY` 风格，不使用生成列或更高版本专属语法。现有券脚本使用 `JSON`，投产前仍应核对实际 MySQL 版本。

### 4.2 DDL

```sql
CREATE TABLE `points_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `balance_points` BIGINT NOT NULL DEFAULT 0 COMMENT '净积分，可为负表示退款扣回债务；可用值=max(balance_points,0)',
  `total_earned` BIGINT NOT NULL DEFAULT 0,
  `total_spent` BIGINT NOT NULL DEFAULT 0,
  `version` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_points_account_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User points account';

CREATE TABLE `points_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `version` INT NOT NULL,
  `spend_step_amount` DECIMAL(10,2) NOT NULL COMMENT '达到该商品实付金额得分',
  `points_per_step` INT NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=draft,1=active,2=archived',
  `effective_from` DATETIME NOT NULL,
  `effective_to` DATETIME DEFAULT NULL,
  `description` VARCHAR(255) DEFAULT NULL,
  `create_user` BIGINT DEFAULT NULL,
  `update_user` BIGINT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_points_rule_version` (`version`),
  KEY `idx_points_rule_status_effective` (`status`,`effective_from`,`effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Versioned points earning rules';

CREATE TABLE `points_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `business_type` VARCHAR(32) NOT NULL COMMENT 'ORDER_EARN, REFUND_REVERSAL, REDEMPTION, MANUAL_ADJUSTMENT',
  `business_id` VARCHAR(64) NOT NULL COMMENT 'Order/refund/redemption stable identifier',
  `idempotency_key` VARCHAR(128) NOT NULL,
  `change_points` BIGINT NOT NULL COMMENT 'positive grant, negative spend/reversal',
  `balance_before` BIGINT NOT NULL,
  `balance_after` BIGINT NOT NULL,
  `rule_id` BIGINT DEFAULT NULL,
  `eligible_amount` DECIMAL(10,2) DEFAULT NULL COMMENT 'yuan; eligible net merchandise payment, if relevant',
  `reason` VARCHAR(255) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_points_ledger_idempotency` (`idempotency_key`),
  UNIQUE KEY `uk_points_ledger_business_event` (`business_type`, `business_id`),
  KEY `idx_points_ledger_user_time` (`user_id`,`create_time`,`id`),
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Append-only points ledger';

CREATE TABLE `points_exchange_activity` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `template_id` BIGINT NOT NULL,
  `points_cost` BIGINT NOT NULL,
  `stock_total` INT NOT NULL,
  `stock_remaining` INT NOT NULL,
  `per_user_limit` INT NOT NULL,
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME NOT NULL,
  `coupon_validity_type` TINYINT NOT NULL DEFAULT 0 COMMENT '0=inherit template,1=days after issue,2=fixed interval',
  `coupon_validity_days` INT DEFAULT NULL,
  `coupon_valid_start_time` DATETIME DEFAULT NULL,
  `coupon_valid_end_time` DATETIME DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=draft,1=active,2=paused,3=ended',
  `create_user` BIGINT DEFAULT NULL,
  `update_user` BIGINT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_points_activity_status_time` (`status`,`start_time`,`end_time`),
  KEY `idx_points_activity_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Points-to-coupon exchange campaigns';

CREATE TABLE `points_exchange_redemption` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `activity_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `idempotency_key` VARCHAR(96) NOT NULL,
  `points_cost` BIGINT NOT NULL,
  `user_coupon_id` BIGINT DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=processing,1=succeeded,2=failed',
  `failure_reason` VARCHAR(255) DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_points_redemption_user_request` (`user_id`,`idempotency_key`),
  KEY `idx_points_redemption_user_time` (`user_id`,`create_time`,`id`),
  KEY `idx_points_redemption_activity_user` (`activity_id`,`user_id`,`status`),
  KEY `idx_points_redemption_status_time` (`status`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Idempotent points exchange records';

ALTER TABLE `user_coupon`
  ADD COLUMN `points_redemption_id` BIGINT DEFAULT NULL
    COMMENT 'Source points exchange; NULL for non-points coupons',
  ADD UNIQUE KEY `uk_user_coupon_points_redemption` (`points_redemption_id`);
```

### 4.3 字段、索引和状态约束

| 表 | 关键唯一性/索引 | 状态与约束 |
|---|---|---|
| `points_account` | `user_id` 唯一，保证一人一个账户 | `balance_points` 可为负；消费前条件校验可用积分；初始化建议 `INSERT IGNORE` 后 `SELECT ... FOR UPDATE` |
| `points_ledger` | `idempotency_key` 唯一、`business_type + business_id` 唯一；用户时间倒序索引 | 追加写；允许低于门槛的订单写入 0 分事件以锁定订单级幂等；`balance_after = balance_before + change_points`；流水不可物理删除 |
| `points_rule` | 规则版本唯一；状态+有效时间索引；服务读取时要求当前时刻恰好一条有效规则 | Phase 1 迁移只种入一条 10 元/1 分规则；Phase 3 管理规则变更时须在事务内串行切换并校验有效期不重叠；规则变更不追溯历史 |
| `points_exchange_activity` | 状态/起止时间、模板索引 | 状态 0 草稿、1 进行中、2 暂停、3 结束；`stock_remaining` 不得小于零；活动结束只禁止新兑换 |
| `points_exchange_redemption` | 用户+幂等键唯一；活动+用户索引用于限次 | 状态 0 处理中、1 成功、2 失败；同一事务提交后不应留下 processing；异步实现时必须配套恢复任务 |
| `user_coupon` 扩展 | 积分兑换 ID 唯一；已有 `claim_record_id` 唯一且可空 | 每条兑换只可生成一张券；空来源不影响原有领券/兑换码数据 |

`points_exchange_redemption` 处理状态和流水在首期单事务中创建、完成，事务失败整笔回滚，因此不会留下“扣了积分但没发券”的可见成功结果。若实现中选择跨事务或 MQ，则需明确处理中超时、积分/库存返还和券发放幂等，不得将中间态当成功返回。

## 5. API 设计

响应沿用 `Result<T>` 包装；分页沿用 `PageResult`。用户接口由 `/user/**` 拦截器保护，用户 ID 从 `BaseContext` 获取；管理接口由 `/admin/**` 管理员拦截器保护。所有金额使用元的十进制字符串或现有 Jackson 数值格式，积分使用整数。

### 5.1 用户端

| 方法与路径 | 请求 | 返回关键字段 | 校验与异常 |
|---|---|---|---|
| `GET /user/points/account` | 无 | `netPoints`、`availablePoints`、`totalEarned`、`totalSpent` | 未初始化账户按 0 返回；不能传 userId |
| `GET /user/points/ledger?page&pageSize&type` | 分页和可选业务类型 | `records[{changePoints,balanceBefore,balanceAfter,businessType,reason,createTime}]` | page/pageSize 范围限制；只查当前用户 |
| `GET /user/points/exchange-activities?page&pageSize` | 分页，可筛可兑 | 活动名称、券模板摘要、`pointsCost`、库存/剩余库存、个人剩余次数、开始/结束时间、状态 | 只公开已发布活动；个人次数按登录用户计算 |
| `POST /user/points/exchange` | `{activityId,idempotencyKey}` | `redemptionId,status,pointsCost,userCouponId,remainingPoints` | 身份取上下文；状态、活动时间、库存、次数、余额、券模板均在事务内二次校验；相同键返回原结果 |
| `GET /user/points/exchange-records?page&pageSize` | 分页 | 活动快照、兑换积分、结果状态、用户券 ID、时间 | 只查当前用户；处理中状态提供轮询语义（首期事务通常只看到成功记录） |
| `GET /user/order/coupon-options` | 购物车标识或空（使用当前用户购物车） | `baseAmount,packFee,deliveryFee,recommendedCouponId,discount,payable,reason,alternatives,unavailableCoupons[]` | 后端重查购物车、商品、券状态；不能接受可信的客户端总额 |
| `POST /user/order/discount-preview` | 可选当前订单草稿/购物车引用；仅含商品 ID、数量时也必须重新读价 | `pricingAt,itemsAmount,fees,selected/recommended option,discount,payable,explanation` | 用户身份、菜品/套餐售卖状态、券归属/状态/有效期/门槛/范围全由服务端检查；不锁券，不保证预览后仍有效 |

下单继续使用现有提交 API 和 `userCouponId`。服务端以真实购物车/数据库价格重算，锁券并写快照；若推荐券在预览后失效或价格变化，返回明确业务错误和最新可用方案，不静默套用另一张券。

### 5.2 管理端

| 方法与路径 | 请求 | 返回/校验 |
|---|---|---|
| `GET /admin/points/rules` | `page,pageSize,status` | 规则版本、计分门槛、有效区间、状态；员工 Token |
| `POST /admin/points/rules` | `{spendStepAmount,pointsPerStep,effectiveFrom,description}` | 创建草稿/未来生效版本；金额必须大于 0、分值为正整数 |
| `PUT /admin/points/rules/{id}/publish` | 无或确认版本号 | 校验无重叠生效规则，记录 `BaseContext` 员工 ID；不能改写已生效历史版本 |
| `PUT /admin/points/rules/{id}/archive` | 无 | 仅结束/归档规则，不删除流水引用的版本 |
| `GET /admin/points/exchange-activities/page` | 分页、名称、状态 | 活动、模板、积分成本、库存、已兑、剩余库存及时间 |
| `POST /admin/points/exchange-activities` | 名称、templateId、积分、库存、每人限额、时间、券有效期、草稿状态 | 检查模板存在且启用、规则合法、库存为正、结束晚于开始 |
| `PUT /admin/points/exchange-activities` | 活动 ID + 可编辑字段 | 已开始或已有兑换的活动不可任意改模板/成本/库存历史；库存变更需校验已兑数 |
| `PUT /admin/points/exchange-activities/{id}/status` | `{status}` | 允许草稿发布、暂停、结束；暂停不能撤销已发用户券 |
| `GET /admin/points/exchange-records` | 用户、活动、状态、时间分页过滤 | 返回用户、活动、扣分、券 ID、幂等键（列表不展示完整敏感请求信息） |
| `GET /admin/points/ledger` | 用户、类型、业务 ID、时间分页过滤 | 只读审计；人工调账另设受权限控制的原因必填接口，不直接编辑流水 |
| `GET /admin/points/exchange-activities/{id}/statistics` | 活动 ID | 已兑数量、剩余库存、失败数、独立用户数；统计口径与 `redemption.status=1` 一致 |

模板编辑/停用需沿用 `CouponServiceImpl` 当前校验及已发券快照语义。API 的具体分页字段、日期格式应遵循现有 DTO、`Result` 和 MyBatis 分页约定。

## 6. 确定性优惠计算引擎

### 6.1 输入与金额语义

建议拆为 `OrderPricingContext`、`EligibleCoupon`、`DiscountOption` 与 `CouponRecommendationService`。商品行含商品 ID、分类 ID、菜品/套餐种类、数量、服务端单价和商品行金额；费用包含服务端打包费、配送费；券包含用户券 ID、类型/规则快照、适用范围、门槛、有效期、当前状态。

禁止将客户端 `amount`、`discountAmount`、商品价格、可用券 ID 列表作为定价依据。预览和下单必须调用同一个 pricing helper；最终下单在事务里重读购物车和券，按当前价格复算。

项目现有数据库金额是 `DECIMAL(...,2)` 元，服务端策略使用 `BigDecimal`。全程禁止 `double`/`float`；入参与输出按两位小数校验，策略中间过程使用足够精度，最终折扣按项目统一约定 `HALF_UP` 到分，并限制 `0 <= discount <= eligibleGoodsAmount`、`payable >= 0`。若折扣比例产品规则要求向下取分，应在产品确认后统一替换并做兼容测试。

### 6.2 已实现类型计算

1. **满减**：适用商品金额 `eligibleAmount` 达到 `thresholdAmount` 后优惠固定 `discountAmount`，最终优惠为 `min(discountAmount, eligibleAmount)`。
2. **折扣**：门槛按适用商品金额判断。`rawDiscount = eligibleAmount × (1 - discountRate)`，按 `HALF_UP` 到分；若设置上限则 `min(rawDiscount, maxDiscountAmount)`；最终不能超过 `eligibleAmount`。
3. **无门槛减**：`min(discountAmount, eligibleAmount)`。
4. **配送/打包费用**：不属于现有券类型，不打折，不为了推荐算法新增配送券。

适用范围：全场券对全部可优惠商品；指定菜品券按菜品 ID；指定分类券按分类 ID。`eligibleAmount` 只累加范围内商品。范围内没有商品时不可用。

### 6.3 选优规则、不可用原因

当前业务不支持券叠加，故首期候选集合固定为“无券方案 + 每一张合法单券方案”，不枚举多券组合。算得每种方案后选 `payable` 最低项。稳定平局顺序固定为：无券优先（避免同价时消耗券），其后按券到期时间升序，再按 `userCouponId` 升序。推荐响应包含版本/时间戳、推荐理由、节省金额、应付金额、备选和不可用原因，例如：已使用、锁定、过期、未开始、未达到门槛、适用商品不匹配、模板停用。

伪代码：

```text
context = pricingService.loadAndPriceFromDatabase(userId, cart)
options = [noCoupon(context)]
for coupon in couponService.listOwnedCouponsForUpdatePreview(userId):
    reason = eligibility(coupon, context, now)
    if reason != eligible:
        unavailable.add(coupon.id, reason)
        continue
    eligibleGoods = sum(scopeMatchedLine.amount)
    discount = strategy(coupon.type).calculate(coupon, eligibleGoods)
    payable = max(context.itemsAmount + context.packFee + context.deliveryFee - discount, 0)
    options.add(option(coupon.id, discount, payable))
recommended = min(options, by payable, then stableTieBreakers)
return recommended, alternatives, unavailable, context.pricingAt
```

不使用机器学习决定金额。若未来明确支持叠加，先在模板层配置互斥组与叠加策略，再限制组合规模、定义折扣顺序和每张券的金额分摊，并增加组合枚举性能边界测试。

### 6.4 金额一致性与核销

- 当前预览仅建议，不预占优惠券；最终下单通过 `user_coupon` 行锁与条件状态更新核验状态和归属。
- 用户提交了券但券状态变化时，不得返回旧折扣金额；失败并要求刷新预览。
- `order_coupon_snapshot` 继续保存订单价格、费用、券规则快照；为审计积分，可在订单积分流水 `detail_snapshot` 保存实际商品计分基数及券优惠分配口径。若后续支持部分退款，需新增订单商品级折扣分摊/退款事件明细。
- 已存在的订单取消、退款恢复券逻辑保持不变；重复退款通知通过现有退款记录状态幂等处理。

## 7. 事务、并发与异常处理

| 场景 | 推荐机制 |
|---|---|
| 支付回调重复 | 只在订单合法状态迁移/完成事件中发积分；积分流水 `ORDER_EARN` 唯一键 + 账户行锁；重复回调不得重复积分。先完成真实支付链路验证 |
| 完成与取消/退款竞争 | 订单行 `FOR UPDATE`，按状态条件更新；先确定唯一终态后再在同一事务触发积分业务。统一锁顺序，避免订单/账户互相等待 |
| 整单退款重复通知 | 沿用退款记录锁及成功状态幂等；积分扣回使用退款业务唯一键；订单退款和积分扣回应同事务提交 |
| 用户同时多次兑换 | 用户账户行锁串行余额和该用户限次检查；活动行锁 + 条件库存扣减；用户幂等键唯一 |
| 多用户争库存 | 活动行 `SELECT ... FOR UPDATE` 或条件 `UPDATE ... stock_remaining > 0`；不可仅在前端禁用或读取后再扣 |
| 事务回滚 | 首期积分账户、流水、兑换记录、活动库存、用户券必须同库同事务；事务失败无任何部分成功。事务外微信/Redis 操作不得加入该链路 |
| Redis 与 MySQL 不一致 | 首期兑换不使用 Redis 计数器。若未来引入预占，Redis 为临时限流/库存缓存，DB 才是最终账本，并提供 outbox、补偿、重试和对账 |
| 退款时积分不足 | 允许净余额变负值并记录退款扣回流水；禁止兑换负余额；未来获得积分先冲抵负值。需产品确认并向用户解释 |
| 优惠券过期与结算同时发生 | 服务端按事务时刻重新校验有效期；下单锁券、条件更新防止竞态；前端推荐不是核销承诺 |
| 两个订单并发使用同券 | 现有用户券状态 0→1 条件更新/行锁；只有一个订单可成功 |
| 模板被停用或改规则 | 已发用户券使用规则快照；管理端停用只影响新发券/兑换，不改写已有用户券和历史订单 |
| 消息异步故障 | 积分发放首期不独立异步；若订单事务和积分不能同库提交，使用事务 outbox + 幂等消费者，不使用“先发消息再假设成功” |

建议事务方法放在独立 Spring Service Bean，避免同类自调用绕过 `@Transactional` 代理。使用 `rollbackFor = Exception.class` 与明确的条件更新行数检查；不要捕获异常后伪装成功。对死锁/瞬时冲突可按既有项目规范有限重试，重试必须复用同一幂等键。

## 8. 前端页面设计

### 8.1 用户端

- 个人中心新增“我的积分”入口，展示可用积分、净积分债务提示（若负数）、累计获得/消费。
- 积分中心首页含可兑换优惠券活动列表：所需积分、券规则、活动时间、库存提示、个人剩余次数；兑换操作展示二次确认和结果。
- 积分明细页按时间分页，说明获得/兑换/退款扣回原因及关联订单/活动。
- 兑换记录页展示处理中/成功/失败、券状态与有效期，成功券直接进入现有“我的优惠券”。
- 结算页显示“智能推荐”标识、推荐原因、节省金额、实付；保留手动选择单券；不可用券显示明确原因。
- 前端生成 UUID 幂等键并在请求重试时复用；不能通过按钮禁用替代后端防重。兑换成功后刷新账户、活动、用户券。

### 8.2 管理端

- 新增积分规则页：版本、每满金额得分、有效期、草稿/生效状态和发布确认。
- 新增积分兑券活动页：选择已启用优惠券模板，填写兑换积分、时间、库存、每人限兑、用户券有效期；发布前展示校验结果。
- 新增积分账户/流水/兑换记录只读审计和活动统计；人工调账若获准，必须填写原因、记录员工 ID、创建不可变正负流水并执行权限校验。
- 复用 `admin-web/src/views/coupon/index.vue` 的分页、筛选、表单和操作反馈模式，但积分活动与免费领券活动分开路由、分开 API。

## 9. 测试设计

| 测试场景 | 类型 | 关键断言 |
|---|---|---|
| 支付成功生成待发积分、管理员释放 | 单元 + 集成 | 支付时余额不变；订单完成并审核后余额/流水更新 |
| 9.99、10.00、19.99、20.00 元边界 | 单元 | 分别 0、1、1、2 分；无浮点误差 |
| 打包费/配送费不计分 | 单元 | 只有商品净实付参与积分 |
| 使用优惠券后积分基数 | 单元 | 按实际商品优惠后金额，而非优惠前小计 |
| 重复支付回调、重复审核释放 | 集成 | 一笔订单只有一条待发记录/一条 `ORDER_EARN`，余额只变更一次 |
| 未支付取消、支付失败 | 集成 | 不发积分；已锁券按现有规则释放 |
| 全额与多次部分退款、回调重放 | 集成 | 逐次累计重新取整，实际扣回不超过已释放积分，重复事件不重复扣分 |
| 退款扣回导致负余额 | 集成 | `balance_points` 可负、可用积分 0、兑换被拒；未来积分先还债 |
| 积分规则版本切换 | 单元 + 集成 | 旧订单引用旧规则快照；生效时间重叠时发布失败 |
| 兑换成功 | 集成 | 同事务扣积分/库存、写流水/兑换、发一张可用 `UserCoupon` |
| 同幂等键重复兑换 | 集成 | 返回原成功结果，不重复扣积分、库存或发券 |
| 不同幂等键重复兑换超过限额 | 集成 | 账户锁下限额可靠拦截 |
| 积分不足、活动未开始/结束/暂停 | 集成 | 事务回滚，无库存/积分/用户券变化 |
| 活动库存为 0、多用户争最后一张 | 并发集成（真实 MySQL） | 最多一个成功、库存非负、账本与发券数一致 |
| 券模板停用/无效 | 集成 | 新兑换拒绝，已发用户券历史快照不变 |
| 满减、折扣封顶、无门槛减 | 单元 | 优惠额和门槛符合现行策略，不能大于可优惠商品金额 |
| 指定菜品/分类范围 | 单元 | 范围外商品不计入门槛和优惠 |
| 多张券最优选择及稳定平局 | 单元 | 包含无券候选，选出最低应付；重复执行结果稳定 |
| 不满足券过滤 | 单元 | 过期、未开始、已用、锁定、门槛不满足、范围不匹配均有原因 |
| 多券叠加 | 单元 | 当前策略明确不生成组合；未来叠加功能单独测试 |
| 无可用券/小额或零金额 | 单元 | 返回无券原价方案，金额不为负 |
| 预览后券被其他订单锁定 | 并发集成（真实 MySQL） | 最终下单拒绝过期推荐，禁止重复核销 |
| 客户端篡改金额/商品单价/userId | 集成/API | 服务端忽略或拒绝，按数据库商品及当前身份重算 |
| 数据库唯一键、事务回滚 | 集成（真实 MySQL） | 重放幂等键冲突被安全处理；任一发券失败回滚所有兑换数据 |
| Redis 断连 | 集成（真实 Redis，仅现有领券回归） | 新积分兑换首期无 Redis 依赖；现有抢券回归链路按既有补偿/对账工作 |

单元测试使用固定 `Clock` 或显式时间注入，避免有效期测试依赖系统时间。数据库行锁、唯一键、事务隔离和并发超兑必须在真实 MySQL 环境验证；现有优惠券 Redis Lua/领券状态机的集成回归需要真实 Redis，RabbitMQ 消费链路按项目当前测试环境决定使用真实 Broker 或受控容器。

## 10. 实施阶段与依赖关系

本项目按五个可独立验证的开发阶段推进。Phase 1 是本次实施范围；其余阶段是计划，不在本次实现。

### Phase 1：积分账户、积分流水、待发审批、退款积分调整

- **文件**：`PointsAccount`、`PointsRule`、`PointsLedger`、`PointsPendingOrder`、`OrderRefundRecord`、`OrderRefundDTO`；`PointsMapper`、`OrderRefundMapper`、`PointsService`、`PointsServiceImpl`、`OrderServiceImpl`、管理端 `OrderController`；迁移 `migration-points-001-phase1.sql`。
- **接口**：`POST /admin/order/{id}/points/release`（管理员审核释放）；`PUT /admin/order/refund`（管理员部分退款，需 `orderId`、唯一 `requestId`、退款总额及商品净实付退款额）；现有微信退款通知继续复用 `/notify/refundSuccess`。
- **迁移**：建 `points_account`、`points_rule`、`points_ledger`、`points_pending_order`；扩展退款记录表的幂等请求号和商品退款计分金额，移除订单级唯一退款限制；初始化每 10 元商品净实付 1 积分规则。仅提供人工 SQL，不自动执行。
- **测试**：支付生成待发积分、订单级幂等、人工审核、负余额抵债、退款前审核下调待发积分、多次部分退款累计取整、超额拒绝、重复回调/请求、微信适配调用参数。
- **完成标准**：积分账户/流水/待发记录在同一数据库事务；退款请求先持久化幂等记录再调用微信，避免网络调用失败丢失退款事件；MySQL 行锁、唯一索引和回调重放需在目标数据库验证。

### Phase 2：积分兑换活动、兑换记录、优惠券发放

- **文件**：新增兑换活动/记录实体、Mapper、Service、用户/管理端 DTO 与 Controller；为 `user_coupon` 增加积分兑换来源字段；新增 `migration-points-002-phase2.sql`。
- **接口**：用户兑换活动列表、幂等兑换、兑换记录；管理端活动创建/编辑/发布/暂停、兑换记录查询。
- **迁移**：建兑换活动/记录表并扩展 `user_coupon` 来源与唯一索引；先核对目标库已执行的优惠券迁移。
- **测试**：余额不足、幂等键重放、用户限兑、活动过期/暂停、库存并发争抢、发券失败回滚。
- **完成标准**：积分、活动库存、兑换记录和现有 `UserCoupon` 快照在同一 DB 事务提交；失败无部分成功。

### Phase 3：用户端积分中心、积分兑换页面、管理端配置页面

- **文件**：新增用户端积分 API 模块、积分中心/流水/兑换记录页面；更新 `user-web/src/views/my/index.vue`；新增管理端积分规则和兑换活动页面/API/路由。
- **接口**：用户账户、流水、活动和兑换记录查询；管理端积分规则版本和活动配置接口。
- **迁移**：不新增表，使用 Phase 1/2 表。
- **测试**：鉴权隔离、分页、空账户、负余额提示、管理表单校验；重复操作仍由服务端幂等保障。
- **完成标准**：UI 只展示后端计算值；用户无法访问他人账户或流水。

### Phase 4：单张优惠券最优推荐并接入结算

- **文件**：抽取订单共用定价/推荐服务；新增优惠方案 DTO/接口；更新 `user-web/src/views/order/index.vue` 和 API；仅在有审计需要时扩展价格快照。
- **接口**：结算预览/可用券推荐；现有提交订单 API 保持兼容并服务端重算。
- **迁移**：如确需新增推荐/分摊快照，再新增独立 `migration-points-004-phase4.sql`；无必要不扩 schema。
- **测试**：单券策略、范围、门槛、稳定排序、无券方案、篡改金额、预览后券被占用、并发核销。
- **完成标准**：预览与下单共用服务器定价规则；不支持券叠加；下单最终重新锁券和计算。

### Phase 5：完整回归测试、异常场景与部署说明

- **文件**：补齐端到端/并发测试、迁移与部署/回滚说明、运维对账只读检查。
- **接口**：不扩展业务接口；诊断接口另行审查权限与数据范围。
- **迁移**：人工按编号执行迁移，不在应用启动时自动执行。
- **测试**：真实 MySQL 验证行锁/唯一索引/回滚；真实 Redis/RabbitMQ 回归现有抢券；支付和退款重复/乱序通知；前端端到端验证。
- **完成标准**：所有目标环境完成迁移前检查、备份、上线验证、对账和回滚演练，并记录未覆盖场景。

依赖关系：Phase 1 → Phase 2 → Phase 3；Phase 4 依赖现有订单价格和优惠券规则；Phase 5 汇总所有阶段并验证真实基础设施下的并发场景。

## 11. 风险清单

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 当前支付入口存在直接模拟成功逻辑 | 高 | 上线积分前先核验并启用真实支付通知；积分只挂唯一完成事件，不能把模拟支付视为生产验收 |
| 退款商品净实付金额目前没有商品行级明细 | 高 | 管理端请求显式提供商品净实付退款额，服务端以订单快照累计校验；上线前财务需核对金额录入流程 |
| 订单完成状态事件可能与退款并发 | 高 | 订单行锁及状态条件迁移；两条路径用统一锁顺序、积分唯一键和同库事务 |
| 已消费积分遇到事后退款 | 高 | 确认负余额/债务及后续收入抵扣策略；提供客服查询与解释 |
| 购物车存储价格在商品改价后可能陈旧 | 高 | 推荐和最终下单都从商品/套餐当前数据服务端重算；必要时对购物车价格策略做单独产品决策 |
| 推荐预览与下单之间券状态/价格变化 | 中 | 预览不锁券；下单重新校验并返回刷新提示；同一 pricing service |
| 活动库存热点锁 | 中 | 先采用 MySQL 单行锁/条件扣减，压测后决定是否预占缓存；不提前引入 Redis 复杂度 |
| 优惠券活动库存与积分活动库存语义混淆 | 中 | 分离积分活动表和 API；兑换的券来源单独追踪，不复用普通抢券限额 |
| 规则调整造成历史分值无法解释 | 中 | 规则版本化，流水保存规则 ID、计分基数和业务快照，禁止覆盖旧版本 |
| 既有券迁移未在所有环境执行 | 中 | 发布前检查目标库 schema、唯一索引、用户券状态和现有迁移顺序；不依据文件存在推断迁移已完成 |
| 多券组合扩大算法复杂度和金额分摊风险 | 低（首期不支持） | 明确首期单券；只有产品确认叠加后再增加互斥组、顺序和分摊设计 |

## 12. 第二阶段复核与 Phase 1 实施决策

- **代码/字段复核**：用户、订单、退款及优惠券相关类和字段均按源码核实；`OrderCouponSnapshot.itemsAmount` 与 `couponDiscount` 用于商品净实付积分基数，排除打包费/配送费。审计基线的退款表一单一笔；本次 Phase 1 通过迁移扩展为多退款事件及独立请求幂等键。
- **原设计修订**：删除 `points_rule` 的生成列 `active_slot` 唯一索引草案，避免依赖生成列版本/索引行为；改由规则版本唯一、有效时间索引及服务端“恰好一条有效规则”校验。明确低于门槛订单仍写入 0 分奖励流水以保障订单级幂等。
- **并发一致性**：支付成功事务锁定订单并创建唯一待发积分记录；管理员释放时锁订单和待发记录；账户变更、流水和待发状态同事务。退款请求在锁订单的事务中先写入唯一退款事件并提交，之后才调用微信；退款成功处理锁定退款事件/订单并累计每个商品退款基数，数据库唯一键和退款号共同保护重放。
- **订单/规则异常**：价格快照缺失或积分规则缺失/不唯一时，支付状态和待发积分事务回滚并显式报错；不猜测积分值或写成功形状的零分记录。部署前需确认新订单均有价格快照且积分规则唯一有效。
- **退款政策**：支付时生成待发积分，管理员只能对已完成、仍已支付且没有处理中退款的订单执行释放。退款在释放前会调低待发积分；释放后按逐笔成功退款的商品净实付金额累计重算目标积分，扣回不超过已实际释放积分；现金退款全额完成后更新支付状态。欠账允许负余额，Phase 2 兑换必须禁止负余额账户。
- **券与推荐兼容性**：Phase 1 不发用户券、不改现有 `user_coupon` 或 `order_coupon_snapshot`；只读价格快照计算积分。Phase 2 可在事务内复用现有 `UserCoupon` 快照生成逻辑；Phase 4 推荐继续由现有下单锁券和金额快照最终核验。
- **迁移兼容性**：应用仅配置 MySQL JDBC，没有可验证的目标数据库版本；脚本按 MySQL 5.7+ 编写并采用条件 DDL，不执行迁移。脚本仅为上线时仍处于履约状态（订单状态 2/3/4）、已支付且有金额快照的订单补建待发积分；上线前已完成历史订单不追溯积分。上线前必须对照目标库检查表/索引、规则唯一性、回填数量并备份。

当前 `OrderServiceImpl.payment` 仍含模拟支付成功逻辑；新增退款 API 代码也未向微信发出实际请求。本次只验证模拟/单元路径，不能据此认定真实支付、微信退款或目标 MySQL 并发已验收。

### Phase 1 当前交付状态

- 已实现支付后待发积分、管理员审核释放、部分退款接口及微信退款通知积分累计调整；旧整单取消/拒单路径也复用退款事件记录。
- 本地 `mvn -f backed/pom.xml -pl sky-server -am test`：83 项测试，0 失败、0 错误、7 项跳过；`mvn -f backed/pom.xml -pl sky-server -am -DskipTests package`：`BUILD SUCCESS`。
- 没有执行 SQL，也没有连接目标 MySQL、Redis 或微信支付环境；并发锁/唯一约束、SQL 回填结果和真实退款回调尚未验收。因此**Phase 1 代码实现已完成，但 Phase 1 投产验收未完成；不得进入 Phase 2**，直至完成下列人工迁移与集成验证。

### Phase 1 部署前人工检查（本次未执行）

1. 备份目标数据库并记录当前应用版本；确认 `SELECT VERSION()` 返回 MySQL 5.7+。
2. 确认优惠券/订单快照迁移已经部署：先运行 `SHOW TABLES LIKE 'order_coupon_snapshot';`；确认表存在后检查 `SELECT COUNT(*) FROM order_coupon_snapshot WHERE items_amount IS NULL OR coupon_discount IS NULL;` 为 0。
3. 检查 `SHOW TABLES LIKE 'points_%';`。若已存在任一 `points_*` 表，不要直接重跑脚本，先对照字段、数据和索引做迁移评审。
4. 部分退款由管理员提交 `eligibleGoodsRefundAmount`，财务/运营负责录入复核；该金额必须是实际退回的商品净实付，不含打包费和配送费。
5. 经批准后人工执行 `backed/sky-server/src/main/resources/db/migration-points-001-phase1.sql`；确认 `points_account`、`points_rule`、`points_ledger`、`points_pending_order` 已创建，退款记录表增加 `request_id`/`eligible_goods_refund_amount` 并移除旧的订单唯一索引；检查所有新唯一索引与每 10 元 1 分规则。核对回填范围只含订单状态 2/3/4、已支付、有价格快照的订单，完成历史不追溯。
6. 在隔离测试环境验证真实 MySQL 行锁、唯一键、事务回滚和退款回调重放；以微信沙箱/经批准的测试支付商户验证部分退款，不得在本次开发验证中发起真实资金退款。核对账户余额等于流水变更累计值。未完成这些检查前 Phase 1 不能视为投产完成，不进入 Phase 2。

## 13. 本次审计文件范围

本次审计重点查看了以下真实源码/SQL（路径均相对仓库根目录）：

- 身份和配置：`backed/sky-pojo/src/main/java/com/sky/entity/User.java`、`backed/sky-common/src/main/java/com/sky/context/BaseContext.java`、`backed/sky-server/src/main/java/com/sky/interceptor/JwtTokenUserInterceptor.java`、`JwtTokenAdminInterceptor.java`、`backed/sky-server/src/main/java/com/sky/config/WebMvcConfiguration.java`。
- 订单与价格：`backed/sky-server/src/main/java/com/sky/service/impl/OrderServiceImpl.java`、`backed/sky-server/src/main/java/com/sky/service/CouponOrderPricingService.java`、`CouponDiscountStrategy.java`、`backed/sky-server/src/main/java/com/sky/service/strategy/` 下满减/折扣/无门槛策略、`backed/sky-pojo/src/main/java/com/sky/dto/OrdersSubmitDTO.java`、`backed/sky-pojo/src/main/java/com/sky/entity/Orders.java`、`backed/sky-server/src/main/resources/mapper/OrderMapper.xml`。
- 券模板/用户券/接口：`backed/sky-server/src/main/java/com/sky/service/impl/CouponServiceImpl.java`、`CouponClaimPersistenceService.java`、`backed/sky-server/src/main/java/com/sky/controller/admin/CouponController.java`、`backed/sky-server/src/main/java/com/sky/controller/user/UserCouponController.java`、`backed/sky-pojo/src/main/java/com/sky/dto/CouponTemplateDTO.java`、`CouponActivityDTO.java`、`CouponClaimDTO.java`。
- 抢券并发与异步：`backed/sky-server/src/main/java/com/sky/service/CouponClaimRedisService.java`、`backed/sky-server/src/main/resources/lua/coupon_claim_reserve.lua`、`backed/sky-server/src/main/java/com/sky/listener/CouponClaimListener.java`、`backed/sky-server/src/main/java/com/sky/task/CouponClaimOutboxDispatcher.java`、`CouponClaimReconciliationTask.java`、`backed/sky-server/src/main/java/com/sky/config/RabbitMQConfiguration.java`。
- 数据库与测试：`backed/sky-server/src/main/resources/db/migration-coupon-phase1.sql`、`migration-coupon-phase2.sql`、`migration-coupon-phase7.sql` 及 phase 3/4/6；`backed/sky-server/src/test/java/com/sky/CouponOrderPricingServiceTest.java`、`CouponClaimPersistenceServiceTest.java`、`CouponClaimReconciliationTaskTest.java`、`OrderCouponLifecycleTest.java`。
- 前端：`admin-web/src/views/coupon/index.vue`、`user-web/src/views/coupon/index.vue`、`user-web/src/views/order/index.vue`、`user-web/src/api/request.ts`、用户端 coupon/order API 模块、`user-web/src/views/my/index.vue`。

第一阶段审计时未执行 SQL、迁移、构建或测试。第二阶段新增的实现文件与测试结果以当前工作区和交付汇报为准；生产/目标数据库迁移仍未执行，必须按上述检查人工执行。
