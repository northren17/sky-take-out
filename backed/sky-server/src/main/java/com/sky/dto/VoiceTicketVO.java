package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class VoiceTicketVO {
    private String ticket;
    private Instant expiresAt;
    private long expiresInSeconds;
}
