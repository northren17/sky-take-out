package com.sky;

import com.sky.entity.CouponActivity;
import com.sky.entity.CouponExchangeCode;
import com.sky.entity.CouponExchangeRedemption;
import com.sky.entity.CouponTemplate;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.service.impl.CouponExchangePersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponExchangePersistenceServiceTest {
    @Mock
    private CouponMapper couponMapper;
    @InjectMocks
    private CouponExchangePersistenceService persistenceService;

    @Test
    void locksAndReservesOneRedemptionForAValidCode() {
        String code = "A".repeat(32);
        CouponExchangeCode exchangeCode = exchangeCode();
        CouponActivity activity = activeActivity();
        CouponTemplate template = activeTemplate();
        when(couponMapper.getExchangeCodeForUpdate(code)).thenReturn(exchangeCode);
        when(couponMapper.getExchangeRedemptionForUpdate(3L, 9L)).thenReturn(null);
        when(couponMapper.getActivityForUpdate(4L)).thenReturn(activity);
        when(couponMapper.getTemplateForUpdate(5L)).thenReturn(template);
        when(couponMapper.countActiveExchangeRedemptions(3L)).thenReturn(0);
        when(couponMapper.insertExchangeRedemption(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    ((CouponExchangeRedemption) invocation.getArgument(0)).setId(8L);
                    return 1;
                });

        CouponExchangeRedemption redemption =
                persistenceService.prepareRedemption(code, 9L, "business-000000000001");

        assertEquals(8L, redemption.getId());
        assertEquals(4L, redemption.getActivityId());
        verify(couponMapper).insertExchangeRedemption(redemption);
    }

    @Test
    void refusesCodeWhenUsageLimitHasBeenReserved() {
        String code = "A".repeat(32);
        CouponExchangeCode exchangeCode = exchangeCode();
        when(couponMapper.getExchangeCodeForUpdate(code)).thenReturn(exchangeCode);
        when(couponMapper.getExchangeRedemptionForUpdate(3L, 9L)).thenReturn(null);
        when(couponMapper.getActivityForUpdate(4L)).thenReturn(activeActivity());
        when(couponMapper.getTemplateForUpdate(5L)).thenReturn(activeTemplate());
        when(couponMapper.countActiveExchangeRedemptions(3L)).thenReturn(1);

        assertThrows(CouponBusinessException.class, () ->
                persistenceService.prepareRedemption(code, 9L, "business-000000000001"));

        verify(couponMapper, never()).insertExchangeRedemption(org.mockito.ArgumentMatchers.any());
    }

    private CouponExchangeCode exchangeCode() {
        CouponExchangeCode code = new CouponExchangeCode();
        code.setId(3L);
        code.setActivityId(4L);
        code.setMaxRedeemCount(1);
        code.setStatus(0);
        return code;
    }

    private CouponActivity activeActivity() {
        CouponActivity activity = new CouponActivity();
        activity.setId(4L);
        activity.setTemplateId(5L);
        activity.setStatus(1);
        activity.setStartTime(LocalDateTime.now().minusMinutes(1));
        activity.setEndTime(LocalDateTime.now().plusMinutes(1));
        return activity;
    }

    private CouponTemplate activeTemplate() {
        CouponTemplate template = new CouponTemplate();
        template.setId(5L);
        template.setStatus(1);
        template.setValidityType(1);
        return template;
    }
}
