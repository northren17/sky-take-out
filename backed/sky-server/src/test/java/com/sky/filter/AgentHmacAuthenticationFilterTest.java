package com.sky.filter;

import com.sky.security.AgentHmac;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentHmacAuthenticationFilterTest {
    private static final String SECRET = "python-to-java-test-secret-0000000000";
    private static final byte[] BODY = "{\"ticket\":\"one-time\"}".getBytes(StandardCharsets.UTF_8);

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private AgentHmacAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        filter = new AgentHmacAuthenticationFilter(redisTemplate, SECRET);
    }

    @Test
    void matchesPythonCanonicalHmacVector() {
        assertThat(AgentHmac.sign(
                "shared-secret",
                "POST",
                "/x",
                "1700000000",
                "abcdefghijklmnopqrstuvwx",
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("324774277285106267e115a86e76ce77c9fd6cad05127d12d2221943a128c127");
    }

    @Test
    void acceptsValidSignatureAndForwardsBody() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        MockHttpServletRequest request = signedRequest(BODY, "a".repeat(32), null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] called = {false};

        filter.doFilterInternal(request, response, (wrappedRequest, wrappedResponse) -> {
            called[0] = true;
            assertThat(wrappedRequest.getInputStream().readAllBytes()).isEqualTo(BODY);
        });

        assertThat(called[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsInvalidSignatureBeforeNonceConsumption() throws Exception {
        MockHttpServletRequest request = signedRequest(
                BODY, "b".repeat(32), AgentHmac.sign(
                        SECRET, "POST", "/internal/agent/v1/voice-sessions/redeem",
                        Long.toString(System.currentTimeMillis() / 1000), "b".repeat(32),
                        "different".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {
            throw new AssertionError("invalid signatures must not reach the controller");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(valueOperations);
    }

    @Test
    void rejectsReplayedNonceAndFailsClosedWhenRedisIsUnavailable() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true, false);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilterInternal(signedRequest(BODY, "c".repeat(32), null), firstResponse,
                (req, res) -> { });
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        filter.doFilterInternal(signedRequest(BODY, "c".repeat(32), null), replayResponse,
                (req, res) -> { });

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(replayResponse.getStatus()).isEqualTo(401);

        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        MockHttpServletResponse unavailableResponse = new MockHttpServletResponse();
        filter.doFilterInternal(signedRequest(BODY, "d".repeat(32), null), unavailableResponse,
                (req, res) -> { });
        assertThat(unavailableResponse.getStatus()).isEqualTo(503);
    }

    @Test
    void rejectsWeakSharedSecret() throws Exception {
        AgentHmacAuthenticationFilter weakFilter =
                new AgentHmacAuthenticationFilter(redisTemplate, "short");
        MockHttpServletResponse response = new MockHttpServletResponse();

        weakFilter.doFilterInternal(signedRequest(BODY, "e".repeat(32), null), response,
                (req, res) -> {
                    throw new AssertionError("weak HMAC secrets must fail closed");
                });

        assertThat(response.getStatus()).isEqualTo(503);
    }

    private MockHttpServletRequest signedRequest(byte[] body, String nonce, String signatureOverride) {
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        String target = "/internal/agent/v1/voice-sessions/redeem";
        String signature = signatureOverride == null
                ? AgentHmac.sign(SECRET, "POST", target, timestamp, nonce, body)
                : signatureOverride;
        MockHttpServletRequest request = new MockHttpServletRequest("POST", target);
        request.setContent(body);
        request.addHeader(AgentHmac.TIMESTAMP_HEADER, timestamp);
        request.addHeader(AgentHmac.NONCE_HEADER, nonce);
        request.addHeader(AgentHmac.SIGNATURE_HEADER, signature);
        return request;
    }
}
