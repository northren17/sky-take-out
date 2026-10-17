package com.sky.task;

import com.sky.dto.CouponClaimDTO;
import com.sky.entity.CouponExchangeRedemption;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponService;
import com.sky.service.impl.CouponClaimPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CouponExchangeRecoveryTask {
    private static final int BATCH_SIZE = 100;

    private final CouponMapper couponMapper;
    private final CouponService couponService;
    private final CouponClaimPersistenceService claimPersistenceService;

    @Scheduled(fixedDelayString = "${sky.coupon.exchange-recovery-delay-ms:30000}",
            initialDelayString = "${sky.coupon.exchange-recovery-initial-delay-ms:30000}")
    public void recoverPendingRedemptions() {
        List<CouponExchangeRedemption> redemptions;
        try {
            redemptions = couponMapper.listPendingExchangeRedemptions(BATCH_SIZE);
        } catch (RuntimeException failure) {
            log.error("查询待恢复兑换码记录失败", failure);
            return;
        }
        for (CouponExchangeRedemption redemption : redemptions) {
            recover(redemption);
        }
    }

    private void recover(CouponExchangeRedemption redemption) {
        CouponClaimDTO claim = new CouponClaimDTO();
        claim.setActivityId(redemption.getActivityId());
        claim.setRequestId(redemption.getBusinessNo());
        try {
            var result = couponService.claim(redemption.getUserId(), claim);
            if (result.getStatus() == 2) {
                claimPersistenceService.failExchangeRedemption(
                        redemption.getBusinessNo(), result.getFailureReason());
            }
        } catch (CouponBusinessException permanentFailure) {
            claimPersistenceService.failExchangeRedemption(
                    redemption.getBusinessNo(), permanentFailure.getMessage());
            log.warn("兑换码领取恢复被业务规则拒绝，businessNo={}, reason={}",
                    redemption.getBusinessNo(), permanentFailure.getMessage());
        } catch (RuntimeException temporaryFailure) {
            log.error("兑换码领取恢复暂时失败，后续将重试，businessNo={}",
                    redemption.getBusinessNo(), temporaryFailure);
        }
    }
}
