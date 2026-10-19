package com.sky.controller.admin;

import com.sky.dto.CouponActivityDTO;
import com.sky.dto.CouponExchangeCodeBatchDTO;
import com.sky.dto.CouponPageQueryDTO;
import com.sky.dto.CouponTemplateDTO;
import com.sky.entity.CouponTemplate;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.CouponService;
import com.sky.service.CouponExchangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/coupon")
@Tag(name = "优惠券管理")
@RequiredArgsConstructor
public class CouponController {
    private final CouponService couponService;
    private final CouponExchangeService couponExchangeService;

    @GetMapping("/templates/page")
    @Operation(summary = "优惠券模板分页查询")
    public Result<PageResult> pageTemplates(CouponPageQueryDTO query) {
        return Result.success(couponService.pageTemplates(query));
    }

    @GetMapping("/templates/{id}")
    @Operation(summary = "查询优惠券模板")
    public Result<CouponTemplate> getTemplate(@PathVariable Long id) {
        return Result.success(couponService.getTemplate(id));
    }

    @PostMapping("/templates")
    @Operation(summary = "新增优惠券模板")
    public Result<Void> saveTemplate(@RequestBody CouponTemplateDTO dto) {
        couponService.saveTemplate(dto);
        return Result.success();
    }

    @PutMapping("/templates")
    @Operation(summary = "修改优惠券模板")
    public Result<Void> updateTemplate(@RequestBody CouponTemplateDTO dto) {
        couponService.updateTemplate(dto);
        return Result.success();
    }

    @DeleteMapping("/templates/{id}")
    @Operation(summary = "删除优惠券模板")
    public Result<Void> deleteTemplate(@PathVariable Long id) {
        couponService.deleteTemplate(id);
        return Result.success();
    }

    @PostMapping("/templates/{id}/status/{status}")
    @Operation(summary = "启用或停用优惠券模板")
    public Result<Void> updateTemplateStatus(@PathVariable Long id, @PathVariable Integer status) {
        couponService.updateTemplateStatus(id, status);
        return Result.success();
    }

    @GetMapping("/activities/page")
    @Operation(summary = "优惠券活动分页查询")
    public Result<PageResult> pageActivities(CouponPageQueryDTO query) {
        return Result.success(couponService.pageActivities(query));
    }

    @PostMapping("/activities")
    @Operation(summary = "创建优惠券活动")
    public Result<Void> saveActivity(@RequestBody CouponActivityDTO dto) {
        couponService.saveActivity(dto);
        return Result.success();
    }

    @PutMapping("/activities")
    @Operation(summary = "修改未发券优惠券活动")
    public Result<Void> updateActivity(@RequestBody CouponActivityDTO dto) {
        couponService.updateActivity(dto);
        return Result.success();
    }

    @PostMapping("/activities/{id}/status/{status}")
    @Operation(summary = "开始或暂停优惠券活动")
    public Result<Void> updateActivityStatus(@PathVariable Long id, @PathVariable Integer status) {
        couponService.updateActivityStatus(id, status);
        return Result.success();
    }

    @PostMapping("/exchange-codes")
    @Operation(summary = "批量生成活动兑换码")
    public Result<List<String>> generateExchangeCodes(@RequestBody CouponExchangeCodeBatchDTO dto) {
        return Result.success(couponExchangeService.generateCodes(dto));
    }
}
