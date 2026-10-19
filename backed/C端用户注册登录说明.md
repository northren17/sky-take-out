# C端用户注册 / 登录 / JWT 鉴权 / 验证码 / Redis 会话

> 新增功能，**完全不影响**原管理端（employee）逻辑：员工端的接口、拦截器、MD5 密码校验、白名单全部保持原样。

---

## 一、功能清单

| 接口 | 方法 | 是否需要登录 | 说明 |
| --- | --- | --- | --- |
| `/user/user/captcha` | GET | 否 | 获取图形验证码，返回 `captchaKey` + Base64 图片 + 有效期 |
| `/user/user/register` | POST | 否 | 账号注册，成功后**直接返回 token**（前端无需再登录一次） |
| `/user/user/loginByPassword` | POST | 否 | 账号密码登录 |
| `/user/user/login` | POST | 否 | 微信登录（**原有接口，未改动语义**，入参仍是 `code`） |
| `/user/user/logout` | POST | 是 | 退出登录，删除 Redis 中的令牌使其立即失效 |
| `/user/user/info` | GET | 是 | 查询当前登录用户信息（不返回密码） |

---

## 二、关键设计

### 1. 账号存哪一列
`user` 表新增两列（DDL 见 `sky-server/src/main/resources/db/migration-user-auth.sql`）：

```sql
ALTER TABLE `user` ADD COLUMN `password` varchar(100) COMMENT '登录密码（BCrypt）' AFTER `openid`;
ALTER TABLE `user` ADD COLUMN `username` varchar(45)  COMMENT '登录账号'          AFTER `password`;
ALTER TABLE `user` ADD UNIQUE KEY `uk_username` (`username`);
```

网页版注册的用户写 `username`，`openid` 保持 `NULL`；微信登录的用户 `openid` 有值、`username` 为 `NULL`。
两者通过唯一索引 `uk_username` 共存（MySQL 唯一索引允许多个 NULL，因此不影响微信用户）。

### 2. 密码加密：BCrypt（仅用户端）
- 新增依赖 `org.springframework.security:spring-security-crypto`（**只引入密码编码器，不引入 Security 过滤器链**，不会改变现有鉴权方式）。
- `EmployeeServiceImpl` 仍然使用 `org.springframework.util.DigestUtils` 的 MD5，**一行未改**。
- 两套算法不会互相干扰：BCrypt 密文是 60 字符、以 `$2a$10$` 开头；MD5 是 32 位十六进制。
- 已确认 `spring-security-crypto` 里**不存在** `org.springframework.util.DigestUtils`，不会造成类冲突。
- BCrypt 自带随机盐，同一密码每次密文都不同，因此只能用它自己的 `matches()` 校验。

### 3. 验证码
- 纯 JDK 实现（`java.awt` + `ImageIO`），不引入第三方验证码库；字符集去掉了 `0/O`、`1/I/L` 等易混字符。
- 答案存在 Redis：`captcha:{captchaKey}`，TTL **120 秒**；前端只拿到 `captchaKey` 和图片，**拿不到答案**。
- **一次性**：校验时「先取后删」，无论对错都作废，防止同一验证码被暴力重试。
- `CaptchaUtil` 不依赖本地字体文件，headless（无桌面环境 / Docker / Linux 服务器）下也能出图，已用测试覆盖。

### 4. JWT + Redis 会话
JWT 本身是自包含的，只要签名没过期就无法作废。为了支持「退出登录 / 强制下线」，登录时把令牌同时写入 Redis：

```
key   = user:token:{userId}
value = jwt 字符串
TTL   = sky.jwt.user-ttl（与 jwt 过期时间一致）
```

`JwtTokenUserInterceptor`（**只拦截 `/user/**`**）现在做两步校验：

1. 校验 jwt 签名与过期时间；
2. 比对 Redis 中的令牌，**不存在或不一致一律返回 401**。

效果：退出登录（删除 key）或异地重新登录（覆盖 key）后，旧令牌立刻失效。
另外顺手修了一个隐患：拦截器补上了 `afterCompletion` 清理 `BaseContext`，避免线程复用（虚拟线程下尤其重要）导致用户 id 串号。

### 5. 参数校验
项目没有引入 `spring-boot-starter-validation`（避免新增校验框架，且校验失败会返回 400 而不是项目约定的 `{code:0,msg}`）。
因此统一在 Service 里手工校验，失败抛 `BaseException` 子类，由既有 `GlobalExceptionHandler` 转成 `{code:0,msg:"..."}`。

### 6. 新增异常与常量
| 类 | 说明 |
| --- | --- |
| `RegisterFailedException` | 注册失败（账号为空/长度不合法/两次密码不一致/账号已存在） |
| `CaptchaErrorException` | 验证码错误或已过期 |
| `RedisKeyConstant` | Redis key 前缀与验证码 TTL |

`MessageConstant` 新增：`USERNAME_ALREADY_EXISTS`、`USERNAME_IS_NULL`、`PASSWORD_IS_NULL`、`PASSWORD_NOT_MATCH`、`USERNAME_LENGTH_ERROR`、`PASSWORD_LENGTH_ERROR`、`CAPTCHA_IS_NULL`、`CAPTCHA_ERROR`、`REGISTER_FAILED`。

---

## 三、真实环境端到端验证结果

环境：真实 MySQL（`sky_take_out`）+ 真实 Redis（本机 6379，db 10）。

```
(1) 获取验证码
    captchaKey = 52d743cf725044899abb8ed3ce25078d
    Redis 中的答案 = CNFA（前端拿不到，测试脚本用 Jedis 直读 Redis 模拟"人眼看图"）
    图片 Base64 长度 = 4276

(2) 注册
    {"code":1,"data":{"id":4,"token":"eyJhbGciOiJIUzI1NiJ9...","username":"e2e_user_69334","name":"端到端用户"}}

(3) 验证码一次性
    第一次（正确验证码）=> code=1
    重放同一 captchaKey  => {"code":0,"msg":"验证码错误或已过期"}      ✅

(4) 未带令牌访问 /user/user/info  =>  HTTP 401                        ✅

(5) 带令牌访问 /user/user/info
    {"code":1,"data":{"id":4,"username":"e2e_user_69334","name":"端到端用户",
     "phone":"13900001111","createTime":"2026-10-16 20:17"}}          ✅ 且不含密码字段

(6) 查库确认密码为 BCrypt
    id  username         name        phone        pwd_prefix  pwd_len  openid
    4   e2e_user_69334   端到端用户   13900001111  $2a$10$     60       (NULL)   ✅

(7) 退出登录 => {"code":1}
(8) 退出后同一令牌 => HTTP 401                                        ✅ Redis 会话生效

(9) 重新登录 => 新 token 访问 info => HTTP 200                        ✅

(10) 错误分支
     错误密码        => {"code":0,"msg":"密码错误"}
     错误验证码      => {"code":0,"msg":"验证码错误或已过期"}
     重复账号        => {"code":0,"msg":"该账号已被注册"}
     两次密码不一致  => {"code":0,"msg":"两次输入的密码不一致"}
     账号不存在      => {"code":0,"msg":"账号不存在"}                 ✅

(11) 管理端未受影响
     admin 登录 => {"code":1,"data":{"id":1,"userName":"admin","name":"管理员","token":"..."}}  ✅
```

自动化测试 `mvn test`：**19 个用例全部通过**

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `UserAuthTest` | 8 | 401 拦截、注册、重复账号、两次密码不一致、登录、密码错误、白名单、退出后令牌失效 |
| `CaptchaUtilTest` | 4 | 验证码格式、随机性、PNG 合法性、headless 出图 |
| `VirtualThreadUpgradeTest` | 7 | 升级回归（虚拟线程、接口文档、员工登录等） |

---

## 四、前端改动（用户端网页版）

| 文件 | 改动 |
| --- | --- |
| `src/api/modules/user.ts` | 新增 `getCaptcha` / `userRegister` / `userLoginByPassword` / `getUserInfo`；保留 `userLogin`(微信) 与 `userLogout` |
| `src/stores/user.ts` | 新增登录账号持久化、`clearSession()`、**异步 `logout()`**（先调后端注销 Redis 令牌再清本地） |
| `src/views/login/index.vue` | 由"手工粘贴 JWT"改为**登录 / 注册双 Tab 表单**，含图形验证码（点击图片刷新、失败自动换一张）；微信链路保留 |
| `src/views/my/index.vue` | 昵称优先显示账号/注册昵称，进入页面拉取 `/user/user/info`，退出改为异步调用后端 |
| `src/api/request.ts` | 401 处理由 `logout()` 改为 `clearSession()`（令牌已失效时再调 /logout 只会又拿一个 401） |

验证：`vue-tsc --noEmit` 通过、`eslint --max-warnings 0` 通过、`vite build` 通过。

> 说明：`request.ts` 里请求头用的是小写 `authentication`（与后端 `sky.jwt.user-token-name` 一致）；
> 拦截器同时兼容 `Authorization: Bearer xxx` 写法，方便用 curl/Postman 调试。

---

## 五、如何使用

```bash
# 1) 建库列（若换环境需要执行一次）
mysql -uroot -p sky_take_out < sky-server/src/main/resources/db/migration-user-auth.sql

# 2) 启动后端（需要 MySQL + Redis）
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar

# 3) 启动用户端网页版
cd user-web && pnpm dev
```

浏览器打开后：注册一个账号 → 自动登录 → 个人中心可看到账号名 → 点「退出登录」→ 令牌立即失效。
接口文档：<http://localhost:8080/doc.html>（用户端接口分组下可见新接口）。
