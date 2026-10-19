package com.sky.service.impl;

import com.sky.entity.CouponActivity;
import com.sky.entity.CouponExchangeCode;
import com.sky.entity.CouponExchangeRedemption;
import com.sky.entity.CouponTemplate;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CouponExchangePersistenceService {
    private final CouponMapper couponMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CouponExchangeRedemption prepareRedemption(String codeValue, Long userId, String newBusinessNo) {
        CouponExchangeCode code = couponMapper.getExchangeCodeForUpdate(codeValue);
        if (code == null) throw new CouponBusinessException("兑换码不存在");

        CouponExchangeRedemption existing =
                couponMapper.getExchangeRedemptionForUpdate(code.getId(), userId);
        if (existing != null) {
            if (existing.getStatus() == 2) {
                throw new CouponBusinessException(existing.getFailureReason() == null
                        ? "该兑换码此前兑换失败" : existing.getFailureReason());
            }
            existing.setActivityId(code.getActivityId());
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        if (code.getStatus() != 0 ||
                code.getExpiresAt() != null && !code.getExpiresAt().isAfter(now)) {
            throw new CouponBusinessException("兑换码已失效或已兑换完毕");
        }
        CouponActivity activity = couponMapper.getActivityForUpdate(code.getActivityId());
        if (activity == null || activity.getStatus() != 1 ||
                now.isBefore(activity.getStartTime()) || !now.isBefore(activity.getEndTime())) {
            throw new CouponBusinessException("兑换码关联活动当前不可兑换");
        }
        CouponTemplate template = couponMapper.getTemplateForUpdate(activity.getTemplateId());
        if (template == null || template.getStatus() != 1) {
            throw new CouponBusinessException("兑换码关联优惠券规则当前不可用");
        }
        if (template.getValidityType() == 2 &&
                (now.isBefore(template.getValidStartTime()) || !template.getValidEndTime().isAfter(now))) {
            throw new CouponBusinessException("优惠券规则当前不在有效期内");
        }
        if (couponMapper.countActiveExchangeRedemptions(code.getId()) >= code.getMaxRedeemCount()) {
            throw new CouponBusinessException("兑换码已达到可兑换次数");
        }

        CouponExchangeRedemption redemption = new CouponExchangeRedemption();
        redemption.setExchangeCodeId(code.getId());
        redemption.setActivityId(code.getActivityId());
        redemption.setBusinessNo(newBusinessNo);
        redemption.setUserId(userId);
        redemption.setStatus(0);
        if (couponMapper.insertExchangeRedemption(redemption) != 1) {
            throw new CouponBusinessException("创建兑换处理记录失败");
        }
        return redemption;
    }
}
