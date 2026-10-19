package com.sky;

import com.sky.entity.CouponActivity;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponClaimRedisService;
import com.sky.task.CouponClaimReconciliationTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponClaimReconciliationTaskTest {
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private CouponClaimRedisService claimRedisService;
    @InjectMocks
    private CouponClaimReconciliationTask reconciliationTask;

    @Test
    void inventoryMismatchIsRepairedFromCommittedDatabaseClaims() {
        CouponActivity activity = new CouponActivity();
        activity.setId(4L);
        activity.setStockRemaining(7);
        when(couponMapper.listActivitiesAfterId(anyLong(), anyInt())).thenReturn(List.of(activity));
        when(claimRedisService.acquireReconciliationLock(4L)).thenReturn("lease-token");
        when(claimRedisService.getInventory(4L)).thenReturn(
                new CouponClaimRedisService.InventorySnapshot(8, Map.of(9L, 1)));
        when(couponMapper.countPendingClaims(4L)).thenReturn(0);
        when(couponMapper.getActivity(4L)).thenReturn(activity);
        when(couponMapper.listSuccessfulClaimCounts(4L))
                .thenReturn(List.of(Map.<String, Object>of("userId", 9L, "claimCount", 2L)));
        when(claimRedisService.reconcileInventory(4L, "lease-token", 7, Map.of(9L, 2)))
                .thenReturn(true);

        reconciliationTask.reconcileActivityInventory();

        verify(claimRedisService).reconcileInventory(4L, "lease-token", 7, Map.of(9L, 2));
        verify(claimRedisService).releaseReconciliationLock(4L, "lease-token");
    }
}
