package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@AllArgsConstructor
public class VoiceSessionGrantVO {
    private String sessionId;
    private String sessionCapability;
    private List<String> scopes;
    private Instant expiresAt;
    private long expiresInSeconds;
}
