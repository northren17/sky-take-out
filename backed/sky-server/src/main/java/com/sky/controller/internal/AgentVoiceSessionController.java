package com.sky.controller.internal;

import com.sky.dto.VoiceSessionCapabilityDTO;
import com.sky.dto.VoiceSessionGrantVO;
import com.sky.dto.VoiceSessionRedeemDTO;
import com.sky.dto.VoiceSessionValidationVO;
import com.sky.dto.AgentCouponQueryDTO;
import com.sky.service.AgentBusinessQueryService;
import com.sky.service.VoiceSessionService;
import com.sky.exception.VoiceSessionException;
import com.sky.vo.AgentCouponPageVO;
import com.sky.vo.AgentOrderSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/agent/v1/voice-sessions")
@RequiredArgsConstructor
public class AgentVoiceSessionController {
    private final VoiceSessionService voiceSessionService;
    private final AgentBusinessQueryService businessQueryService;

    @PostMapping("/redeem")
    public VoiceSessionGrantVO redeem(@RequestBody VoiceSessionRedeemDTO request) {
        return voiceSessionService.redeem(request);
    }

    @PostMapping("/{sessionId}/validate")
    public VoiceSessionValidationVO validate(@PathVariable String sessionId,
                                             @RequestBody VoiceSessionCapabilityDTO request) {
        return voiceSessionService.validate(sessionId, request.getSessionCapability());
    }

    @PostMapping("/{sessionId}/close")
    public void close(@PathVariable String sessionId,
                      @RequestBody VoiceSessionCapabilityDTO request) {
        voiceSessionService.close(sessionId, request.getSessionCapability());
    }

    @PostMapping("/{sessionId}/orders")
    public List<AgentOrderSummaryVO> recentOrders(
            @PathVariable String sessionId,
            @RequestBody VoiceSessionCapabilityDTO request) {
        Long userId = voiceSessionService.authorizeRead(
                sessionId, request.getSessionCapability(), "orders:read");
        return businessQueryService.recentOrders(userId);
    }

    @PostMapping("/{sessionId}/coupons")
    public AgentCouponPageVO coupons(@PathVariable String sessionId,
                                     @RequestBody AgentCouponQueryDTO request) {
        if (request == null) throw new VoiceSessionException("优惠券查询参数无效");
        Long userId = voiceSessionService.authorizeRead(
                sessionId, request.getSessionCapability(), "coupons:read");
        return businessQueryService.coupons(
                userId, request.getPage(), request.getPageSize(), request.getStatus());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidQuery(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "invalid_query", "message", ex.getMessage()));
    }

    @ExceptionHandler(VoiceSessionException.class)
    public ResponseEntity<Map<String, String>> handleSessionFailure(VoiceSessionException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "invalid_voice_session", "message", ex.getMessage()));
    }
}
