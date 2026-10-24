package com.sky.controller.admin;

import com.sky.context.BaseContext;
import com.sky.entity.MealReservation;
import com.sky.entity.MealReservationCapacity;
import com.sky.result.Result;
import com.sky.service.MealReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/reservation")
@RequiredArgsConstructor
@Tag(name = "管理端-预约管理接口")
public class MealReservationAdminController {
    private final MealReservationService reservationService;

    @PutMapping("/capacity")
    @Operation(summary = "设置预约限额")
    public Result<Void> updateCapacity(@RequestBody MealReservationCapacity capacity) {
        capacity.setUpdateUser(BaseContext.getCurrentId());
        reservationService.updateCapacity(capacity);
        return Result.success();
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询预约")
    public Result<List<MealReservation>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(reservationService.listAll(page, pageSize));
    }

    @PutMapping("/{id}/picked-up")
    @Operation(summary = "标记预约已取餐")
    public Result<Void> markPickedUp(@PathVariable Long id) {
        reservationService.markPickedUp(id);
        return Result.success();
    }
}
