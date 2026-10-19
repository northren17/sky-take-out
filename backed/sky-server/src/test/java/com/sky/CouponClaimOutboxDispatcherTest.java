package com.sky;

import com.sky.dto.CouponClaimMessage;
import com.sky.entity.CouponClaimRecord;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponClaimMessagePublisher;
import com.sky.service.CouponClaimRedisService;
import com.sky.service.impl.CouponClaimPersistenceService;
import com.sky.task.CouponClaimOutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponClaimOutboxDispatcherTest {
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private CouponClaimRedisService claimRedisService;
    @Mock
    private CouponClaimMessagePublisher messagePublisher;
    @Mock
    private CouponClaimPersistenceService claimPersistenceService;
    @InjectMocks
    private CouponClaimOutboxDispatcher dispatcher;

    @Test
    void publishesDueReservationWithStableBusinessIdentity() {
        CouponClaimRecord record = record(0);
        when(couponMapper.listDueClaimDispatches(100)).thenReturn(List.of(record));
        when(claimRedisService.getReservation(4L, "request-0000000001")).thenReturn("9");
        when(couponMapper.reserveClaimDispatch(12L, 10)).thenReturn(1);

        dispatcher.dispatchPendingClaims();

        ArgumentCaptor<CouponClaimMessage> message = ArgumentCaptor.forClass(CouponClaimMessage.class);
        verify(messagePublisher).publish(message.capture());
        assertEquals("request-0000000001", message.getValue().getBusinessNo());
        assertEquals(4L, message.getValue().getActivityId());
        assertEquals(9L, message.getValue().getUserId());
    }

    @Test
    void closesReservationAfterBoundedDispatchAttempts() {
        CouponClaimRecord record = record(8);
        when(couponMapper.listDueClaimDispatches(100)).thenReturn(List.of(record));
        when(claimRedisService.getReservation(4L, "request-0000000001")).thenReturn("9");
        when(claimPersistenceService.failPendingDispatch(12L,
                "领取消息达到最大投递次数，库存已执行补偿")).thenReturn(true);

        dispatcher.dispatchPendingClaims();

        verify(claimRedisService).compensateReservation(4L, 9L, "request-0000000001");
        verify(messagePublisher, never()).publish(any());
    }

    @Test
    void doesNotDispatchPendingRequestWithoutRedisReservation() {
        CouponClaimRecord record = record(0);
        when(couponMapper.listDueClaimDispatches(100)).thenReturn(List.of(record));
        when(claimRedisService.getReservation(4L, "request-0000000001")).thenReturn(null);
        when(claimPersistenceService.failPendingDispatch(12L,
                "未找到Redis预占记录，领取请求无法恢复")).thenReturn(true);

        dispatcher.dispatchPendingClaims();

        verify(messagePublisher, never()).publish(any());
        verify(couponMapper, never()).reserveClaimDispatch(12L, 10);
    }

    private CouponClaimRecord record(int attempts) {
        CouponClaimRecord record = new CouponClaimRecord();
        record.setId(12L);
        record.setBusinessNo("request-0000000001");
        record.setActivityId(4L);
        record.setUserId(9L);
        record.setStatus(0);
        record.setDispatchAttempts(attempts);
        return record;
    }
}
