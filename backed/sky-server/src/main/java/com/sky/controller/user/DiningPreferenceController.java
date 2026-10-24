package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.dto.DiningPreferenceDTO;
import com.sky.result.Result;
import com.sky.service.DiningPreferenceService;
import com.sky.vo.DiningPreferenceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/dining-preferences")
@RequiredArgsConstructor
public class DiningPreferenceController {
    private final DiningPreferenceService preferenceService;

    @GetMapping
    public Result<DiningPreferenceVO> get() {
        return Result.success(preferenceService.get(BaseContext.getCurrentId()));
    }

    @PutMapping
    public Result<DiningPreferenceVO> save(@RequestBody DiningPreferenceDTO request) {
        return Result.success(preferenceService.save(BaseContext.getCurrentId(), request));
    }
}
