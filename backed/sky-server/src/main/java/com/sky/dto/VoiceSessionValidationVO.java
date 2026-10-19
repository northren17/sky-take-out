package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@AllArgsConstructor
public class VoiceSessionValidationVO {
    private boolean valid;
    private List<String> scopes;
    private Instant expiresAt;
}
