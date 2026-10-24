# 套餐、预约与评价模块说明

数据库脚本位于 `sky-server/src/main/resources/db/migration-meal-reservation-review.sql`。请在部署前备份数据库，并对已存在的 `setmeal` 表执行一次迁移；套餐库存初始值为 100，`-1` 表示不限库存。项目已有 `setmeal`、`setmeal_dish`、`orders` 和 `order_detail`，本次只新增预约、评价及其必要明细表。

所有接口使用项目现有的 `{ "code": 1, "data": ... }` 响应包装。`/user/**` 必须携带用户 token，`/admin/**` 必须携带管理员 token。金额单位为元。

## 前端页面

- `user-web/src/views/meal/index.vue`：套餐分类、套餐组成、加入购物车和库存显示。
- `user-web/src/views/reservation/index.vue`：选择未来日期／午晚餐、加入菜品或套餐、提交及取消预约。
- `user-web/src/views/review/index.vue`：待评价订单、整体与菜品评分、上传晒图、历史评价。
- 首页菜品详情同时显示最近评价；历史订单页新增“预约订单”标签。
- 套餐购买继续使用现有购物车与即时订单结算；预约则使用独立预约单，不会伪装成即时订单。

## REST API

### 套餐专区（已有接口扩充库存字段）

| 方法 | URL | 入参 | 说明 |
|---|---|---|---|
| GET | `/user/dish/category` | 无 | 查询分类；`type=2` 为套餐分类 |
| GET | `/user/setmeal/list?categoryId=1` | 分类 ID | 已售套餐，返回新增 `stock` 字段 |
| GET | `/user/setmeal/dish/{setmealId}` | 路径 ID | 套餐菜品和份数，新增 `dishId` |
| POST | `/user/shoppingCart/add` | `{"setmealId":1}` | 复用原购物车加购 |

示例：`{"code":1,"data":[{"id":1,"name":"营养套餐","price":28.00,"stock":12}]}`

### 预约点餐

| 方法 | URL | 权限 | 入参／用途 |
|---|---|---|---|
| POST | `/user/reservation` | 用户 | 创建预约，JSON 如下 |
| GET | `/user/reservation/mine?page=1&pageSize=20` | 用户 | 当前用户预约记录及商品明细 |
| GET | `/user/reservation/capacity?date=2026-10-22&period=1` | 用户 | 查询开放状态和该日期时段限额 |
| PUT | `/user/reservation/{id}/cancel` | 用户 | 取消本人且仍可取消的预约 |
| GET | `/admin/reservation/page?page=1&pageSize=20` | 管理员 | 预约管理列表 |
| PUT | `/admin/reservation/capacity` | 管理员 | 设置日期时段限额／开放状态 |
| PUT | `/admin/reservation/{id}/picked-up` | 管理员 | 标记已取餐 |

创建请求：

```json
{
  "requestId": "前端生成的UUID",
  "mealDate": "2026-10-22",
  "mealPeriod": 1,
  "remark": "少辣",
  "items": [
    {"itemType": 1, "itemId": 10, "quantity": 1},
    {"itemType": 2, "itemId": 4, "quantity": 2}
  ]
}
```

`itemType`：1 菜品、2 套餐；`mealPeriod`：1 午餐、2 晚餐。成功返回：

```json
{"code":1,"data":{"id":31,"reservationNo":"YY...","status":0,"totalAmount":68.00,"items":[]}}
```

限额配置请求：`{"mealDate":"2026-10-22","mealPeriod":1,"maxReservations":120,"enabled":1}`。默认未配置的日期／时段开放预约、限额 100 单。不能把限额调至低于当前有效预约数。

### 评价晒单

| 方法 | URL | 权限 | 入参／用途 |
|---|---|---|---|
| GET | `/user/review/pending?page=1&pageSize=10` | 用户 | 本人已完成且尚未评价的订单 |
| POST | `/user/review` | 用户 | 提交订单评分、菜品评分、评价文字和已上传图片 URL |
| GET | `/user/review/mine?page=1&pageSize=10` | 用户 | 本人的评价历史及图片／菜品评分 |
| GET | `/user/review/dish/{dishId}?page=1&pageSize=10` | 用户 | 某菜品的评价列表及图片 |
| POST | `/user/review/upload` | 用户 | multipart 表单字段 `file`，最大 5MB，JPG／PNG／WebP |

评价请求：

```json
{
  "orderId": 31,
  "rating": 5,
  "content": "菜品新鲜，出餐很快。",
  "images": ["https://example.invalid/review/uuid.jpg"],
  "items": [
    {"orderDetailId": 90, "dishId": 10, "rating": 5}
  ]
}
```

`items` 对套餐明细也可逐菜评分；服务端会校验菜品确实属于订单中的菜品或套餐。接口只允许本人已完成订单评价，每订单一次，评分范围 1–5，晒图最多 9 张。评价记录提交后不提供编辑／删除接口。

## Redis 使用

| Key | 内容 | TTL | 一致性／用途 |
|---|---|---|---|
| `setmeal:stock:{setmealId}` | 套餐剩余库存 | 5 分钟 | 仅用于读取展示；扣减／恢复以数据库带条件更新为准，提交事务后删除缓存 |
| `reservation:capacity:{yyyy-MM-dd}:{period}` | 当前有效预约单数 | 48 小时 | Redisson 同 key 分布式锁保护初始化与递增／递减；缓存过期后从有效预约记录重建 |
| `lock:reservation:capacity:{yyyy-MM-dd}:{period}` | Redisson 分布式锁 | 持锁期间 | 避免多实例并发超额预约；数据库事务回滚时通过事务回调补偿缓存计数 |
| `reservation:request:{userId}:{requestId}` | 创建请求处理中／预约号 | 2 分钟 | 同一用户 requestId 短时去重；事务回滚删除 key |
| `review:dish:{dishId}:1` | 热门菜品第一页评价 JSON | 5 分钟 | 仅缓存第一页；新评价成功提交后按被评分菜品精确清理 |
| `lock:review:order:{orderId}` | 订单评价分布式锁 | 持锁期间 | 序列化同订单并发提交，数据库唯一索引仍作为最终约束 |

Redis 不可用时，依赖 Redis 的限额、幂等与评价热点接口会明确失败，不静默跳过并发保护。套餐库存扣减由 SQL 原子条件更新保障。

## 状态与业务规则

- 套餐 `stock`：`-1` 不限量；`0` 售罄；正数表示剩余份数。管理端通过套餐编辑设置库存。
- 预约 `status`：`0` 待取餐 → `1` 已取餐；待取餐可取消后转 `2` 已取消；过期后转 `3` 超时未取。
- 预约仅可选明天及之后；午餐为 1，晚餐为 2。未配置限额默认 100 单；按预约单计数，不按商品件数。
- 当前默认取消截止：午餐日 11:30、晚餐日 17:30；未取餐自动过期时间：午餐日 13:30、晚餐日 20:00。后台任务每分钟检查过期预约，取消／过期时返还预约套餐库存及预约名额。正式运营前请按食堂实际供餐时间调整这些常量。
- 预约套餐与即时下单共用套餐库存；菜品不在本模块扣库存。套餐菜品组成和数量复用 `setmeal_dish.copies`。
- 订单 `status=5`（已完成）可评价；唯一订单评价索引限制重复提交。订单菜品评分写入独立明细表，晒单 URL 写入图片表。
- My Orders 里的“预约订单”读取独立预约记录并明确标记为预约，不改变既有即时订单 API 和状态定义。

## 部署与验证提示

1. 先运行 SQL 迁移，再发布后端和用户端。
2. 确认 Redis、MySQL 和项目既有 OSS 配置可用；用户评价图片复用项目 OSS 工具。
3. 预约取消和自动过期恢复库存，套餐库存的数据库扣减／恢复与预约主明细事务一起提交。
4. 管理端预约接口由现有 `/admin/**` 拦截器保护；管理端可通过 `/admin/reservation` 配置限额和标记取餐。
