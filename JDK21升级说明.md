# 苍穹外卖后端 JDK 21 + Spring Boot 3 升级说明

> 升级范围：`D:\苍穹外卖\backed`（Maven 多模块：sky-common / sky-pojo / sky-server）
> 升级前：JDK 17 + Spring Boot 2.7.3 + springfox(Swagger2) + jjwt 0.9.1 + commons-lang 2.x
> 升级后：JDK 21 + Spring Boot 3.3.5 + springdoc-openapi 2.3.0 + knife4j 4.5.0 + jjwt 0.12.3 + commons-lang3

---

## 一、版本矩阵

| 组件 | 升级前 | 升级后 | 说明 |
| --- | --- | --- | --- |
| JDK | 17 | **21 (LTS)** | 项目启用虚拟线程的前提 |
| Spring Boot | 2.7.3 | **3.3.5** | 基于 Spring Framework 6.1，最低 JDK 17 |
| 命名空间 | `javax.*` | **`jakarta.*`** | Servlet / WebSocket 全部迁移 |
| 接口文档 | springfox 3.0.0 + knife4j 3.0.2 | **springdoc-openapi 2.3.0 + knife4j 4.5.0（jakarta 版）** | springfox 已停止维护且不兼容 Spring 6 |
| Swagger 注解 | `io.swagger.annotations.*`（Swagger2） | **`io.swagger.v3.oas.annotations.*`（OpenAPI3）** | `@Api→@Tag`、`@ApiOperation→@Operation`、`@ApiModelProperty→@Schema` |
| MyBatis Starter | 2.2.0 | **3.0.5** | MyBatis 3.5.19 + mybatis-spring 3.0.5 |
| PageHelper | 1.3.0 | **2.1.1** | 1.x 依赖 Spring Boot 2 自动配置，Boot 3 下不生效 |
| Druid | `druid-spring-boot-starter` 1.2.1 | **`druid-spring-boot-3-starter` 1.2.28** | 旧坐标用 `spring.factories` 注册自动配置，Boot 3 只读 `AutoConfiguration.imports`，不改坐标会导致数据源不生效 |
| JWT | jjwt 0.9.1（单体包，依赖 `javax.xml.bind`） | **jjwt-api / jjwt-impl / jjwt-jackson 0.12.3** | 0.9.1 的 `DatatypeConverter` 在 JDK11+ 已移除 |
| JSON | fastjson 1.2.76 | **fastjson 1.2.83** | 修复 JDK 17+ 反射访问 `sun.misc.Unsafe` 的兼容问题 |
| 工具库 | commons-lang 2.6 | **commons-lang3 3.17.0** | `org.apache.commons.lang` → `org.apache.commons.lang3` |
| Excel | POI 3.16 | **POI 5.2.3** | 3.16 在 JDK17+ 下反射访问 JDK 内部类会报错 |
| 数据库驱动 | `mysql:mysql-connector-java` | **`com.mysql:mysql-connector-j`** | Boot 3 官方坐标变更 |
| 阿里云 OSS | 3.10.2 | **3.17.4** | 剔除旧版 `javax.servlet` / `jaxb` 传递依赖 |

被移除的依赖：`jaxb-api`（jjwt 0.12 与 Jakarta EE 10 都不再需要）、`mapstruct`（项目未实际使用）、手工指定的 `jackson-databind 2.9.2`（改由 Boot BOM 统一管理 2.19.x）、`aspectj`/`lombok` 的硬编码版本（改为 BOM 管理）。

---

## 二、虚拟线程接入情况

| 位置 | 是否使用虚拟线程 | 配置 |
| --- | --- | --- |
| Tomcat 请求处理线程 | ✅ | `spring.threads.virtual.enabled=true`（Boot 3.2+ 会把 Tomcat 的 `ProtocolHandler` 换成虚拟线程执行器） |
| `@Async` 异步任务 | ✅ | `AsyncConfiguration#taskExecutor`（`SimpleAsyncTaskExecutorBuilder.virtualThreads(true)`） |
| RabbitMQ 消费者 | ✅ | `spring.rabbitmq.listener.simple.virtual-threads=true` |
| `@Scheduled` 定时任务 | ❌（保持平台线程） | Spring 6.1 的 `TaskScheduler` 未适配虚拟线程；本项目定时任务只有凌晨一次批量兜底，非并发瓶颈，无需改造 |
| WebSocket 会话线程 | ⚠️ 仍为 Tomcat 平台线程 | Tomcat 10.1 的 WebSocket 容器不使用请求执行器；已把会话容器改为 `ConcurrentHashMap` 保证并发安全 |

配套改动：

1. `AsyncConfiguration` 注册了 `BaseContextTaskDecorator`，把调用方线程 `BaseContext`（ThreadLocal 保存的登录用户 id）传递给虚拟线程并在任务结束后清理，避免 `@Async` 方法内 `BaseContext.getCurrentId()` 取不到值、或 ThreadLocal 泄漏。
2. `WebSocketServer` 的会话容器 `HashMap` → `ConcurrentHashMap`（原实现在并发建连/断连时可能出现数据丢失甚至链表成环）。
3. `application.yml` 增加连接队列与消费者并发配置（`server.tomcat.accept-count`、`spring.task.execution.simple.concurrency-limit`、`spring.rabbitmq.listener.simple.concurrency/max-concurrency/prefetch`）。

> ⚠️ JDK 21 的虚拟线程遇到 `synchronized` 阻塞会发生 **pinning（载体线程被占用）**，JEP 491 到 JDK 24 才解决。业务代码里请用 `ReentrantLock` 替代长阻塞的 `synchronized`；本项目现有业务代码没有自定义 `synchronized` 块，因此不受影响。

---

## 三、过程中必须处理的兼容问题

1. **Druid 自动配置失效**：`druid-spring-boot-starter` 1.2.23 仍通过 `META-INF/spring.factories` 注册，Spring Boot 3 不再读取该文件 → 表现为 “Failed to determine a suitable driver class” → 必须换成 `druid-spring-boot-3-starter`。
2. **knife4j 与 springdoc 版本强绑定**：knife4j 4.5.0 是按 springdoc 2.3.0 编译的，会调用 `SpringDocConfigProperties.getGroupConfigs()` 等方法。若把 springdoc 提升到 2.8.x（为了配 Boot 3.4/3.5），`/v3/api-docs` 会抛 `NoSuchMethodError` 返回 500。**因此 Spring Boot 固定为 3.3.5**（对应 Spring Framework 6.1），与 knife4j 4.5.0 + springdoc 2.3.0 形成自洽组合。
3. **`swagger-annotations-jakarta` 版本必须与 springdoc 传递的 `swagger-core-jakarta` 一致**，否则会抛 `NoClassDefFoundError: Schema$SchemaResolution`。
4. **自定义消息转换器会“吃掉”二进制响应**：原代码 `converters.add(0, new MappingJackson2HttpMessageConverter(...))` 把扩展过的 Jackson 转换器插在最前面，而 Spring 的 Jackson 转换器能处理 `byte[]`（输出 Base64）。springdoc 的 `/v3/api-docs` 恰好是 `byte[]` + `produces=application/json`，结果接口文档变成 Base64 字符串，knife4j 页面空白。
   → 改为**只替换已有 Jackson 转换器的 `ObjectMapper`**，不再新建并前插，日期格式（`LocalDateTime → yyyy-MM-dd HH:mm`）依旧生效，同时不影响文档/文件下载等二进制响应。
5. **`spring.main.allow-circular-references`**：Boot 2.4+ 默认关闭，原项目显式打开。本次已设置为 `false` 并验证容器能正常启动（项目实际不存在循环依赖），移除了这个隐患开关。
6. **jjwt 0.12 的密钥长度限制**：HS256 要求密钥 ≥ 256 位，而项目配置的 `itcast`（6 字节）、`lulumiao`（8 字节）不满足，直接升级会在签发 token 时抛 `WeakKeyException`。`JwtUtil` 已做兼容：密钥不足 32 字节时用 SHA-256 派生，**原 yml 配置无需修改**。
7. **配置项整理**：`application-dev.yml` 里的 `spring.rabbitmq.*` 统一挪到 `sky.rabbitmq.*`，由 `application.yml` 用占位符引用，和 `sky.datasource` / `sky.redis` 的写法保持一致。

---

## 四、如何运行

```bash
# 编译（要求 JDK 21，mvn -v 应显示 Java version: 21.x）
cd D:\苍穹外卖\backed
mvn clean package

# 启动
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar
```

- 接口文档（knife4j）：<http://localhost:8080/doc.html>
- OpenAPI 根文档：<http://localhost:8080/v3/api-docs>，分组文档：`/v3/api-docs/管理端接口`、`/v3/api-docs/用户端接口`
- 前端代理地址无需修改（管理端 8888 → `http://localhost:8080/admin`，用户端 Vite → `http://localhost:8080`）
- 依赖：MySQL 8（`sky_take_out`）、Redis、RabbitMQ；`application-dev.yml` 中的连接信息保持原样

> ❗ **本机 MySQL 当前没有 `sky_take_out` 库**（`show databases` 只有 campusconnect / interview 等），
> 需要先导入项目自带的 SQL 脚本建库建表，否则数据源无法建立连接。
>
> 课程资料里的建库脚本在：
> `D:\资料\苍穹外卖\1、黑马程序员Java项目《苍穹外卖》企业级开发实战\外卖后端资料\day01\数据库\sky.sql`
>
> ⚠️ 该文件本身是损坏的：它是一份 GBK 字节被当成 UTF-8 重新保存过的文件（属于不可逆的二次编码），
> 例如「管理员」被写成 9 个汉字乱码，用任何编码都无法还原。因此：
> - 表结构（列名/类型/索引）是完好的，可以直接拿来建表；
> - 脚本里的中文**数据**（员工姓名、分类名、菜品名）是乱码，需要从原始资料重新导出，或改成 GBK 编码另存后再导入；
> - 脚本里 `employee` 表的密码是明文 `123456`，而 `EmployeeServiceImpl` 是按 **MD5** 比对的，
>   所以要能登录必须把该行改成 `password = 'e10adc3949ba59abbe56e057f20f883e'`（即 `123456` 的 MD5）。

---

## 五、升级验证

新增 `sky-server/src/test/java/com/sky/VirtualThreadUpgradeTest.java`，**7 个用例全部通过**，且不依赖本机 MySQL/Redis/RabbitMQ（测试用 H2 内存库 + Mock 中间件）：

| 用例 | 验证内容 |
| --- | --- |
| `contextLoads` | Spring Boot 3 容器（jakarta + MyBatis 3 + Druid 3 starter）装配成功 |
| `openApiDocsAvailable` | `/v3/api-docs`、两个分组文档、`/doc.html` 均返回 200，且扫描到管理端/用户端接口 |
| `tomcatRequestRunsOnVirtualThread` | 请求处理线程 `Thread.currentThread().isVirtual() == true` |
| `asyncExecutorUsesVirtualThreads` | `@Async` 执行器线程为虚拟线程，且 `BaseContext` 成功跨线程传递 |
| `adminLoginStillWorks` | 管理端登录接口返回 `code=1` 与 jwt 令牌 |
| `jwtRoundTripWorks` | jjwt 0.12.3 用旧密钥（itcast）签发/解析成功 |
| `customJacksonDateFormatStillWorks` | `LocalDateTime` 仍按 `yyyy-MM-dd HH:mm` 输出 |

另外用打包后的 jar 做了真实启动冒烟验证：

```
Tomcat started on port 8080 (http) with context path '/'
Started SkyApplication in 11.526 seconds
GET /doc.html        -> 200 text/html
GET /v3/api-docs     -> 200 application/json (42886 bytes)
GET /v3/api-docs/管理端接口 -> 200 application/json (29760 bytes)
日志线程名：tomcat-handler-0..3（请求线程）、rabbit-simple-0..59（MQ 消费者） —— 均为虚拟线程
```

---

## 六、常见问题：admin / 123456 提示“密码错误”

**不是升级引入的问题**，而是数据库里存的是明文密码、程序里按 MD5 比对：

```java
// EmployeeServiceImpl.login()
String password = DigestUtils.md5DigestAsHex(employeeLoginDTO.getPassword().getBytes());
if (!password.equals(employee.getPassword())) {   // 用 MD5 值去比库里的值
    throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
}
```

课程原始 `sky.sql` 里 `employee` 表插入的是明文 `'123456'`（只有 6 位），
而 `md5("123456")` = `e10adc3949ba59abbe56e057f20f883e`（32 位），两者永远不相等，
所以“查到了用户但提示密码错误”。新增员工时代码会自动写成 MD5，因此后续新增的员工不受影响。

修复方式（任选其一）：

```sql
-- 方式 1：直接改库（推荐）
update sky_take_out.employee set password = MD5('123456') where username = 'admin';

-- 方式 2：已知哈希值直接写入
update sky_take_out.employee
set password = 'e10adc3949ba59abbe56e057f20f883e'
where username = 'admin';
```

校验：

```sql
select username, password, password = MD5('123456') as ok from sky_take_out.employee;
```

`ok = 1` 即修复成功。

---

## 七、后续可选优化（未在本次改动中实施）

1. `BaseContext` 用 `ThreadLocal` 保存登录用户 id，在 WebSocket 等非请求线程场景需手工传递；如需更彻底的方案可换成 `TransmittableThreadLocal` 或改为显式参数传递。
2. WebSocket 若需要支撑海量长连接，可把 Tomcat 的 WebSocket 容器或整条链路换成 Netty / Spring WebFlux。
3. `WeChatPayUtil` 中 `BigDecimal.ROUND_HALF_UP` 自 Java 9 起废弃，可改为 `RoundingMode.HALF_UP`（仅告警，不影响功能）。
4. 升级到 Spring Boot 3.4/3.5 需要同时把接口文档方案从 knife4j 换成 springdoc 原生 swagger-ui（或等待 knife4j 发布适配版本）。
5. 密码仍使用 MD5 存储（代码中已有 TODO），建议迁移到 BCrypt。
