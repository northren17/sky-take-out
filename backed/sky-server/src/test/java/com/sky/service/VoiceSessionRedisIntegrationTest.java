package com.sky.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.constant.RedisKeyConstant;
import com.sky.dto.VoiceSessionRedeemDTO;
import com.sky.exception.VoiceSessionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@EnabledIfSystemProperty(named = "VOICE_TEST_REDIS_PORT", matches = "\\d+")
class VoiceSessionRedisIntegrationTest {
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static VoiceAgentRevocationClient revocationClient;
    private VoiceSessionService service;
    private Long userId;
    private String loginSessionId;

    @BeforeAll
    static void connectToTestRedis() {
        int port = Integer.parseInt(System.getProperty("VOICE_TEST_REDIS_PORT"));
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration("127.0.0.1", port);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        revocationClient = mock(VoiceAgentRevocationClient.class);
    }

    @AfterAll
    static void closeRedisConnection() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void createServiceAndLoginSession() {
        service = new VoiceSessionService(redisTemplate, new ObjectMapper(), revocationClient);
        userId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        loginSessionId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                RedisKeyConstant.USER_TOKEN + userId, "test-jwt", Duration.ofMinutes(2));
        redisTemplate.opsForValue().set(
                RedisKeyConstant.USER_SESSION + userId, loginSessionId, Duration.ofMinutes(2));
    }

    @Test
    void issuesAndRedeemsTicketExactlyOnceUnderConcurrency() throws Exception {
        String ticket = service.issueTicket(userId, loginSessionId).getTicket();
        int contenders = 12;
        AtomicInteger successes = new AtomicInteger();
        List<String> failures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                String sessionId = UUID.randomUUID().toString();
                results.add(executor.submit(() -> {
                    try {
                        service.redeem(request(ticket, sessionId));
                        successes.incrementAndGet();
                    } catch (VoiceSessionException ex) {
                        synchronized (failures) {
                            failures.add(ex.getMessage());
                        }
                    }
                }));
            }
            for (var result : results) result.get();
        }

        assertThat(successes).hasValue(1);
        assertThat(failures).hasSize(contenders - 1);
    }

    @Test
    void issuedTicketExpiresAndCannotBeRedeemed() throws Exception {
        String ticket = service.issueTicket(userId, loginSessionId).getTicket();
        String ticketKey = RedisKeyConstant.VOICE_TICKET + sha256Hex(ticket);
        assertThat(redisTemplate.hasKey(ticketKey)).isTrue();
        assertThat(redisTemplate.expire(ticketKey, Duration.ofMillis(250))).isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (redisTemplate.hasKey(ticketKey) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(redisTemplate.hasKey(ticketKey)).isFalse();

        assertThatThrownBy(() -> service.redeem(
                request(ticket, UUID.randomUUID().toString())))
                .isInstanceOf(VoiceSessionException.class);
    }

    @Test
    void logoutInvalidatesActiveSessionAndUnredeemedTickets() {
        String activeTicket = service.issueTicket(userId, loginSessionId).getTicket();
        String unusedTicket = service.issueTicket(userId, loginSessionId).getTicket();
        String sessionId = UUID.randomUUID().toString();
        String capability = service.redeem(request(activeTicket, sessionId))
                .getSessionCapability();

        service.logoutAndCloseSessions(userId, loginSessionId);

        assertThatThrownBy(() -> service.validate(sessionId, capability))
                .isInstanceOf(VoiceSessionException.class);
        assertThatThrownBy(() -> service.redeem(
                request(unusedTicket, UUID.randomUUID().toString())))
                .isInstanceOf(VoiceSessionException.class);
        verify(revocationClient).revokeSessions(List.of(sessionId));
    }

    @Test
    void readAuthorizationResolvesUserFromCapabilityAndRejectsInvalidScope() {
        String ticket = service.issueTicket(userId, loginSessionId).getTicket();
        String sessionId = UUID.randomUUID().toString();
        String capability = service.redeem(request(ticket, sessionId)).getSessionCapability();

        assertThat(service.authorizeRead(sessionId, capability, "orders:read")).isEqualTo(userId);
        assertThat(service.authorizeRead(sessionId, capability, "coupons:read")).isEqualTo(userId);
        assertThat(service.authorizeRead(sessionId, capability, "menu:read")).isEqualTo(userId);
        assertThatThrownBy(() -> service.authorizeRead(sessionId, capability, "memory:read"))
                .isInstanceOf(VoiceSessionException.class);
        assertThatThrownBy(() -> service.authorizeRead(sessionId, capability, "orders:write"))
                .isInstanceOf(VoiceSessionException.class);
        assertThatThrownBy(() -> service.authorizeRead(
                sessionId, "another-session-capability", "orders:read"))
                .isInstanceOf(VoiceSessionException.class);
    }

    private static VoiceSessionRedeemDTO request(String ticket, String sessionId) {
        VoiceSessionRedeemDTO request = new VoiceSessionRedeemDTO();
        request.setTicket(ticket);
        request.setSessionId(sessionId);
        return request;
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
    }
}
