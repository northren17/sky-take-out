package com.sky;

import com.sky.dto.CouponClaimDTO;
import com.sky.entity.CouponActivity;
import com.sky.entity.CouponClaimRecord;
import com.sky.entity.CouponTemplate;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponClaimRedisService;
import com.sky.service.CouponRuleCache;
import com.sky.service.impl.CouponClaimPersistenceService;
import com.sky.service.impl.CouponServiceImpl;
import com.sky.vo.CouponClaimStatusVO;
import com.sky.vo.CouponActivityRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private CouponClaimRedisService claimRedisService;
    @Mock
    private CouponClaimPersistenceService claimPersistenceService;
    @Mock
    private CouponRuleCache ruleCache;
    @InjectMocks
    private CouponServiceImpl couponService;

    @Test
    void claimReturnsPendingStatusAfterDurableReservationIsQueued() {
        Long userId = 91L;
        Long activityId = 12L;
        String requestId = "d9f63277-1edc-42ba-bb7a-9d7be10039d0";
        LocalDateTime now = LocalDateTime.now();
        CouponActivity activity = activity(activityId, now);
        CouponTemplate template = template();
        when(couponMapper.getClaimByBusinessNo(requestId)).thenReturn(null);
        stubClaimRules(activityId, activity);
        when(couponMapper.countPendingClaims(activityId)).thenReturn(0);
        when(couponMapper.countSuccessfulClaims(activityId, userId)).thenReturn(0);
        when(ruleCache.getTemplate(eq(5L), any())).thenReturn(template);
        CouponClaimRecord pending = claimRecord(100L, requestId, activityId, userId, 0);
        when(claimPersistenceService.createPendingRecord(requestId, activityId, userId)).thenReturn(pending);
        when(claimRedisService.reserve(eq(activity), eq(userId), eq(requestId), any())).thenReturn(1L);
        when(couponMapper.activateClaimDispatch(100L)).thenReturn(1);

        CouponClaimStatusVO result = couponService.claim(userId, claimRequest(activityId, requestId));

        assertEquals(requestId, result.getBusinessNo());
        assertEquals(0, result.getStatus());
        verify(claimRedisService).prepareActivity(activity, 0);
        verify(claimRedisService).initializeUserClaimCount(activityId, userId, 0);
        verify(couponMapper).activateClaimDispatch(100L);
        verify(claimPersistenceService, never()).completeClaim(any(), any(), any(), any());
    }

    @Test
    void repeatClaimReturnsThePreviouslyIssuedCouponWithoutConsumingStock() {
        Long userId = 91L;
        Long activityId = 12L;
        String requestId = "d9f63277-1edc-42ba-bb7a-9d7be10039d0";
        CouponClaimRecord prior = new CouponClaimRecord();
        prior.setBusinessNo(requestId);
        prior.setActivityId(activityId);
        prior.setUserId(userId);
        prior.setStatus(1);
        prior.setUserCouponId(200L);
        when(couponMapper.getClaimByBusinessNo(requestId)).thenReturn(prior);

        CouponClaimStatusVO result = couponService.claim(userId, claimRequest(activityId, requestId));
        assertEquals(1, result.getStatus());
        assertEquals(200L, result.getUserCouponId());
        verify(claimRedisService, never()).reserve(any(), anyLong(), any(), any());
        verify(claimPersistenceService, never()).createPendingRecord(any(), any(), any());
    }

    @Test
    void rejectsClaimWhenPerUserLimitWasReached() {
        Long userId = 91L;
        Long activityId = 12L;
        CouponActivity activity = activity(activityId, LocalDateTime.now());
        String requestId = "d9f63277-1edc-42ba-bb7a-9d7be10039d0";
        when(couponMapper.getClaimByBusinessNo(requestId)).thenReturn(null);
        stubClaimRules(activityId, activity);
        when(couponMapper.countPendingClaims(activityId)).thenReturn(0);
        when(couponMapper.countSuccessfulClaims(activityId, userId)).thenReturn(1);
        when(ruleCache.getTemplate(eq(5L), any())).thenReturn(template());
        CouponClaimRecord pending = claimRecord(101L, requestId, activityId, userId, 0);
        when(claimPersistenceService.createPendingRecord(requestId, activityId, userId)).thenReturn(pending);
        when(claimRedisService.reserve(eq(activity), eq(userId), eq(requestId), any())).thenReturn(-4L);

        CouponClaimStatusVO result = couponService.claim(userId, claimRequest(activityId, requestId));
        assertEquals(2, result.getStatus());
        assertEquals("已达到该活动的个人领取上限", result.getFailureReason());
        verify(claimPersistenceService).markFailed(101L, "已达到该活动的个人领取上限");
        verify(claimPersistenceService, never()).completeClaim(any(), any(), any(), any());
    }

    @Test
    void uncertainRedisReservationReturnsAQueryablePendingStatus() {
        Long userId = 91L;
        Long activityId = 12L;
        String requestId = "d9f63277-1edc-42ba-bb7a-9d7be10039d0";
        CouponActivity activity = activity(activityId, LocalDateTime.now());
        when(couponMapper.getClaimByBusinessNo(requestId)).thenReturn(null);
        stubClaimRules(activityId, activity);
        when(couponMapper.countPendingClaims(activityId)).thenReturn(0);
        when(couponMapper.countSuccessfulClaims(activityId, userId)).thenReturn(0);
        when(ruleCache.getTemplate(eq(5L), any())).thenReturn(template());
        CouponClaimRecord pending = claimRecord(103L, requestId, activityId, userId, 0);
        when(claimPersistenceService.createPendingRecord(requestId, activityId, userId)).thenReturn(pending);
        when(claimRedisService.reserve(eq(activity), eq(userId), eq(requestId), any()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        CouponClaimStatusVO result = couponService.claim(userId, claimRequest(activityId, requestId));

        assertEquals(0, result.getStatus());
        assertEquals(requestId, result.getBusinessNo());
        verify(claimPersistenceService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void requestStatusIsOnlyVisibleToTheAuthenticatedClaimOwner() {
        Long userId = 91L;
        Long activityId = 12L;
        String requestId = "d9f63277-1edc-42ba-bb7a-9d7be10039d0";
        when(couponMapper.getClaimByBusinessNo(requestId))
                .thenReturn(claimRecord(102L, requestId, activityId, userId, 0));
        assertThrows(CouponBusinessException.class,
                () -> couponService.getClaimStatus(userId + 1, requestId));
    }

    private CouponActivity activity(Long activityId, LocalDateTime now) {
        CouponActivity activity = new CouponActivity();
        activity.setId(activityId);
        activity.setTemplateId(5L);
        activity.setStatus(1);
        activity.setStockRemaining(5);
        activity.setPerUserLimit(1);
        activity.setStartTime(now.minusMinutes(1));
        activity.setEndTime(now.plusMinutes(30));
        return activity;
    }

    private void stubClaimRules(Long activityId, CouponActivity activity) {
        CouponActivityRule rule = new CouponActivityRule();
        rule.setId(activity.getId());
        rule.setTemplateId(activity.getTemplateId());
        rule.setName(activity.getName());
        rule.setPerUserLimit(activity.getPerUserLimit());
        rule.setStartTime(activity.getStartTime());
        rule.setEndTime(activity.getEndTime());
        rule.setStatus(activity.getStatus());
        when(ruleCache.getActivityRule(eq(activityId), any())).thenReturn(rule);
        when(couponMapper.getActivityInventory(activityId)).thenReturn(activity);
    }

    private CouponTemplate template() {
        CouponTemplate template = new CouponTemplate();
        template.setId(5L);
        template.setName("Welcome");
        template.setStatus(1);
        template.setCouponType(1);
        template.setThresholdAmount(new BigDecimal("20.00"));
        template.setDiscountAmount(new BigDecimal("5.00"));
        template.setDiscountRate(new BigDecimal("0.9000"));
        template.setMaxDiscountAmount(BigDecimal.ZERO);
        template.setValidityType(1);
        template.setValidityDays(7);
        template.setScopeType(1);
        return template;
    }

    private CouponClaimDTO claimRequest(Long activityId, String requestId) {
        CouponClaimDTO dto = new CouponClaimDTO();
        dto.setActivityId(activityId);
        dto.setRequestId(requestId);
        return dto;
    }

    private CouponClaimRecord claimRecord(Long id, String businessNo, Long activityId,
                                          Long userId, Integer status) {
        CouponClaimRecord record = new CouponClaimRecord();
        record.setId(id);
        record.setBusinessNo(businessNo);
        record.setActivityId(activityId);
        record.setUserId(userId);
        record.setStatus(status);
        return record;
    }
}
