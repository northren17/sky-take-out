package com.sky;

import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDK21 + Spring Boot 3 升级后的启动与虚拟线程验证测试。
 * <p>
 * 验证内容：
 * <ol>
 *     <li>Spring Boot 3 容器可以正常启动（jakarta 命名空间、springdoc、MyBatis 3、Druid 全部生效）；</li>
 *     <li>springdoc-openapi（knife4j）接口文档可用，管理端/用户端两个分组都被扫描到；</li>
 *     <li>虚拟线程真正生效：Tomcat 请求处理线程与 @Async 执行器线程都是虚拟线程；</li>
 *     <li>原有业务链路可用：管理端员工登录接口正常返回 jwt token（jjwt 0.12.x + 旧密钥）。</li>
 * </ol>
 * 说明：测试使用 test profile，数据源为 H2 内存库（含最小表结构与 admin 账号），
 * Redis / RabbitMQ 均以 Mock 替代，因此整套验证不依赖任何本机中间件，可在任意环境重复执行。
 */
@SpringBootTest(
        classes = {SkyApplication.class, VirtualThreadUpgradeTest.ProbeController.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 排除 RabbitMQ 连接自动配置与 Redis 响应式自动配置，避免测试环境缺少中间件时启动失败；
                // RabbitAnnotationDrivenConfiguration 不是自动配置类，无法在此排除，
                // 因此额外用 @MockBean 提供 ConnectionFactory，并让监听容器不自动启动。
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@ActiveProfiles("test")
@Slf4j
class VirtualThreadUpgradeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AsyncTaskExecutor taskExecutor;

    /** Redis 连接工厂用 Mock 替代，避免测试依赖本机 Redis */
    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    /** RabbitMQ 连接工厂与模板用 Mock 替代，避免测试依赖本机 RabbitMQ */
    @MockBean
    private org.springframework.amqp.rabbit.connection.ConnectionFactory amqpConnectionFactory;

    @MockBean
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Test
    @DisplayName("容器启动成功：Spring Boot 3 + jakarta + MyBatis3 + Druid 全部装配完成")
    void contextLoads() {
        assertThat(port).isPositive();
    }

    @Test
    @DisplayName("springdoc-openapi 接口文档可用，且管理端/用户端两个分组都被扫描")
    void openApiDocsAvailable() {
        // 根文档：确认 springdoc 本身工作正常
        ResponseEntity<String> root = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(root.getStatusCode().is2xxSuccessful())
                .as("根接口文档应返回 200，实际 body=%s", root.getBody())
                .isTrue();
        assertThat(root.getBody()).contains("苍穹外卖项目接口文档");

        // knife4j 页面访问 springdoc 的分组接口
        ResponseEntity<String> admin = restTemplate.getForEntity("/v3/api-docs/管理端接口", String.class);
        assertThat(admin.getStatusCode().is2xxSuccessful())
                .as("管理端分组文档应返回 200，实际 body=%s", admin.getBody())
                .isTrue();
        assertThat(admin.getBody()).contains("/admin/employee/login");

        ResponseEntity<String> user = restTemplate.getForEntity("/v3/api-docs/用户端接口", String.class);
        assertThat(user.getStatusCode().is2xxSuccessful())
                .as("用户端分组文档应返回 200，实际 body=%s", user.getBody())
                .isTrue();
        assertThat(user.getBody()).contains("/user/user/login");

        // knife4j 文档页面（静态资源）可访问
        ResponseEntity<String> docHtml = restTemplate.getForEntity("/doc.html", String.class);
        assertThat(docHtml.getStatusCode().is2xxSuccessful())
                .as("/doc.html 应可访问，实际状态=%s", docHtml.getStatusCode())
                .isTrue();
    }

    @Test
    @DisplayName("虚拟线程生效：Tomcat 请求处理线程是虚拟线程")
    void tomcatRequestRunsOnVirtualThread() {
        String threadInfo = restTemplate.getForObject("/test/virtual-thread-probe", String.class);
        log.info("请求处理线程信息：{}", threadInfo);
        assertThat(threadInfo)
                .as("Tomcat 请求处理线程应为虚拟线程")
                .contains("virtual=true");
    }

    @Test
    @DisplayName("虚拟线程生效：@Async 执行器使用虚拟线程，且 BaseContext 能跨线程传递")
    void asyncExecutorUsesVirtualThreads() throws Exception {
        BaseContext.setCurrentId(9527L);
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            taskExecutor.execute(() -> future.complete(
                    "virtual=" + Thread.currentThread().isVirtual()
                            + ",name=" + Thread.currentThread().getName()
                            + ",userId=" + BaseContext.getCurrentId()));
            String result = future.get(10, TimeUnit.SECONDS);
            log.info("异步任务线程信息：{}", result);
            assertThat(result).contains("virtual=true").contains("userId=9527");
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    @Test
    @DisplayName("业务链路可用：管理端员工登录接口正常返回 jwt token")
    void adminLoginStillWorks() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"admin\",\"password\":\"123456\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/employee/login", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        log.info("登录响应：status={}, body={}", response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .as("登录接口应返回 code=1、用户名与 jwt 令牌")
                .contains("\"code\":1")
                .contains("admin")
                .contains("token");
    }

    @Test
    @DisplayName("jwt 工具类可用：签发后能正常解析出用户 id（jjwt 0.12.3）")
    void jwtRoundTripWorks() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, 1L);
        String token = JwtUtil.createJWT("itcast", 7200000L, claims);
        assertThat(token).isNotBlank();

        Claims parsed = JwtUtil.parseJWT("itcast", token);
        assertThat(parsed.get(JwtClaimsConstant.EMP_ID, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("日期格式仍生效：LocalDateTime 按自定义 JacksonObjectMapper 输出 yyyy-MM-dd HH:mm")
    void customJacksonDateFormatStillWorks() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String loginBody = "{\"username\":\"admin\",\"password\":\"123456\"}";
        ResponseEntity<String> login = restTemplate.exchange(
                "/admin/employee/login", HttpMethod.POST, new HttpEntity<>(loginBody, headers), String.class);
        String token = com.alibaba.fastjson.JSON.parseObject(login.getBody())
                .getJSONObject("data").getString("token");

        headers.set("token", token);
        ResponseEntity<String> employee = restTemplate.exchange(
                "/admin/employee/1", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        log.info("员工详情响应：{}", employee.getBody());
        assertThat(employee.getStatusCode().is2xxSuccessful()).isTrue();
        // 自定义日期格式 yyyy-MM-dd HH:mm（不含秒），且没有被序列化成时间戳数组
        assertThat(employee.getBody())
                .as("LocalDateTime 应按 yyyy-MM-dd HH:mm 输出")
                .containsPattern("\"createTime\":\"\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\"");
    }

    /**
     * 测试专用探针接口：返回当前处理请求的线程信息
     */
    @RestController
    static class ProbeController {

        @GetMapping("/test/virtual-thread-probe")
        public String probe() {
            Thread current = Thread.currentThread();
            return "virtual=" + current.isVirtual() + ",name=" + current.getName();
        }
    }
}
