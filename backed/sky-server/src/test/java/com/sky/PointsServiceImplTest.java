package com.sky;

import com.sky.entity.OrderCouponSnapshot;
import com.sky.entity.OrderRefundRecord;
import com.sky.entity.PointsAccount;
import com.sky.entity.PointsLedger;
import com.sky.entity.PointsPendingOrder;
import com.sky.entity.PointsRule;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.PointsMapper;
import com.sky.service.impl.PointsServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PointsServiceImplTest {
    @Mock
    private PointsMapper pointsMapper;
    @Mock
    private CouponMapper couponMapper;
    @InjectMocks
    private PointsServiceImpl pointsService;

    @Test
    void paymentCreatesUnspendablePendingPointsFromNetMerchandiseAmount() {
        OrderCouponSnapshot snapshot = snapshot("33.00", "3.01");
        when(pointsMapper.getPendingOrderForUpdate(17L)).thenReturn(null);
        when(couponMapper.getOrderCouponSnapshot(17L)).thenReturn(snapshot);
        when(pointsMapper.listEffectiveRules(any())).thenReturn(List.of(activeRule()));
        when(pointsMapper.insertPendingOrder(any(PointsPendingOrder.class))).thenReturn(1);

        pointsService.recordPendingForPaidOrder(17L, 5L);

        ArgumentCaptor<PointsPendingOrder> captor = ArgumentCaptor.forClass(PointsPendingOrder.class);
        verify(pointsMapper).insertPendingOrder(captor.capture());
        assertEquals(new BigDecimal("29.99"), captor.getValue().getEligibleAmount());
        assertEquals(2L, captor.getValue().getEarnedPoints());
        assertEquals(2L, captor.getValue().getPendingPoints());
        assertEquals(PointsPendingOrder.PENDING, captor.getValue().getStatus());
        verify(pointsMapper, never()).insertAccountIfAbsent(5L);
    }

    @Test
    void repeatedPaymentCallbackDoesNotCreateAnotherPendingRecord() {
        when(pointsMapper.getPendingOrderForUpdate(17L)).thenReturn(new PointsPendingOrder());

        pointsService.recordPendingForPaidOrder(17L, 5L);

        verify(pointsMapper, never()).insertPendingOrder(any(PointsPendingOrder.class));
        verify(couponMapper, never()).getOrderCouponSnapshot(17L);
    }

    @Test
    void adminReleaseMovesPendingPointsIntoBalanceAndAllowsDebtOffset() {
        PointsPendingOrder pending = pending(17L, "50.00", 5L, 5L, PointsPendingOrder.PENDING);
        PointsAccount account = account(-3L, 0L, 0L);
        when(pointsMapper.getPendingOrderForUpdate(17L)).thenReturn(pending);
        when(pointsMapper.getLedgerForUpdate("ORDER_EARN", "17")).thenReturn(null);
        when(pointsMapper.getAccountForUpdate(5L)).thenReturn(account);
        when(pointsMapper.updateAccount(any(PointsAccount.class))).thenReturn(1);
        when(pointsMapper.insertLedger(any(PointsLedger.class))).thenReturn(1);
        when(pointsMapper.updatePendingOrder(pending)).thenReturn(1);

        pointsService.releasePendingPoints(17L);

        assertEquals(2L, account.getBalancePoints());
        assertEquals(5L, account.getTotalEarned());
        assertEquals(5L, pending.getIssuedPoints());
        assertEquals(PointsPendingOrder.RELEASED, pending.getStatus());
        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsMapper).insertLedger(ledgerCaptor.capture());
        assertEquals(5L, ledgerCaptor.getValue().getChangePoints());
    }

    @Test
    void refundBeforeApprovalReducesPendingPointsWithoutChangingBalance() {
        PointsPendingOrder pending = pending(18L, "29.99", 2L, 2L, PointsPendingOrder.PENDING);
        when(pointsMapper.getPendingOrderForUpdate(18L)).thenReturn(pending);
        when(pointsMapper.updatePendingOrder(pending)).thenReturn(1);

        pointsService.adjustForRefund(refund(18L, "RF18-1", "10.00"));

        assertEquals(new BigDecimal("10.00"), pending.getRefundedEligibleAmount());
        assertEquals(1L, pending.getPendingPoints());
        assertEquals(PointsPendingOrder.PENDING, pending.getStatus());
        verify(pointsMapper, never()).insertAccountIfAbsent(5L);
        verify(pointsMapper, never()).insertLedger(any(PointsLedger.class));
    }

    @Test
    void fullMerchandiseRefundCancelsPendingPoints() {
        PointsPendingOrder pending = pending(19L, "20.00", 2L, 2L, PointsPendingOrder.PENDING);
        when(pointsMapper.getPendingOrderForUpdate(19L)).thenReturn(pending);
        when(pointsMapper.updatePendingOrder(pending)).thenReturn(1);

        pointsService.adjustForRefund(refund(19L, "RF19-1", "20.00"));

        assertEquals(0L, pending.getPendingPoints());
        assertEquals(PointsPendingOrder.CANCELLED, pending.getStatus());
        verify(pointsMapper, never()).insertAccountIfAbsent(5L);
    }

    @Test
    void partialRefundReversesOnlyThePointsNoLongerEarnedAndCanCreateDebt() {
        PointsPendingOrder pending = pending(20L, "50.00", 5L, 5L, PointsPendingOrder.RELEASED);
        pending.setIssuedPoints(5L);
        PointsAccount account = account(1L, 5L, 0L);
        when(pointsMapper.getPendingOrderForUpdate(20L)).thenReturn(pending);
        when(pointsMapper.getLedgerForUpdate("REFUND_REVERSAL", "RF20-1")).thenReturn(null);
        when(pointsMapper.getAccountForUpdate(5L)).thenReturn(account);
        when(pointsMapper.updateAccount(any(PointsAccount.class))).thenReturn(1);
        when(pointsMapper.insertLedger(any(PointsLedger.class))).thenReturn(1);
        when(pointsMapper.updatePendingOrder(pending)).thenReturn(1);

        pointsService.adjustForRefund(refund(20L, "RF20-1", "30.00"));

        assertEquals(-2L, account.getBalancePoints());
        assertEquals(3L, pending.getReversedPoints());
        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsMapper).insertLedger(ledgerCaptor.capture());
        assertEquals(-3L, ledgerCaptor.getValue().getChangePoints());
        assertEquals(new BigDecimal("30.00"), ledgerCaptor.getValue().getEligibleAmount());
    }

    @Test
    void multiplePartialRefundsAccumulateAndNeverReverseMoreThanIssuedPoints() {
        PointsPendingOrder pending = pending(23L, "50.00", 5L, 5L, PointsPendingOrder.RELEASED);
        pending.setIssuedPoints(5L);
        PointsAccount account = account(10L, 5L, 0L);
        when(pointsMapper.getPendingOrderForUpdate(23L)).thenReturn(pending);
        when(pointsMapper.getLedgerForUpdate("REFUND_REVERSAL", "RF23-1")).thenReturn(null);
        when(pointsMapper.getLedgerForUpdate("REFUND_REVERSAL", "RF23-2")).thenReturn(null);
        when(pointsMapper.getAccountForUpdate(5L)).thenReturn(account);
        when(pointsMapper.updateAccount(any(PointsAccount.class))).thenReturn(1);
        when(pointsMapper.insertLedger(any(PointsLedger.class))).thenReturn(1);
        when(pointsMapper.updatePendingOrder(pending)).thenAnswer(invocation -> {
            pending.setVersion(pending.getVersion() + 1);
            return 1;
        });

        pointsService.adjustForRefund(refund(23L, "RF23-1", "20.00"));
        pointsService.adjustForRefund(refund(23L, "RF23-2", "20.00"));

        assertEquals(new BigDecimal("40.00"), pending.getRefundedEligibleAmount());
        assertEquals(4L, pending.getReversedPoints());
        assertEquals(6L, account.getBalancePoints());
        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsMapper, org.mockito.Mockito.times(2)).insertLedger(ledgerCaptor.capture());
        assertEquals(-2L, ledgerCaptor.getAllValues().get(0).getChangePoints());
        assertEquals(-2L, ledgerCaptor.getAllValues().get(1).getChangePoints());
    }

    @Test
    void repeatedRefundEventDoesNotApplyAnotherReversal() {
        PointsPendingOrder pending = pending(21L, "50.00", 5L, 5L, PointsPendingOrder.RELEASED);
        pending.setIssuedPoints(5L);
        when(pointsMapper.getPendingOrderForUpdate(21L)).thenReturn(pending);
        when(pointsMapper.getLedgerForUpdate("REFUND_REVERSAL", "RF21-1"))
                .thenReturn(new PointsLedger());

        pointsService.adjustForRefund(refund(21L, "RF21-1", "30.00"));

        verify(pointsMapper, never()).getAccountForUpdate(5L);
        verify(pointsMapper, never()).updatePendingOrder(any(PointsPendingOrder.class));
    }

    @Test
    void rejectsCumulativeEligibleRefundAboveOriginalGoodsNetPayment() {
        PointsPendingOrder pending = pending(22L, "10.00", 1L, 1L, PointsPendingOrder.PENDING);
        when(pointsMapper.getPendingOrderForUpdate(22L)).thenReturn(pending);

        assertThrows(OrderBusinessException.class,
                () -> pointsService.adjustForRefund(refund(22L, "RF22-1", "10.01")));

        verify(pointsMapper, never()).updatePendingOrder(any(PointsPendingOrder.class));
    }

    private OrderCouponSnapshot snapshot(String items, String discount) {
        OrderCouponSnapshot snapshot = new OrderCouponSnapshot();
        snapshot.setItemsAmount(new BigDecimal(items));
        snapshot.setCouponDiscount(new BigDecimal(discount));
        return snapshot;
    }

    private PointsPendingOrder pending(Long orderId, String eligibleAmount, long earned,
                                       long pendingPoints, int status) {
        PointsPendingOrder pending = new PointsPendingOrder();
        pending.setId(orderId);
        pending.setOrderId(orderId);
        pending.setUserId(5L);
        pending.setRuleId(1L);
        pending.setEligibleAmount(new BigDecimal(eligibleAmount));
        pending.setRefundedEligibleAmount(BigDecimal.ZERO.setScale(2));
        pending.setSpendStepAmount(new BigDecimal("10.00"));
        pending.setPointsPerStep(1);
        pending.setEarnedPoints(earned);
        pending.setPendingPoints(pendingPoints);
        pending.setIssuedPoints(0L);
        pending.setReversedPoints(0L);
        pending.setStatus(status);
        pending.setVersion(0);
        return pending;
    }

    private OrderRefundRecord refund(Long orderId, String refundNo, String eligibleAmount) {
        OrderRefundRecord refund = new OrderRefundRecord();
        refund.setOrderId(orderId);
        refund.setOutRefundNo(refundNo);
        refund.setEligibleGoodsRefundAmount(new BigDecimal(eligibleAmount));
        return refund;
    }

    private PointsAccount account(long balance, long totalEarned, long totalSpent) {
        PointsAccount account = new PointsAccount();
        account.setId(1L);
        account.setUserId(5L);
        account.setBalancePoints(balance);
        account.setTotalEarned(totalEarned);
        account.setTotalSpent(totalSpent);
        account.setVersion(0);
        return account;
    }

    private PointsRule activeRule() {
        PointsRule rule = new PointsRule();
        rule.setId(1L);
        rule.setSpendStepAmount(new BigDecimal("10.00"));
        rule.setPointsPerStep(1);
        return rule;
    }
}
