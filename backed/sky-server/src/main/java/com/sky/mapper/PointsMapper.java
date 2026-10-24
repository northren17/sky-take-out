package com.sky.mapper;

import com.sky.entity.PointsAccount;
import com.sky.entity.PointsLedger;
import com.sky.entity.PointsPendingOrder;
import com.sky.entity.PointsRule;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PointsMapper {
    @Insert("insert ignore into points_account (user_id, balance_points, total_earned, total_spent, version) " +
            "values (#{userId}, 0, 0, 0, 0)")
    int insertAccountIfAbsent(@Param("userId") Long userId);

    @Select("select * from points_account where user_id = #{userId} for update")
    PointsAccount getAccountForUpdate(@Param("userId") Long userId);

    @Select("select * from points_rule where status = 1 and effective_from <= #{now} " +
            "and (effective_to is null or effective_to > #{now}) order by version desc")
    List<PointsRule> listEffectiveRules(@Param("now") LocalDateTime now);

    @Select("select * from points_pending_order where order_id = #{orderId} for update")
    PointsPendingOrder getPendingOrderForUpdate(@Param("orderId") Long orderId);

    @Insert("insert into points_pending_order " +
            "(order_id, user_id, rule_id, eligible_amount, refunded_eligible_amount, spend_step_amount, " +
            "points_per_step, earned_points, pending_points, issued_points, reversed_points, status, version) " +
            "values (#{orderId}, #{userId}, #{ruleId}, #{eligibleAmount}, #{refundedEligibleAmount}, " +
            "#{spendStepAmount}, #{pointsPerStep}, #{earnedPoints}, #{pendingPoints}, #{issuedPoints}, " +
            "#{reversedPoints}, #{status}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPendingOrder(PointsPendingOrder pendingOrder);

    @Update("update points_pending_order set refunded_eligible_amount = #{refundedEligibleAmount}, " +
            "pending_points = #{pendingPoints}, issued_points = #{issuedPoints}, " +
            "reversed_points = #{reversedPoints}, status = #{status}, version = version + 1, " +
            "update_time = current_timestamp where id = #{id} and version = #{version}")
    int updatePendingOrder(PointsPendingOrder pendingOrder);

    @Select("select * from points_ledger where business_type = #{businessType} " +
            "and business_id = #{businessId} for update")
    PointsLedger getLedgerForUpdate(@Param("businessType") String businessType,
                                    @Param("businessId") String businessId);

    @Insert("insert into points_ledger " +
            "(user_id, business_type, business_id, idempotency_key, change_points, " +
            "balance_before, balance_after, rule_id, eligible_amount, reason) " +
            "values (#{userId}, #{businessType}, #{businessId}, #{idempotencyKey}, #{changePoints}, " +
            "#{balanceBefore}, #{balanceAfter}, #{ruleId}, #{eligibleAmount}, #{reason})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertLedger(PointsLedger ledger);

    @Update("update points_account set balance_points = #{balancePoints}, total_earned = #{totalEarned}, " +
            "total_spent = #{totalSpent}, version = version + 1, update_time = current_timestamp " +
            "where id = #{id} and version = #{version}")
    int updateAccount(PointsAccount account);
}
