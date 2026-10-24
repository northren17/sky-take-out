package com.sky.service.impl;

import com.sky.entity.OrderCouponSnapshot;
import com.sky.entity.OrderRefundRecord;
import com.sky.entity.PointsAccount;
import com.sky.entity.PointsLedger;
import com.sky.entity.PointsPendingOrder;
import com.sky.entity.PointsRule;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.PointsMapper;
import com.sky.service.PointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PointsServiceImpl implements PointsService {
    private static final String ORDER_EARN = "ORDER_EARN";
    private static final String REFUND_REVERSAL = "REFUND_REVERSAL";

    private final PointsMapper pointsMapper;
    private final CouponMapper couponMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordPendingForPaidOrder(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            throw new OrderBusinessException("生成待发积分缺少订单或用户标识");
        }
        if (pointsMapper.getPendingOrderForUpdate(orderId) != null) return;

        OrderCouponSnapshot snapshot = couponMapper.getOrderCouponSnapshot(orderId);
        if (snapshot == null || snapshot.getItemsAmount() == null || snapshot.getCouponDiscount() == null) {
            throw new OrderBusinessException("订单缺少价格快照，无法计算待发积分");
        }
        BigDecimal eligibleAmount = normalize(snapshot.getItemsAmount().subtract(snapshot.getCouponDiscount()));
        if (eligibleAmount.signum() < 0) {
            throw new OrderBusinessException("订单商品净实付金额无效，无法计算待发积分");
        }

        PointsRule rule = getEffectiveRule(LocalDateTime.now());
        long earnedPoints = calculatePoints(eligibleAmount, rule.getSpendStepAmount(), rule.getPointsPerStep());

        PointsPendingOrder pending = new PointsPendingOrder();
        pending.setOrderId(orderId);
        pending.setUserId(userId);
        pending.setRuleId(rule.getId());
        pending.setEligibleAmount(eligibleAmount);
        pending.setRefundedEligibleAmount(BigDecimal.ZERO.setScale(2));
        pending.setSpendStepAmount(normalize(rule.getSpendStepAmount()));
        pending.setPointsPerStep(rule.getPointsPerStep());
        pending.setEarnedPoints(earnedPoints);
        pending.setPendingPoints(earnedPoints);
        pending.setIssuedPoints(0L);
        pending.setReversedPoints(0L);
        pending.setStatus(PointsPendingOrder.PENDING);
        if (pointsMapper.insertPendingOrder(pending) != 1) {
            throw new OrderBusinessException("保存订单待发积分失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releasePendingPoints(Long orderId) {
        PointsPendingOrder pending = pointsMapper.getPendingOrderForUpdate(orderId);
        if (pending == null) throw new OrderBusinessException("订单待发积分记录不存在");
        if (!Integer.valueOf(PointsPendingOrder.PENDING).equals(pending.getStatus())) {
            throw new OrderBusinessException("该订单积分已释放或已取消");
        }
        if (pointsMapper.getLedgerForUpdate(ORDER_EARN, orderId.toString()) != null) {
            throw new OrderBusinessException("积分流水与待发状态不一致，拒绝重复释放");
        }

        pointsMapper.insertAccountIfAbsent(pending.getUserId());
        PointsAccount account = pointsMapper.getAccountForUpdate(pending.getUserId());
        if (account == null) throw new OrderBusinessException("初始化积分账户失败");

        long points = pending.getPendingPoints();
        long before = account.getBalancePoints();
        long after = Math.addExact(before, points);
        account.setBalancePoints(after);
        account.setTotalEarned(Math.addExact(account.getTotalEarned(), points));
        if (pointsMapper.updateAccount(account) != 1) {
            throw new OrderBusinessException("更新积分账户失败");
        }

        PointsLedger ledger = new PointsLedger();
        ledger.setUserId(pending.getUserId());
        ledger.setBusinessType(ORDER_EARN);
        ledger.setBusinessId(orderId.toString());
        ledger.setIdempotencyKey(ORDER_EARN + ":" + orderId);
        ledger.setChangePoints(points);
        ledger.setBalanceBefore(before);
        ledger.setBalanceAfter(after);
        ledger.setRuleId(pending.getRuleId());
        ledger.setEligibleAmount(pending.getEligibleAmount()
                .subtract(pending.getRefundedEligibleAmount()));
        ledger.setReason("管理员审核通过，释放订单消费积分");
        if (pointsMapper.insertLedger(ledger) != 1) {
            throw new OrderBusinessException("写入订单积分流水失败");
        }

        pending.setIssuedPoints(points);
        pending.setStatus(PointsPendingOrder.RELEASED);
        if (pointsMapper.updatePendingOrder(pending) != 1) {
            throw new OrderBusinessException("更新待发积分状态失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustForRefund(OrderRefundRecord refund) {
        if (refund == null || refund.getOrderId() == null || refund.getOutRefundNo() == null ||
                refund.getEligibleGoodsRefundAmount() == null) {
            throw new OrderBusinessException("退款积分调整缺少业务标识或商品退款金额");
        }

        PointsPendingOrder pending = pointsMapper.getPendingOrderForUpdate(refund.getOrderId());
        if (pending == null) {
            log.warn("退款订单没有 Phase 1 待发积分记录，跳过积分调整，orderId={}, refundNo={}",
                    refund.getOrderId(), refund.getOutRefundNo());
            return;
        }
        if (Integer.valueOf(PointsPendingOrder.RELEASED).equals(pending.getStatus()) &&
                pointsMapper.getLedgerForUpdate(REFUND_REVERSAL, refund.getOutRefundNo()) != null) {
            return;
        }

        BigDecimal refundEligibleAmount = normalize(refund.getEligibleGoodsRefundAmount());
        if (refundEligibleAmount.signum() < 0) {
            throw new OrderBusinessException("商品净实付退款金额不能为负数");
        }
        BigDecimal cumulativeRefund = normalize(
                pending.getRefundedEligibleAmount().add(refundEligibleAmount));
        if (cumulativeRefund.compareTo(pending.getEligibleAmount()) > 0) {
            throw new OrderBusinessException("累计商品净实付退款金额超过订单可计积分金额");
        }

        BigDecimal remainingEligibleAmount = pending.getEligibleAmount().subtract(cumulativeRefund);
        long remainingEarnedPoints = calculatePoints(
                remainingEligibleAmount, pending.getSpendStepAmount(), pending.getPointsPerStep());
        pending.setRefundedEligibleAmount(cumulativeRefund);

        if (Integer.valueOf(PointsPendingOrder.CANCELLED).equals(pending.getStatus())) {
            if (pointsMapper.updatePendingOrder(pending) != 1) {
                throw new OrderBusinessException("更新已取消的待发积分退款状态失败");
            }
            return;
        }
        if (Integer.valueOf(PointsPendingOrder.PENDING).equals(pending.getStatus())) {
            pending.setPendingPoints(remainingEarnedPoints);
            if (pending.getEligibleAmount().signum() > 0 &&
                    cumulativeRefund.compareTo(pending.getEligibleAmount()) == 0) {
                pending.setStatus(PointsPendingOrder.CANCELLED);
            }
            if (pointsMapper.updatePendingOrder(pending) != 1) {
                throw new OrderBusinessException("更新退款后的待发积分失败");
            }
            return;
        }

        if (!Integer.valueOf(PointsPendingOrder.RELEASED).equals(pending.getStatus())) {
            throw new OrderBusinessException("积分待发记录状态不允许处理退款");
        }
        long desiredReversedPoints = pending.getIssuedPoints() -
                Math.min(pending.getIssuedPoints(), remainingEarnedPoints);
        long reversalPoints = desiredReversedPoints - pending.getReversedPoints();
        if (reversalPoints < 0) {
            throw new OrderBusinessException("退款积分累计值异常，拒绝增加已发积分");
        }

        pointsMapper.insertAccountIfAbsent(pending.getUserId());
        PointsAccount account = pointsMapper.getAccountForUpdate(pending.getUserId());
        if (account == null) throw new OrderBusinessException("积分账户不存在，无法处理退款积分");
        long before = account.getBalancePoints();
        long after = Math.subtractExact(before, reversalPoints);
        if (reversalPoints > 0) {
            account.setBalancePoints(after);
            if (pointsMapper.updateAccount(account) != 1) {
                throw new OrderBusinessException("退款后更新积分账户失败");
            }
        }

        PointsLedger reversal = new PointsLedger();
        reversal.setUserId(pending.getUserId());
        reversal.setBusinessType(REFUND_REVERSAL);
        reversal.setBusinessId(refund.getOutRefundNo());
        reversal.setIdempotencyKey(REFUND_REVERSAL + ":" + refund.getOutRefundNo());
        reversal.setChangePoints(-reversalPoints);
        reversal.setBalanceBefore(before);
        reversal.setBalanceAfter(after);
        reversal.setRuleId(pending.getRuleId());
        reversal.setEligibleAmount(refundEligibleAmount);
        reversal.setReason("退款成功，按累计商品净实付退款金额调整积分");
        if (pointsMapper.insertLedger(reversal) != 1) {
            throw new OrderBusinessException("写入退款积分流水失败");
        }

        pending.setReversedPoints(desiredReversedPoints);
        if (pointsMapper.updatePendingOrder(pending) != 1) {
            throw new OrderBusinessException("更新退款累计积分状态失败");
        }
    }

    private PointsRule getEffectiveRule(LocalDateTime now) {
        List<PointsRule> rules = pointsMapper.listEffectiveRules(now);
        if (rules == null || rules.size() != 1) {
            throw new OrderBusinessException("当前没有唯一有效的积分规则");
        }
        PointsRule rule = rules.get(0);
        if (rule.getSpendStepAmount() == null || rule.getSpendStepAmount().signum() <= 0 ||
                rule.getPointsPerStep() == null || rule.getPointsPerStep() <= 0) {
            throw new OrderBusinessException("当前积分规则配置无效");
        }
        return rule;
    }

    private long calculatePoints(BigDecimal eligibleAmount, BigDecimal stepAmount, Integer pointsPerStep) {
        if (eligibleAmount.signum() <= 0) return 0L;
        return eligibleAmount.divideToIntegralValue(stepAmount)
                .multiply(BigDecimal.valueOf(pointsPerStep))
                .longValueExact();
    }

    private BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
