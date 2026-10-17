package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.dto.CouponClaimDTO;
import com.sky.dto.CouponRedeemDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.CouponService;
import com.sky.service.CouponExchangeService;
import com.sky.vo.CouponActivityVO;
import com.sky.vo.CouponClaimStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/user/coupon")
@Tag(name = "C端-优惠券")
@RequiredArgsConstructor
public class UserCouponController {
    private final CouponService couponService;
    private final CouponExchangeService couponExchangeService;

    @GetMapping("/activities")
    @Operation(summary = "查询可领取优惠券活动")
    public Result<List<CouponActivityVO>> listAvailableActivities() {
        return Result.success(couponService.listAvailableActivities(BaseContext.getCurrentId()));
    }

    @PostMapping("/claim")
    @Operation(summary = "领取优惠券")
    public Result<CouponClaimStatusVO> claim(@RequestBody CouponClaimDTO dto) {
        return Result.success(couponService.claim(BaseContext.getCurrentId(), dto));
    }

    @GetMapping("/claim/{businessNo}")
    @Operation(summary = "查询优惠券领取处理状态")
    public Result<CouponClaimStatusVO> claimStatus(@PathVariable String businessNo) {
        return Result.success(couponService.getClaimStatus(BaseContext.getCurrentId(), businessNo));
    }

    @PostMapping("/redeem")
    @Operation(summary = "使用兑换码领取优惠券")
    public Result<CouponClaimStatusVO> redeem(@RequestBody CouponRedeemDTO dto) {
        return Result.success(couponExchangeService.redeem(BaseContext.getCurrentId(), dto));
    }

    @GetMapping("/mine")
    @Operation(summary = "查询我的优惠券")
    public Result<PageResult> pageMine(@RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "10") Integer pageSize,
                                       @RequestParam(required = false) Integer status) {
        return Result.success(couponService.pageUserCoupons(BaseContext.getCurrentId(), page, pageSize, status));
    }
}
