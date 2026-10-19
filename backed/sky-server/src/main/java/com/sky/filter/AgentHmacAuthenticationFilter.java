package com.sky.filter;

import com.sky.security.AgentHmac;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

@Component
public class AgentHmacAuthenticationFilter extends OncePerRequestFilter {
    private static final int MIN_SECRET_BYTES = 32;
    private static final long ALLOWED_CLOCK_SKEW_SECONDS = 60;
    private static final Duration NONCE_TTL = Duration.ofSeconds(120);
    private static final int MAX_BODY_BYTES = 65_536;
    private static final Pattern NONCE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{22,128}");

    private final StringRedisTemplate redisTemplate;
    private final String secret;

    public AgentHmacAuthenticationFilter(
            StringRedisTemplate redisTemplate,
            @Value("${SKY_AGENT_PYTHON_HMAC_SECRET:}") String secret) {
        this.redisTemplate = redisTemplate;
        this.secret = secret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/agent/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Agent service authentication is not configured");
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        String timestamp = request.getHeader(AgentHmac.TIMESTAMP_HEADER);
        String nonce = request.getHeader(AgentHmac.NONCE_HEADER);
        String signature = request.getHeader(AgentHmac.SIGNATURE_HEADER);
        if (!validTimestamp(timestamp) || nonce == null || !NONCE_PATTERN.matcher(nonce).matches()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Agent authentication");
            return;
        }

        String target = request.getRequestURI();
        if (request.getQueryString() != null) target += "?" + request.getQueryString();
        String expected = AgentHmac.sign(secret, request.getMethod(), target, timestamp, nonce, body);
        if (!AgentHmac.matches(expected, signature)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Agent authentication");
            return;
        }

        try {
            Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                    "agent:hmac:nonce:python:" + nonce, "1", NONCE_TTL);
            if (!Boolean.TRUE.equals(accepted)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Replayed Agent request");
                return;
            }
        } catch (RuntimeException ex) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Agent authentication store is unavailable");
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean validTimestamp(String value) {
        if (value == null || !value.matches("[0-9]{10}")) return false;
        try {
            long timestamp = Long.parseLong(value);
            return Math.abs(Instant.now().getEpochSecond() - timestamp) <= ALLOWED_CLOCK_SKEW_SECONDS;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
