package com.sky;

import com.alibaba.fastjson.JSON;
import com.sky.dto.CouponClaimMessage;
import com.sky.entity.CouponActivity;
import com.sky.entity.CouponClaimRecord;
import com.sky.entity.CouponTemplate;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponClaimRedisService;
import com.sky.service.impl.CouponClaimPersistenceService;
import com.sky.listener.CouponClaimListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponClaimListenerTest {
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private CouponClaimPersistenceService persistenceService;
    @Mock
    private CouponClaimRedisService claimRedisService;
    @InjectMocks
    private CouponClaimListener listener;

    @Test
    void duplicateMessageForCompletedClaimOnlyCleansRedisMarker() {
        CouponClaimRecord record = record(1);
        when(couponMapper.getClaimByBusinessNo(record.getBusinessNo())).thenReturn(record);
        when(claimRedisService.completeReservation(4L, 9L, record.getBusinessNo())).thenReturn(true);

        listener.processClaim(messageBody(record));

        verify(persistenceService, never()).completeClaim(any(), any(), any(), any());
        verify(claimRedisService).completeReservation(4L, 9L, record.getBusinessNo());
    }

    @Test
    void databaseFailureEscapesListenerForDeadLetterRecovery() {
        CouponClaimRecord record = record(0);
        CouponActivity activity = new CouponActivity();
        activity.setId(4L);
        activity.setTemplateId(5L);
        when(couponMapper.getClaimByBusinessNo(record.getBusinessNo())).thenReturn(record);
        when(couponMapper.getActivity(4L)).thenReturn(activity);
        when(couponMapper.getTemplate(5L)).thenReturn(new CouponTemplate());
        when(persistenceService.completeClaim(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> listener.processClaim(messageBody(record)));

        verify(persistenceService, never()).markFailed(any(), any());
        verify(claimRedisService, never()).compensateReservation(4L, 9L, record.getBusinessNo());
    }

    private CouponClaimRecord record(int status) {
        CouponClaimRecord record = new CouponClaimRecord();
        record.setBusinessNo("claim-request-0001");
        record.setActivityId(4L);
        record.setUserId(9L);
        record.setStatus(status);
        return record;
    }

    private String messageBody(CouponClaimRecord record) {
        CouponClaimMessage message = new CouponClaimMessage();
        message.setBusinessNo(record.getBusinessNo());
        message.setActivityId(record.getActivityId());
        message.setUserId(record.getUserId());
        return JSON.toJSONString(message);
    }
}
