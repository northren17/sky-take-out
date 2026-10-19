package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.dto.VoiceTicketVO;
import com.sky.result.Result;
import com.sky.service.VoiceSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/voice-sessions")
@RequiredArgsConstructor
public class VoiceSessionController {
    private final VoiceSessionService voiceSessionService;

    @PostMapping("/ticket")
    public Result<VoiceTicketVO> issueTicket() {
        return Result.success(voiceSessionService.issueTicket(
                BaseContext.getCurrentId(), BaseContext.getCurrentSessionId()));
    }
}
