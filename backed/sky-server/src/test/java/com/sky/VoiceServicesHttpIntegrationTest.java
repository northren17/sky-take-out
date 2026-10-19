package com.sky;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.constant.RedisKeyConstant;
import com.sky.service.VoiceAgentRevocationClient;
import com.sky.service.VoiceSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatusCode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=${VOICE_TEST_REDIS_PORT:16389}",
                "spring.data.redis.database=0"
        })
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "VOICE_TEST_REDIS_PORT", matches = "\\d+")
class VoiceServicesHttpIntegrationTest {
    private static int configuredPythonPort;

    @DynamicPropertySource
    static void configureAgentRevocationEndpoint(DynamicPropertyRegistry registry) {
        registry.add("VOICE_AGENT_INTERNAL_URL", () -> {
            if (configuredPythonPort == 0) {
                try {
                    configuredPythonPort = findAvailablePort();
                } catch (IOException ex) {
                    throw new IllegalStateException("Could not reserve Python test port", ex);
                }
            }
            return "http://127.0.0.1:" + configuredPythonPort;
        });
    }

    @LocalServerPort
    private int javaPort;

    @Autowired
    private VoiceSessionService voiceSessionService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ConnectionFactory amqpConnectionFactory;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    private Process pythonProcess;
    private int pythonPort;
    private Long userId;
    private String loginSessionId;

    private void startPythonAgent() throws Exception {
        String projectDirectory = requiredProperty("VOICE_AGENT_PROJECT_DIR");
        String pythonExecutable = requiredProperty("VOICE_AGENT_PYTHON");
        pythonPort = configuredPythonPort;

        Path bootstrap = Files.createTempFile("voice-agent-integration-server-", ".py");
        Files.writeString(bootstrap, """
                import asyncio
                import os
                import sys
                sys.path.insert(0, os.environ["VOICE_AGENT_PROJECT_DIR"])
                import uvicorn
                from agent import main

                class FakeVoiceSession:
                    def __init__(
                        self,
                        websocket,
                        settings,
                        *,
                        session_id=None,
                        business_client=None,
                        session_capability=None,
                    ):
                        self.websocket = websocket
                        self.session_id = session_id
                        self.business_client = business_client
                        self.session_capability = session_capability
                        self.stopped = asyncio.Event()

                    async def start(self):
                        await self.websocket.send_json({"type": "test.session.ready"})

                    async def wait_failure(self):
                        await self.stopped.wait()

                    async def receive_audio(self, audio):
                        raise AssertionError("test connection must not send audio")

                    async def interrupt(self):
                        return None

                    async def heartbeat(self):
                        await self.websocket.send_json({"type": "heartbeat.ack"})

                    async def close(self):
                        self.stopped.set()

                    async def report_server_error(self, message):
                        return None

                main.VoiceSession = FakeVoiceSession
                uvicorn.run(
                    main.app,
                    host="127.0.0.1",
                    port=int(os.environ["VOICE_TEST_PYTHON_PORT"]),
                    log_level="error",
                )
                """);
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable, bootstrap.toString());
        builder.directory(new java.io.File(projectDirectory));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("VOICE_AGENT_PROJECT_DIR", projectDirectory);
        builder.environment().put("VOICE_TEST_PYTHON_PORT", Integer.toString(pythonPort));
        builder.environment().put("AGENT_JAVA_INTERNAL_URL", "http://127.0.0.1:" + javaPort);
        pythonProcess = builder.start();
        bootstrap.toFile().deleteOnExit();
    }

    @AfterEach
    void stopPythonAgent() {
        if (pythonProcess != null) {
            pythonProcess.destroy();
            try {
                if (!pythonProcess.waitFor(5, TimeUnit.SECONDS)) {
                    pythonProcess.destroyForcibly();
                    pythonProcess.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                pythonProcess.destroyForcibly();
            }
            pythonProcess = null;
        }
    }

    @BeforeEach
    void seedIsolatedRedisLoginSession() {
        redisTemplate.getConnectionFactory().getConnection()
                .serverCommands().flushDb();
        userId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        loginSessionId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                RedisKeyConstant.USER_TOKEN + userId, "integration-jwt", Duration.ofMinutes(2));
        redisTemplate.opsForValue().set(
                RedisKeyConstant.USER_SESSION + userId, loginSessionId, Duration.ofMinutes(2));
    }

    @Test
    void pythonClientRedeemsAndValidatesTicketThroughJavaHttpAndJavaRevokesPythonOverHttp()
            throws Exception {
        startPythonAgent();
        awaitPythonHealth();
        String ticket = voiceSessionService.issueTicket(userId, loginSessionId).getTicket();
        String sessionId = UUID.randomUUID().toString();

        Path pythonScript = Files.createTempFile("voice-agent-http-client-", ".py");
        Files.writeString(pythonScript, """
                import asyncio
                import os
                import sys
                sys.path.insert(0, os.environ["VOICE_AGENT_PROJECT_DIR"])
                from agent.internal_auth import JavaInternalClient
                from agent.tools.java_business import create_java_business_tools

                async def main():
                    client = JavaInternalClient(
                        os.environ["AGENT_JAVA_INTERNAL_URL"],
                        os.environ["AGENT_PYTHON_HMAC_SECRET"],
                    )
                    grant = await client.redeem(
                        os.environ["VOICE_TEST_TICKET"],
                        os.environ["VOICE_TEST_SESSION_ID"],
                    )
                    print("redeemed")
                    result = await client.validate(
                        os.environ["VOICE_TEST_SESSION_ID"],
                        grant["sessionCapability"],
                    )
                    if result.get("valid") is not True:
                        raise RuntimeError("Java did not validate the redeemed session")
                    print("validated")
                    await client.close(
                        os.environ["VOICE_TEST_SESSION_ID"],
                        grant["sessionCapability"],
                    )
                    print("python-client-ok")

                asyncio.run(main())
                """);
        try {
            ProcessBuilder pythonClient = new ProcessBuilder(
                    requiredProperty("VOICE_AGENT_PYTHON"), pythonScript.toString());
            pythonClient.directory(new java.io.File(requiredProperty("VOICE_AGENT_PROJECT_DIR")));
            pythonClient.redirectErrorStream(true);
            pythonClient.environment().put(
                    "AGENT_JAVA_INTERNAL_URL", "http://127.0.0.1:" + javaPort);
            pythonClient.environment().put(
                    "VOICE_AGENT_PROJECT_DIR", requiredProperty("VOICE_AGENT_PROJECT_DIR"));
            pythonClient.environment().put("VOICE_TEST_TICKET", ticket);
            pythonClient.environment().put("VOICE_TEST_SESSION_ID", sessionId);
            Process pythonCall = pythonClient.start();
            boolean exited = pythonCall.waitFor(20, TimeUnit.SECONDS);
            if (!exited) pythonCall.destroyForcibly();
            byte[] output = pythonCall.getInputStream().readAllBytes();
            assertThat(exited).isTrue();
            assertThat(pythonCall.exitValue())
                    .as("Python JavaInternalClient should redeem, validate, and close: %s",
                            new String(output, StandardCharsets.UTF_8))
                    .isZero();
            assertThat(new String(output, StandardCharsets.UTF_8)).contains("python-client-ok");
        } finally {
            Files.deleteIfExists(pythonScript);
        }

        assertThatThrownByValidation(sessionId);

        String pythonBaseUrl = "http://127.0.0.1:"
                + pythonPort;
        String javaToPythonSecret = requiredEnvironment("SKY_AGENT_JAVA_HMAC_SECRET");
        new VoiceAgentRevocationClient(
                new ObjectMapper(), pythonBaseUrl, javaToPythonSecret)
                .revokeSessions(java.util.List.of(UUID.randomUUID().toString()));
    }

    @Test
    void javaLogoutClosesAnAuthenticatedLiveWebSocket() throws Exception {
        startPythonAgent();
        awaitPythonHealth();
        String ticket = voiceSessionService.issueTicket(userId, loginSessionId).getTicket();
        Path clientScript = Files.createTempFile("voice-agent-websocket-client-", ".py");
        Files.writeString(clientScript, """
                import asyncio
                import json
                import os
                import sys
                import websockets

                async def main():
                    url = "ws://127.0.0.1:" + os.environ["VOICE_TEST_PYTHON_PORT"] + "/ws/voice"
                    async with websockets.connect(
                        url, origin="http://localhost:5173", open_timeout=5
                    ) as websocket:
                        await websocket.send(json.dumps({
                            "type": "auth",
                            "ticket": os.environ["VOICE_TEST_TICKET"],
                        }))
                        ready = await asyncio.wait_for(websocket.recv(), timeout=10)
                        if json.loads(ready).get("type") != "test.session.ready":
                            raise RuntimeError("Voice session did not authenticate")
                        print("WS_AUTHENTICATED", flush=True)
                        try:
                            await asyncio.wait_for(websocket.recv(), timeout=10)
                        except websockets.ConnectionClosed as closed:
                            if closed.code != 4001:
                                raise RuntimeError(
                                    f"Unexpected WebSocket close code: {closed.code}"
                                )
                            print("WS_REVOKED", flush=True)
                            return
                        raise RuntimeError("WebSocket remained open after Java logout")

                asyncio.run(main())
                """);
        try {
            ProcessBuilder clientBuilder = new ProcessBuilder(
                    requiredProperty("VOICE_AGENT_PYTHON"), clientScript.toString());
            clientBuilder.directory(new java.io.File(requiredProperty("VOICE_AGENT_PROJECT_DIR")));
            clientBuilder.redirectErrorStream(true);
            clientBuilder.environment().put("VOICE_TEST_PYTHON_PORT",
                    Integer.toString(pythonPort));
            clientBuilder.environment().put("VOICE_TEST_TICKET", ticket);
            Process client = clientBuilder.start();
            try (BufferedReader output = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                String readyLine;
                StringBuilder clientOutput = new StringBuilder();
                while ((readyLine = output.readLine()) != null) {
                    clientOutput.append(readyLine).append(System.lineSeparator());
                    if (readyLine.equals("WS_AUTHENTICATED")) break;
                }
                if (readyLine == null) {
                    client.waitFor(2, TimeUnit.SECONDS);
                    throw new IllegalStateException(
                            "WebSocket client exited before authenticating: " + clientOutput);
                }

                voiceSessionService.logoutAndCloseSessions(userId, loginSessionId);

                assertThat(redisTemplate.opsForValue().get(
                        RedisKeyConstant.USER_TOKEN + userId)).isNull();
                assertThat(redisTemplate.opsForValue().get(
                        RedisKeyConstant.USER_SESSION + userId)).isNull();
                String revokedLine = output.readLine();
                assertThat(revokedLine).isEqualTo("WS_REVOKED");
                assertThat(client.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(client.exitValue()).isZero();
            } finally {
                if (client.isAlive()) client.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(clientScript);
        }
    }

    @Test
    void pythonReadsOnlySessionOwnersOrdersAndCouponsOverSignedHttp() throws Exception {
        createAgentReadTables();
        long orderId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        long otherOrderId = orderId + 1;
        long otherUserId = userId + 1;
        jdbcTemplate.update(
                "insert into orders (id, number, status, user_id, order_time, amount) " +
                        "values (?, ?, ?, ?, current_timestamp, ?)",
                orderId, "VOICE-OWN-" + orderId, 5, userId, new java.math.BigDecimal("18.60"));
        jdbcTemplate.update(
                "insert into orders (id, number, status, user_id, order_time, amount) " +
                        "values (?, ?, ?, ?, current_timestamp, ?)",
                otherOrderId, "VOICE-OTHER-" + otherOrderId, 5, otherUserId,
                new java.math.BigDecimal("99.99"));
        jdbcTemplate.update(
                "insert into order_detail (id, order_id, name, number, image, dish_flavor) " +
                        "values (?, ?, ?, ?, ?, ?)",
                orderId, orderId, "测试盖饭", 2, "must-not-leak-image", "must-not-leak-flavor");
        jdbcTemplate.update(
                "insert into user_coupon (id, user_id, coupon_name_snapshot, coupon_type_snapshot, " +
                        "threshold_amount_snapshot, discount_amount_snapshot, discount_rate_snapshot, " +
                        "max_discount_amount_snapshot, scope_type_snapshot, status, " +
                        "valid_start_time, valid_end_time, acquired_time) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, " +
                        "dateadd('DAY', 2, current_timestamp), current_timestamp)",
                orderId, userId, "当前用户午餐券", 1, new java.math.BigDecimal("30.00"),
                new java.math.BigDecimal("8.00"), new java.math.BigDecimal("1.00"),
                new java.math.BigDecimal("0.00"), 1, 0);
        jdbcTemplate.update(
                "insert into user_coupon (id, user_id, coupon_name_snapshot, coupon_type_snapshot, " +
                        "threshold_amount_snapshot, discount_amount_snapshot, discount_rate_snapshot, " +
                        "max_discount_amount_snapshot, scope_type_snapshot, status, " +
                        "valid_start_time, valid_end_time, acquired_time) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, " +
                        "dateadd('DAY', 2, current_timestamp), current_timestamp)",
                otherOrderId, otherUserId, "他人优惠券不得返回", 1,
                new java.math.BigDecimal("30.00"), new java.math.BigDecimal("8.00"),
                new java.math.BigDecimal("1.00"), new java.math.BigDecimal("0.00"), 1, 0);

        startPythonAgent();
        awaitPythonHealth();
        String ticket = voiceSessionService.issueTicket(userId, loginSessionId).getTicket();
        String sessionId = UUID.randomUUID().toString();
        Path clientScript = Files.createTempFile("voice-agent-business-client-", ".py");
        Files.writeString(clientScript, """
                import asyncio
                import json
                import os
                import sys
                sys.path.insert(0, os.environ["VOICE_AGENT_PROJECT_DIR"])
                from agent.internal_auth import JavaInternalClient
                from agent.tools.java_business import create_java_business_tools

                async def main():
                    client = JavaInternalClient(
                        os.environ["AGENT_JAVA_INTERNAL_URL"],
                        os.environ["AGENT_PYTHON_HMAC_SECRET"],
                    )
                    grant = await client.redeem(
                        os.environ["VOICE_TEST_TICKET"],
                        os.environ["VOICE_TEST_SESSION_ID"],
                    )
                    order_tool, coupon_tool = create_java_business_tools(
                        client,
                        os.environ["VOICE_TEST_SESSION_ID"],
                        grant["sessionCapability"],
                    )
                    _, order_result = await order_tool.coroutine(order_number="")
                    _, coupon_result = await coupon_tool.coroutine(
                        page=1, page_size=20, status=None
                    )
                    orders = order_result["data"]["orders"]
                    coupons = coupon_result["data"]
                    spoofed_orders = await client._request(
                        "POST",
                        "/internal/agent/v1/voice-sessions/"
                        + os.environ["VOICE_TEST_SESSION_ID"] + "/orders",
                        {
                            "sessionCapability": grant["sessionCapability"],
                            "userId": int(os.environ["VOICE_TEST_OTHER_USER_ID"]),
                        },
                    )
                    assert len(orders) == 1, orders
                    assert orders[0]["order_number"] == os.environ["VOICE_TEST_ORDER"]
                    assert len(spoofed_orders) == 1, spoofed_orders
                    assert spoofed_orders[0]["orderNumber"] == os.environ["VOICE_TEST_ORDER"]
                    assert "测试盖饭 x2" in orders[0]["items"][0]
                    assert "phone" not in orders[0] and "address" not in orders[0]
                    assert coupons["total"] == 1, coupons
                    assert coupons["coupons"][0]["name"] == "当前用户午餐券"
                    print("SESSION_SCOPED_READS_OK")

                asyncio.run(main())
                """);
        try {
            ProcessBuilder clientBuilder = new ProcessBuilder(
                    requiredProperty("VOICE_AGENT_PYTHON"), clientScript.toString());
            clientBuilder.directory(new java.io.File(requiredProperty("VOICE_AGENT_PROJECT_DIR")));
            clientBuilder.redirectErrorStream(true);
            clientBuilder.environment().put(
                    "VOICE_AGENT_PROJECT_DIR", requiredProperty("VOICE_AGENT_PROJECT_DIR"));
            clientBuilder.environment().put(
                    "AGENT_JAVA_INTERNAL_URL", "http://127.0.0.1:" + javaPort);
            clientBuilder.environment().put("VOICE_TEST_TICKET", ticket);
            clientBuilder.environment().put("VOICE_TEST_SESSION_ID", sessionId);
            clientBuilder.environment().put("VOICE_TEST_ORDER", "VOICE-OWN-" + orderId);
            clientBuilder.environment().put(
                    "VOICE_TEST_OTHER_USER_ID", Long.toString(otherUserId));
            Process client = clientBuilder.start();
            boolean exited = client.waitFor(30, TimeUnit.SECONDS);
            if (!exited) client.destroyForcibly();
            String output = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exited).isTrue();
            assertThat(client.exitValue())
                    .as("Signed Java business query should remain session-scoped: %s", output)
                    .isZero();
            assertThat(output).contains("SESSION_SCOPED_READS_OK");
        } finally {
            Files.deleteIfExists(clientScript);
        }
    }

    private void createAgentReadTables() {
        jdbcTemplate.execute("create table if not exists orders (" +
                "id bigint primary key, number varchar(64), status int, user_id bigint, " +
                "order_time timestamp, amount decimal(10,2), phone varchar(32), address varchar(255))");
        jdbcTemplate.execute("create table if not exists order_detail (" +
                "id bigint primary key, order_id bigint, name varchar(128), number int, " +
                "image varchar(255), dish_flavor varchar(255))");
        jdbcTemplate.execute("create table if not exists user_coupon (" +
                "id bigint primary key, user_id bigint, coupon_name_snapshot varchar(128), " +
                "coupon_type_snapshot int, threshold_amount_snapshot decimal(10,2), " +
                "discount_amount_snapshot decimal(10,2), discount_rate_snapshot decimal(5,2), " +
                "max_discount_amount_snapshot decimal(10,2), scope_type_snapshot int, status int, " +
                "valid_start_time timestamp, valid_end_time timestamp, acquired_time timestamp)");
    }

    private void awaitPythonHealth() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (!pythonProcess.isAlive()) {
                throw new IllegalStateException("Python Agent process exited before health check");
            }
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + pythonPort + "/api/health"))
                                .timeout(Duration.ofSeconds(1))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (HttpStatusCode.valueOf(response.statusCode()).is2xxSuccessful()) return;
            } catch (IOException ignored) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("Python Agent health endpoint did not become ready");
    }

    private void assertThatThrownByValidation(String sessionId) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> voiceSessionService.validate(sessionId, "closed-capability"))
                .isInstanceOf(com.sky.exception.VoiceSessionException.class);
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test property is missing: " + name);
        }
        return value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Required test environment is missing or weak: " + name);
        }
        return value;
    }
}
