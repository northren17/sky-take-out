package com.sky.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.constant.RedisKeyConstant;
import com.sky.dto.VoiceSessionGrantVO;
import com.sky.dto.VoiceSessionRedeemDTO;
import com.sky.dto.VoiceSessionValidationVO;
import com.sky.dto.VoiceTicketVO;
import com.sky.exception.VoiceSessionException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class VoiceSessionService {
    public static final long TICKET_TTL_SECONDS = 60;
    public static final long SESSION_TTL_SECONDS = 30 * 60;
    private static final List<String> SESSION_SCOPES =
            List.of("orders:read", "coupons:read", "menu:read");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String STORE_LOGIN_SESSION_SCRIPT = """
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            return 1
            """;
    private static final String ISSUE_TICKET_SCRIPT = """
            if not redis.call('GET', KEYS[1]) then return 0 end
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
            local result = redis.call('SET', KEYS[3], ARGV[2], 'PX', ARGV[3], 'NX')
            if not result then return 0 end
            return 1
            """;
    private static final String REDEEM_TICKET_SCRIPT = """
            local ticket = redis.call('GET', KEYS[1])
            if not ticket or ticket ~= ARGV[1] then return -1 end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then return -2 end
            if not redis.call('GET', KEYS[3]) then return -2 end
            if redis.call('EXISTS', KEYS[4]) == 1 then return -3 end
            redis.call('SET', KEYS[4], ARGV[3], 'EX', ARGV[4], 'NX')
            if redis.call('EXISTS', KEYS[4]) == 0 then return -3 end
            redis.call('SADD', KEYS[5], ARGV[5])
            redis.call('EXPIRE', KEYS[5], ARGV[4])
            redis.call('DEL', KEYS[1])
            return 1
            """;
    private static final String LOGOUT_SCRIPT = """
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return '[]' end
            redis.call('DEL', KEYS[1], KEYS[2])
            local ids = redis.call('SMEMBERS', KEYS[3])
            for _, id in ipairs(ids) do
              redis.call('DEL', ARGV[2] .. id)
            end
            return cjson.encode(ids)
            """;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final VoiceAgentRevocationClient revocationClient;
    private final DefaultRedisScript<Long> storeLoginSessionScript;
    private final DefaultRedisScript<Long> issueTicketScript;
    private final DefaultRedisScript<Long> redeemTicketScript;
    private final DefaultRedisScript<String> logoutScript;

    public VoiceSessionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                               VoiceAgentRevocationClient revocationClient) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.revocationClient = revocationClient;
        this.storeLoginSessionScript = script(STORE_LOGIN_SESSION_SCRIPT, Long.class);
        this.issueTicketScript = script(ISSUE_TICKET_SCRIPT, Long.class);
        this.redeemTicketScript = script(REDEEM_TICKET_SCRIPT, Long.class);
        this.logoutScript = script(LOGOUT_SCRIPT, String.class);
    }

    public String newLoginSessionId() {
        return UUID.randomUUID().toString();
    }

    public void storeLoginSession(Long userId, String token, String sessionId, long ttlMillis) {
        Long stored = redisTemplate.execute(storeLoginSessionScript,
                List.of(userTokenKey(userId), userSessionKey(userId)),
                token, sessionId, Long.toString(ttlMillis));
        if (!Long.valueOf(1).equals(stored)) {
            throw new VoiceSessionException("无法创建服务端登录会话");
        }
    }

    public VoiceTicketVO issueTicket(Long userId, String loginSessionId) {
        if (userId == null || loginSessionId == null || loginSessionId.isBlank()) {
            throw new VoiceSessionException("请重新登录后再启动语音助手");
        }
        String ticket = randomToken();
        Instant expiresAt = Instant.now().plusSeconds(TICKET_TTL_SECONDS);
        String ticketJson = json(Map.of(
                "userId", userId,
                "loginSessionId", loginSessionId,
                "audience", "voice-agent",
                "scopes", SESSION_SCOPES,
                "expiresAt", expiresAt.toEpochMilli()));
        Long created = redisTemplate.execute(issueTicketScript,
                List.of(userTokenKey(userId), userSessionKey(userId), ticketKey(ticket)),
                loginSessionId, ticketJson, Long.toString(TimeUnit.SECONDS.toMillis(TICKET_TTL_SECONDS)));
        if (!Long.valueOf(1).equals(created)) {
            throw new VoiceSessionException("登录会话已失效，无法签发语音凭证");
        }
        return new VoiceTicketVO(ticket, expiresAt, TICKET_TTL_SECONDS);
    }

    public VoiceSessionGrantVO redeem(VoiceSessionRedeemDTO request) {
        if (request == null || request.getTicket() == null || request.getSessionId() == null) {
            throw new VoiceSessionException("ticket 或 sessionId 无效");
        }
        try {
            UUID parsed = UUID.fromString(request.getSessionId());
            if (!parsed.toString().equalsIgnoreCase(request.getSessionId())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new VoiceSessionException("sessionId 无效");
        }

        String ticketKey = ticketKey(request.getTicket());
        String serializedTicket = redisTemplate.opsForValue().get(ticketKey);
        if (serializedTicket == null) throw new VoiceSessionException("ticket 无效、已过期或已使用");

        Map<String, Object> ticket = readJson(serializedTicket);
        Long userId = number(ticket.get("userId"));
        String loginSessionId = String.valueOf(ticket.get("loginSessionId"));
        if (userId == null || !"voice-agent".equals(ticket.get("audience"))) {
            throw new VoiceSessionException("ticket 无效");
        }

        String capability = randomToken();
        Instant expiresAt = Instant.now().plusSeconds(SESSION_TTL_SECONDS);
        String sessionJson = json(Map.of(
                "sessionId", request.getSessionId(),
                "userId", userId,
                "loginSessionId", loginSessionId,
                "capabilityHash", sha256Hex(capability),
                "scopes", SESSION_SCOPES,
                "expiresAt", expiresAt.toEpochMilli()));
        Long result = redisTemplate.execute(redeemTicketScript,
                List.of(ticketKey, userSessionKey(userId), userTokenKey(userId),
                        sessionKey(request.getSessionId()), loginSessionsKey(loginSessionId)),
                serializedTicket, loginSessionId, sessionJson,
                Long.toString(SESSION_TTL_SECONDS), request.getSessionId());
        if (!Long.valueOf(1).equals(result)) {
            if (Long.valueOf(-2).equals(result)) {
                throw new VoiceSessionException("登录会话已失效");
            }
            if (Long.valueOf(-3).equals(result)) {
                throw new VoiceSessionException("sessionId 已存在");
            }
            throw new VoiceSessionException("ticket 无效、已过期或已使用");
        }
        return new VoiceSessionGrantVO(request.getSessionId(), capability, SESSION_SCOPES,
                expiresAt, SESSION_TTL_SECONDS);
    }

    public VoiceSessionValidationVO validate(String sessionId, String capability) {
        Map<String, Object> session = loadValidSession(sessionId, capability);
        return new VoiceSessionValidationVO(true, SESSION_SCOPES,
                Instant.ofEpochMilli(number(session.get("expiresAt"))));
    }

    public Long authorizeRead(String sessionId, String capability, String requiredScope) {
        if (!SESSION_SCOPES.contains(requiredScope)) {
            throw new VoiceSessionException("语音会话权限范围无效");
        }
        Map<String, Object> session = loadValidSession(sessionId, capability);
        Object scopes = session.get("scopes");
        if (!(scopes instanceof List<?> grantedScopes) || !grantedScopes.contains(requiredScope)) {
            throw new VoiceSessionException("语音会话不具备所需只读权限");
        }
        Long userId = number(session.get("userId"));
        if (userId == null) throw new VoiceSessionException("语音会话用户无效");
        return userId;
    }

    public void close(String sessionId, String capability) {
        Map<String, Object> session = loadValidSession(sessionId, capability);
        redisTemplate.delete(sessionKey(sessionId));
        redisTemplate.opsForSet().remove(
                loginSessionsKey(String.valueOf(session.get("loginSessionId"))), sessionId);
    }

    public void logoutAndCloseSessions(Long userId, String loginSessionId) {
        String response = redisTemplate.execute(logoutScript,
                List.of(userTokenKey(userId), userSessionKey(userId), loginSessionsKey(loginSessionId)),
                loginSessionId, RedisKeyConstant.VOICE_SESSION);
        if (response == null) throw new VoiceSessionException("服务端登出状态未确认");
        List<String> sessionIds;
        try {
            sessionIds = objectMapper.readValue(response, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new VoiceSessionException("服务端已撤销登录，但语音会话关闭状态未确认");
        }
        revocationClient.revokeSessions(sessionIds);
        redisTemplate.delete(loginSessionsKey(loginSessionId));
    }

    private Map<String, Object> loadValidSession(String sessionId, String capability) {
        if (sessionId == null || capability == null) {
            throw new VoiceSessionException("语音会话无效");
        }
        String raw = redisTemplate.opsForValue().get(sessionKey(sessionId));
        if (raw == null) throw new VoiceSessionException("语音会话已过期或撤销");
        Map<String, Object> session = readJson(raw);
        if (!MessageDigest.isEqual(
                sha256Hex(capability).getBytes(),
                String.valueOf(session.get("capabilityHash")).getBytes())) {
            throw new VoiceSessionException("语音会话无效");
        }
        Long userId = number(session.get("userId"));
        String loginSessionId = String.valueOf(session.get("loginSessionId"));
        Long expiresAt = number(session.get("expiresAt"));
        if (userId == null || expiresAt == null || Instant.now().toEpochMilli() >= expiresAt) {
            throw new VoiceSessionException("语音会话已过期");
        }
        String currentSession = redisTemplate.opsForValue().get(userSessionKey(userId));
        String currentToken = redisTemplate.opsForValue().get(userTokenKey(userId));
        if (!loginSessionId.equals(currentSession) || currentToken == null) {
            throw new VoiceSessionException("登录会话已失效");
        }
        return session;
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new VoiceSessionException("语音会话记录无效");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new VoiceSessionException("无法创建语音会话记录");
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash voice session credential", ex);
        }
    }

    private static Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String userTokenKey(Long userId) {
        return RedisKeyConstant.USER_TOKEN + userId;
    }

    private static String userSessionKey(Long userId) {
        return RedisKeyConstant.USER_SESSION + userId;
    }

    private static String ticketKey(String ticket) {
        return RedisKeyConstant.VOICE_TICKET + sha256Hex(ticket);
    }

    private static String sessionKey(String sessionId) {
        return RedisKeyConstant.VOICE_SESSION + sessionId;
    }

    private static String loginSessionsKey(String loginSessionId) {
        return RedisKeyConstant.VOICE_LOGIN_SESSIONS + loginSessionId;
    }

    private static <T> DefaultRedisScript<T> script(String source, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(resultType);
        return script;
    }
}
