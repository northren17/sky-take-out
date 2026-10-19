package com.sky;

import com.sky.entity.CouponActivity;
import com.sky.entity.CouponClaimRecord;
import com.sky.entity.CouponTemplate;
import com.sky.entity.UserCoupon;
import com.sky.mapper.CouponMapper;
import com.sky.service.impl.CouponClaimPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponClaimPersistenceServiceTest {
    @Mock
    private CouponMapper couponMapper;
    @InjectMocks
    private CouponClaimPersistenceService persistenceService;

    @Test
    void successfulReservationPersistsStockCouponAndClaimTogether() {
        Long activityId = 4L;
        Long userId = 9L;
        CouponClaimRecord record = new CouponClaimRecord();
        record.setId(12L);
        record.setBusinessNo("claim-request-0001");
        record.setActivityId(activityId);
        record.setUserId(userId);
        record.setStatus(0);
        when(couponMapper.getClaimByBusinessNoForUpdate(record.getBusinessNo())).thenReturn(record);
        when(couponMapper.consumeActivityStock(activityId)).thenReturn(1);
        when(couponMapper.insertUserCoupon(any())).thenAnswer(invocation -> {
            ((UserCoupon) invocation.getArgument(0)).setId(50L);
            return 1;
        });
        when(couponMapper.markClaimSucceeded(12L, 50L)).thenReturn(1);

        Long couponId = persistenceService.completeClaim(record, activity(activityId), template(),
                LocalDateTime.now());

        assertEquals(50L, couponId);
        verify(couponMapper).consumeActivityStock(activityId);
        verify(couponMapper).markClaimSucceeded(12L, 50L);
        verify(couponMapper).insertUserCoupon(any());
    }

    @Test
    void alreadyCompletedClaimDoesNotConsumeStockAgain() {
        CouponClaimRecord record = new CouponClaimRecord();
        record.setId(12L);
        record.setBusinessNo("claim-request-0001");
        record.setActivityId(4L);
        record.setUserId(9L);
        record.setStatus(1);
        record.setUserCouponId(50L);
        when(couponMapper.getClaimByBusinessNoForUpdate(record.getBusinessNo())).thenReturn(record);

        assertEquals(50L, persistenceService.completeClaim(record, activity(4L), template(),
                LocalDateTime.now()));

        verify(couponMapper, never()).consumeActivityStock(4L);
        verify(couponMapper, never()).insertUserCoupon(any());
    }

    private CouponActivity activity(Long id) {
        CouponActivity activity = new CouponActivity();
        activity.setId(id);
        activity.setTemplateId(5L);
        return activity;
    }

    private CouponTemplate template() {
        CouponTemplate template = new CouponTemplate();
        template.setId(5L);
        template.setName("Welcome");
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
}
