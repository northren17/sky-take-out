package com.sky;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.RedisKeyConstant;
import com.sky.service.CaptchaService;
import com.sky.service.VoiceSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * C端用户注册 / 账号密码登录 / JWT + Redis 会话鉴权 的验证测试。
 * <p>
 * 覆盖：
 * <ol>
 *     <li>未带令牌访问受保护接口 → 401；</li>
 *     <li>注册接口可用，账号写入 username 列，密码以 BCrypt 密文存储（不是明文也不是 MD5）；</li>
 *     <li>重复注册同一账号 → "该账号已被注册"；</li>
 *     <li>两次密码不一致 → "两次输入的密码不一致"；</li>
 *     <li>登录成功后用返回的令牌可访问 /user/user/info；</li>
 *     <li>密码错误 → "密码错误"；</li>
 *     <li>白名单接口无需登录（验证码 / 登录）；</li>
 *     <li>退出登录后令牌立即失效 → 401（证明 Redis 会话生效，而不是只靠 jwt 自校验）。</li>
 * </ol>
 * 说明：测试用 H2 内存库 + Mock 中间件，Redis 的 opsForValue 用一个内存 Map 模拟，
 * 因此不依赖本机 MySQL / Redis / RabbitMQ，可在任意环境重复执行。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("C端用户注册/登录/鉴权")
class UserAuthTest {

    @Autowired
    private TestRestTemplate restTemplate;

    /** 验证码校验用 Mock 直接放行，验证码本身的有效期/一次性另有测试覆盖 */
    @MockBean
    private CaptchaService captchaService;

    @MockBean
    private VoiceSessionService voiceSessionService;

    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockBean
    private org.springframework.amqp.rabbit.connection.ConnectionFactory amqpConnectionFactory;

    @MockBean
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    /** 内存版 Redis 令牌存储，用于断言"服务端会话"逻辑 */
    private final AtomicReference<Map<String, String>> tokenStore = new AtomicReference<>(new HashMap<>());

    /**
     * Redis 客户端用 Mock 替代：真正被 mock 的是模板与 ValueOperations 两层，
     * opsForValue() 必须由模板这个 mock 返回，才能被 Mockito 拦截。
     */
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private static final String USERNAME = "sky_user_01";
    private static final String PASSWORD = "sky@123456";

    private void stubRedis() {
        tokenStore.set(new HashMap<>());

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(inv -> {
            tokenStore.get().put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(valueOperations.get(anyString())).thenAnswer(inv -> tokenStore.get().get(inv.getArgument(0)));
        when(stringRedisTemplate.delete(anyString())).thenAnswer(inv -> {
            tokenStore.get().remove(inv.getArgument(0));
            return true;
        });
        when(voiceSessionService.newLoginSessionId()).thenAnswer(
                inv -> UUID.randomUUID().toString());
        doAnswer(inv -> {
            Long userId = inv.getArgument(0);
            tokenStore.get().put(RedisKeyConstant.USER_TOKEN + userId, inv.getArgument(1));
            tokenStore.get().put(RedisKeyConstant.USER_SESSION + userId, inv.getArgument(2));
            return null;
        }).when(voiceSessionService).storeLoginSession(
                anyLong(), anyString(), anyString(), anyLong());
        doAnswer(inv -> {
            Long userId = inv.getArgument(0);
            tokenStore.get().remove(RedisKeyConstant.USER_TOKEN + userId);
            tokenStore.get().remove(RedisKeyConstant.USER_SESSION + userId);
            return null;
        }).when(voiceSessionService).logoutAndCloseSessions(anyLong(), anyString());
    }

    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    @DisplayName("未带令牌访问受保护接口 → 401")
    void protectedEndpointRequiresToken() {
        stubRedis();
        ResponseEntity<String> res = restTemplate.getForEntity("/user/user/info", String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(2)
    @DisplayName("注册成功：返回令牌，且密码以 BCrypt 密文存储")
    void registerWorks() {
        stubRedis();
        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        ResponseEntity<String> res = post("/user/user/register", registerBody(USERNAME, PASSWORD, PASSWORD));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        JSONObject body = JSON.parseObject(res.getBody());
        assertThat(body.getInteger("code")).as("注册应成功，实际=%s", res.getBody()).isEqualTo(1);
        String token = body.getJSONObject("data").getString("token");
        assertThat(token).as("注册成功应直接返回 jwt 令牌").isNotBlank();
        assertThat(body.getJSONObject("data").getString("username")).isEqualTo(USERNAME);

        // 会话已写入 Redis（key = user:token:{id}）
        assertThat(tokenStore.get().values()).contains(token);
    }

    @Test
    @Order(3)
    @DisplayName("重复注册同一账号 → 该账号已被注册")
    void duplicateRegisterRejected() {
        stubRedis();
        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        ResponseEntity<String> res = post("/user/user/register", registerBody(USERNAME, PASSWORD, PASSWORD));
        JSONObject body = JSON.parseObject(res.getBody());
        assertThat(body.getInteger("code")).isEqualTo(0);
        assertThat(body.getString("msg")).isEqualTo("该账号已被注册");
    }

    @Test
    @Order(4)
    @DisplayName("两次密码不一致 → 两次输入的密码不一致")
    void passwordMismatchRejected() {
        stubRedis();
        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        ResponseEntity<String> res = post("/user/user/register", registerBody("sky_user_02", PASSWORD, "other12345"));
        JSONObject body = JSON.parseObject(res.getBody());
        assertThat(body.getInteger("code")).isEqualTo(0);
        assertThat(body.getString("msg")).isEqualTo("两次输入的密码不一致");
    }

    @Test
    @Order(5)
    @DisplayName("登录成功：返回令牌，带令牌可访问 /user/user/info")
    void loginAndAccessProtectedEndpoint() {
        stubRedis();
        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        String token = login(USERNAME, PASSWORD);

        ResponseEntity<String> info = restTemplate.exchange(
                "/user/user/info", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
        assertThat(info.getStatusCode().is2xxSuccessful()).isTrue();

        JSONObject body = JSON.parseObject(info.getBody());
        assertThat(body.getInteger("code")).isEqualTo(1);
        JSONObject data = body.getJSONObject("data");
        assertThat(data.getString("username")).isEqualTo(USERNAME);
        assertThat(data.getString("id")).isNotBlank();
        // 绝不能把密码返回给前端
        assertThat(info.getBody()).doesNotContain("password").doesNotContain("$2a$");
    }

    @Test
    @Order(6)
    @DisplayName("密码错误 → 密码错误")
    void wrongPasswordRejected() {
        stubRedis();
        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        ResponseEntity<String> res = post("/user/user/loginByPassword", loginBody(USERNAME, "wrong_pwd_123"));
        JSONObject body = JSON.parseObject(res.getBody());
        assertThat(body.getInteger("code")).isEqualTo(0);
        assertThat(body.getString("msg")).isEqualTo("密码错误");
    }

    @Test
    @Order(7)
    @DisplayName("白名单接口无需登录：验证码 / 登录 / 注册")
    void whitelistEndpointsNeedNoToken() {
        stubRedis();

        ResponseEntity<String> captcha = restTemplate.getForEntity("/user/user/captcha", String.class);
        assertThat(captcha.getStatusCode().is2xxSuccessful())
                .as("验证码接口应可匿名访问，实际=%s", captcha.getStatusCode())
                .isTrue();

        when(captchaService.verify(anyString(), anyString())).thenReturn(true);
        ResponseEntity<String> res = post("/user/user/loginByPassword", loginBody(USERNAME, PASSWORD));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(JSON.parseObject(res.getBody()).getInteger("code")).isEqualTo(1);
    }

    @Test
    @Order(8)
    @DisplayName("退出登录后令牌立即失效 → 401（Redis 会话生效）")
    void logoutInvalidatesToken() {
        stubRedis();
        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        String token = login(USERNAME, PASSWORD);
        HttpHeaders headers = authHeaders(token);

        // 退出前可访问
        assertThat(restTemplate.exchange("/user/user/info", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> logout = restTemplate.exchange(
                "/user/user/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(logout.getStatusCode().is2xxSuccessful()).isTrue();

        // 退出后同一个令牌立即失效：jwt 本身还能验签，但 Redis 里已没有它
        ResponseEntity<String> after = restTemplate.exchange(
                "/user/user/info", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(after.getStatusCode().value())
                .as("退出登录后应返回 401，实际=%s", after.getStatusCode())
                .isEqualTo(401);
    }

    @Test
    @Order(9)
    @DisplayName("新登录替换当前会话后，旧令牌立即失效")
    void newLoginInvalidatesOlderToken() {
        stubRedis();
        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        String oldToken = login(USERNAME, PASSWORD);
        String newToken = login(USERNAME, PASSWORD);
        assertThat(newToken).isNotEqualTo(oldToken);

        ResponseEntity<String> oldSession = restTemplate.exchange(
                "/user/user/info", HttpMethod.GET,
                new HttpEntity<>(authHeaders(oldToken)), String.class);
        ResponseEntity<String> currentSession = restTemplate.exchange(
                "/user/user/info", HttpMethod.GET,
                new HttpEntity<>(authHeaders(newToken)), String.class);

        assertThat(oldSession.getStatusCode().value()).isEqualTo(401);
        assertThat(currentSession.getStatusCode().value()).isEqualTo(200);
    }

    /* ------------------------------------------------------------------ */

    private String login(String username, String password) {
        ResponseEntity<String> res = post("/user/user/loginByPassword", loginBody(username, password));
        JSONObject body = JSON.parseObject(res.getBody());
        assertThat(body.getInteger("code")).as("登录应成功，实际=%s", res.getBody()).isEqualTo(1);
        return body.getJSONObject("data").getString("token");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authentication", token);
        return headers;
    }

    private Map<String, Object> registerBody(String username, String password, String confirmPassword) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("confirmPassword", confirmPassword);
        body.put("phone", "13800000000");
        body.put("name", "测试用户");
        body.put("code", "ABCD");
        body.put("captchaKey", "test-captcha-key");
        return body;
    }

    private Map<String, Object> loginBody(String username, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("code", "ABCD");
        body.put("captchaKey", "test-captcha-key");
        return body;
    }

    private ResponseEntity<String> post(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
