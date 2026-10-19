package com.sky.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.exception.VoiceSessionException;
import com.sky.security.AgentHmac;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class VoiceAgentRevocationClient {
    private static final String REVOKE_PATH = "/internal/agent/v1/voice-sessions/revoke";

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String secret;

    public VoiceAgentRevocationClient(
            ObjectMapper objectMapper,
            @Value("${VOICE_AGENT_INTERNAL_URL:}") String baseUrl,
            @Value("${SKY_AGENT_JAVA_HMAC_SECRET:}") String secret) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.secret = secret;
    }

    public void revokeSessions(List<String> sessionIds) {
        if (sessionIds.isEmpty()) return;
        if (baseUrl.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new VoiceSessionException(
                    "服务端登录已撤销，但语音会话关闭未确认；请稍后重试或联系支持人员");
        }

        try {
            byte[] body = objectMapper.writeValueAsBytes(new RevokeRequest(sessionIds));
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = AgentHmac.sign(secret, "POST", REVOKE_PATH,
                    timestamp, nonce, body);

            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(2_000);
            requestFactory.setReadTimeout(5_000);
            RestClient client = RestClient.builder().requestFactory(requestFactory).build();
            client.post()
                    .uri(URI.create(baseUrl + REVOKE_PATH))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(AgentHmac.TIMESTAMP_HEADER, timestamp)
                    .header(AgentHmac.NONCE_HEADER, nonce)
                    .header(AgentHmac.SIGNATURE_HEADER, signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Voice Agent session revocation failed: errorType={}",
                    ex.getClass().getSimpleName());
            throw new VoiceSessionException(
                    "服务端登录已撤销，但语音会话关闭未确认；请稍后重试或联系支持人员");
        }
    }

    private record RevokeRequest(List<String> sessionIds) {
    }
}
