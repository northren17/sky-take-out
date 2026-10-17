package com.sky.service.impl;

import com.sky.entity.CouponActivity;
import com.sky.entity.CouponClaimRecord;
import com.sky.entity.CouponExchangeRedemption;
import com.sky.entity.CouponTemplate;
import com.sky.entity.UserCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CouponClaimPersistenceService {
    private final CouponMapper couponMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CouponClaimRecord createPendingRecord(String businessNo, Long activityId, Long userId) {
        CouponActivity activity = couponMapper.getActivityForUpdate(activityId);
        LocalDateTime now = LocalDateTime.now();
        if (activity == null || activity.getStatus() != 1 || now.isBefore(activity.getStartTime()) ||
                !now.isBefore(activity.getEndTime())) {
            throw new CouponBusinessException("活动状态已变化，当前不可领取");
        }
        CouponTemplate template = couponMapper.getTemplateForUpdate(activity.getTemplateId());
        if (template == null || template.getStatus() != 1) {
            throw new CouponBusinessException("优惠券规则状态已变化，当前不可领取");
        }
        if (template.getValidityType() == 2 && (now.isBefore(template.getValidStartTime()) ||
                !template.getValidEndTime().isAfter(now))) {
            throw new CouponBusinessException("优惠券规则当前不在有效期内");
        }
        CouponClaimRecord record = new CouponClaimRecord();
        record.setBusinessNo(businessNo);
        record.setActivityId(activityId);
        record.setUserId(userId);
        if (couponMapper.insertClaimRecord(record) != 1) {
            throw new CouponBusinessException("创建领取处理记录失败");
        }
        return record;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Long completeClaim(CouponClaimRecord request, CouponActivity activity,
                              CouponTemplate template, LocalDateTime now) {
        CouponClaimRecord record = couponMapper.getClaimByBusinessNoForUpdate(request.getBusinessNo());
        if (record == null || !record.getActivityId().equals(request.getActivityId()) ||
                !record.getUserId().equals(request.getUserId())) {
            throw new CouponBusinessException("领取处理记录与请求不匹配");
        }
        if (record.getStatus() == 1) return record.getUserCouponId();
        if (record.getStatus() != 0) {
            throw new CouponBusinessException("领取任务已失败，不能重复持久化");
        }
        if (couponMapper.consumeActivityStock(activity.getId()) != 1) {
            throw new CouponBusinessException("数据库活动库存与Redis库存不一致");
        }
        UserCoupon userCoupon = createUserCoupon(activity, template, record.getId(), record.getUserId(), now);
        if (couponMapper.insertUserCoupon(userCoupon) != 1) {
            throw new CouponBusinessException("创建用户优惠券失败");
        }
        if (couponMapper.markClaimSucceeded(record.getId(), userCoupon.getId()) != 1) {
            throw new CouponBusinessException("更新领取结果失败");
        }
        CouponExchangeRedemption redemption =
                couponMapper.getExchangeRedemptionByBusinessNoForUpdate(record.getBusinessNo());
        if (redemption != null && redemption.getStatus() == 0) {
            if (couponMapper.markExchangeRedemptionSucceeded(redemption.getId()) != 1 ||
                    couponMapper.incrementExchangeCodeRedeemedCount(redemption.getExchangeCodeId()) != 1) {
                throw new CouponBusinessException("更新兑换码核销状态失败");
            }
        }
        return userCoupon.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(Long recordId, String reason) {
        String failureReason = StringUtils.hasText(reason) ? reason : "领取处理失败";
        if (failureReason.length() > 255) failureReason = failureReason.substring(0, 255);
        CouponClaimRecord current = couponMapper.getClaimById(recordId);
        if (couponMapper.markClaimFailed(recordId, failureReason) != 1) {
            if (current == null || current.getStatus() != 1 && current.getStatus() != 2) {
                throw new CouponBusinessException("更新领取失败状态失败");
            }
        }
        if (current != null && current.getStatus() != 1) {
            couponMapper.markExchangeRedemptionFailed(current.getBusinessNo(), failureReason);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean failPendingDispatch(Long recordId, String reason) {
        CouponClaimRecord record = couponMapper.getClaimById(recordId);
        if (record == null) throw new CouponBusinessException("领取处理记录不存在");
        String failureReason = StringUtils.hasText(reason) ? reason : "领取处理失败";
        if (failureReason.length() > 255) failureReason = failureReason.substring(0, 255);
        if (couponMapper.failDueClaimDispatch(recordId, failureReason) != 1) return false;
        couponMapper.markExchangeRedemptionFailed(record.getBusinessNo(), failureReason);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failExchangeRedemption(String businessNo, String reason) {
        String failureReason = StringUtils.hasText(reason) ? reason : "兑换请求未完成";
        if (failureReason.length() > 255) failureReason = failureReason.substring(0, 255);
        couponMapper.markExchangeRedemptionFailed(businessNo, failureReason);
    }

    private UserCoupon createUserCoupon(CouponActivity activity, CouponTemplate template,
                                        Long claimRecordId, Long userId, LocalDateTime now) {
        UserCoupon coupon = new UserCoupon();
        coupon.setClaimRecordId(claimRecordId);
        coupon.setActivityId(activity.getId());
        coupon.setTemplateId(template.getId());
        coupon.setUserId(userId);
        coupon.setCouponNameSnapshot(template.getName());
        coupon.setCouponTypeSnapshot(template.getCouponType());
        coupon.setThresholdAmountSnapshot(template.getThresholdAmount());
        coupon.setDiscountAmountSnapshot(template.getDiscountAmount());
        coupon.setDiscountRateSnapshot(template.getDiscountRate());
        coupon.setMaxDiscountAmountSnapshot(template.getMaxDiscountAmount());
        coupon.setScopeTypeSnapshot(template.getScopeType());
        coupon.setScopeDataSnapshot(template.getScopeData());
        coupon.setValidStartTime(template.getValidityType() == 2 ? template.getValidStartTime() : now);
        coupon.setValidEndTime(template.getValidityType() == 2
                ? template.getValidEndTime() : now.plusDays(template.getValidityDays()));
        return coupon;
    }
}
