package com.sky.controller.user;

import com.sky.dto.MealReservationDTO;
import com.sky.entity.MealReservationCapacity;
import com.sky.result.Result;
import com.sky.service.MealReservationService;
import com.sky.vo.MealReservationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/user/reservation")
@RequiredArgsConstructor
@Tag(name = "C端-预约订餐接口")
public class MealReservationController {
    private final MealReservationService reservationService;

    @PostMapping
    @Operation(summary = "提交预约")
    public Result<MealReservationVO> create(@RequestBody MealReservationDTO dto) {
        return Result.success(reservationService.create(dto));
    }

    @GetMapping("/mine")
    @Operation(summary = "查询我的预约")
    public Result<List<MealReservationVO>> listMine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(reservationService.listMine(page, pageSize));
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "取消预约")
    public Result<Void> cancel(@PathVariable Long id) {
        reservationService.cancel(id);
        return Result.success();
    }

    @GetMapping("/capacity")
    @Operation(summary = "查询指定日期和时段的预约限额")
    public Result<MealReservationCapacity> capacity(@RequestParam LocalDate date, @RequestParam Integer period) {
        return Result.success(reservationService.getCapacity(date, period));
    }
}
