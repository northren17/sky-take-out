package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.CouponPageQueryDTO;
import com.sky.entity.CouponActivity;
import com.sky.entity.CouponClaimRecord;
import com.sky.entity.CouponExchangeCode;
import com.sky.entity.CouponExchangeRedemption;
import com.sky.entity.CouponTemplate;
import com.sky.entity.OrderCouponSnapshot;
import com.sky.entity.UserCoupon;
import com.sky.vo.CouponActivityRule;
import com.sky.vo.CouponActivityVO;
import com.sky.vo.AgentCouponSummaryVO;
import com.sky.vo.UserCouponVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CouponMapper {
    Page<CouponTemplate> pageTemplates(CouponPageQueryDTO query);
    CouponTemplate getTemplate(Long id);
    @Select("select * from coupon_template where id = #{id} for update")
    CouponTemplate getTemplateForUpdate(@Param("id") Long id);
    void insertTemplate(CouponTemplate template);
    int updateTemplate(CouponTemplate template);
    int deleteTemplateIfUnused(Long id);
    int countActivitiesByTemplate(Long templateId);

    Page<CouponActivityVO> pageActivities(CouponPageQueryDTO query);
    CouponActivity getActivity(Long id);
    CouponActivityRule getActivityRule(@Param("id") Long id);
    @Select("select id, stock_total, stock_remaining, issued_count, used_count " +
            "from coupon_activity where id = #{id}")
    CouponActivity getActivityInventory(@Param("id") Long id);
    @Select("select * from coupon_activity where id = #{id} for update")
    CouponActivity getActivityForUpdate(Long id);
    void insertActivity(CouponActivity activity);
    int updateActivity(CouponActivity activity);
    int updateActivityStatus(@Param("id") Long id, @Param("status") Integer status);
    List<CouponActivityVO> listAvailableActivities(@Param("userId") Long userId,
                                                    @Param("now") LocalDateTime now);
    List<CouponActivity> listActivitiesAfterId(@Param("afterId") Long afterId,
                                               @Param("limit") int limit);
    List<Map<String, Object>> listSuccessfulClaimCounts(@Param("activityId") Long activityId);
    int countSuccessfulClaims(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Select("select * from coupon_exchange_code where code = #{code} for update")
    CouponExchangeCode getExchangeCodeForUpdate(@Param("code") String code);
    @Select("select * from coupon_exchange_redemption where exchange_code_id = #{codeId} " +
            "and user_id = #{userId} for update")
    CouponExchangeRedemption getExchangeRedemptionForUpdate(@Param("codeId") Long codeId,
                                                            @Param("userId") Long userId);
    @Select("select * from coupon_exchange_redemption where business_no = #{businessNo} for update")
    CouponExchangeRedemption getExchangeRedemptionByBusinessNoForUpdate(
            @Param("businessNo") String businessNo);
    @Select("select count(*) from coupon_exchange_redemption where exchange_code_id = #{codeId} " +
            "and status in (0, 1)")
    int countActiveExchangeRedemptions(@Param("codeId") Long codeId);
    int insertExchangeCodes(@Param("codes") List<CouponExchangeCode> codes);
    @Insert("insert into coupon_exchange_redemption " +
            "(exchange_code_id, business_no, user_id, status) values " +
            "(#{exchangeCodeId}, #{businessNo}, #{userId}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertExchangeRedemption(CouponExchangeRedemption redemption);
    List<CouponExchangeRedemption> listPendingExchangeRedemptions(@Param("limit") int limit);
    @Update("update coupon_exchange_redemption set status = 1, update_time = current_timestamp " +
            "where id = #{id} and status = 0")
    int markExchangeRedemptionSucceeded(@Param("id") Long id);
    @Update("update coupon_exchange_code set redeemed_count = redeemed_count + 1, " +
            "status = case when redeemed_count + 1 >= max_redeem_count then 2 else status end " +
            "where id = #{id} and status = 0 and redeemed_count < max_redeem_count")
    int incrementExchangeCodeRedeemedCount(@Param("id") Long id);
    @Update("update coupon_exchange_redemption set status = 2, failure_reason = #{reason}, " +
            "update_time = current_timestamp where business_no = #{businessNo} and status = 0")
    int markExchangeRedemptionFailed(@Param("businessNo") String businessNo,
                                     @Param("reason") String reason);

    CouponClaimRecord getClaimByBusinessNo(String businessNo);
    @Select("select * from coupon_claim_record where business_no = #{businessNo} for update")
    CouponClaimRecord getClaimByBusinessNoForUpdate(@Param("businessNo") String businessNo);
    @Select("select * from coupon_claim_record where id = #{id}")
    CouponClaimRecord getClaimById(@Param("id") Long id);
    @Select("select count(*) from coupon_claim_record where activity_id = #{activityId} and status = 0")
    int countPendingClaims(@Param("activityId") Long activityId);
    @Select("select count(*) from coupon_claim_record cr join coupon_activity a on a.id = cr.activity_id " +
            "where a.template_id = #{templateId} and cr.status = 0")
    int countPendingClaimsByTemplate(@Param("templateId") Long templateId);
    List<CouponClaimRecord> listPendingClaimRecordsAfterId(@Param("afterId") Long afterId,
                                                           @Param("limit") int limit);
    List<CouponClaimRecord> listDueClaimDispatches(@Param("limit") int limit);
    @Update("update coupon_claim_record set dispatch_attempts = dispatch_attempts + 1, " +
            "next_dispatch_time = date_add(current_timestamp, interval #{delaySeconds} second), " +
            "update_time = current_timestamp where id = #{id} and status = 0 " +
            "and (next_dispatch_time is null or next_dispatch_time <= current_timestamp)")
    int reserveClaimDispatch(@Param("id") Long id, @Param("delaySeconds") int delaySeconds);
    @Update("update coupon_claim_record set next_dispatch_time = current_timestamp, " +
            "update_time = current_timestamp where id = #{id} and status = 0")
    int activateClaimDispatch(@Param("id") Long id);
    @Update("update coupon_claim_record set status = 2, failure_reason = #{reason}, " +
            "update_time = current_timestamp where id = #{id} and status = 0 " +
            "and (next_dispatch_time is null or next_dispatch_time <= current_timestamp)")
    int failDueClaimDispatch(@Param("id") Long id, @Param("reason") String reason);
    @Insert("insert into coupon_claim_record " +
            "(business_no, activity_id, user_id, status, next_dispatch_time) " +
            "values (#{businessNo}, #{activityId}, #{userId}, 0, " +
            "date_add(current_timestamp, interval 60 second))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertClaimRecord(CouponClaimRecord record);
    @Update("update coupon_claim_record set status = 1, user_coupon_id = #{userCouponId}, " +
            "update_time = current_timestamp where id = #{id} and status = 0")
    int markClaimSucceeded(@Param("id") Long id, @Param("userCouponId") Long userCouponId);
    @Update("update coupon_claim_record set status = 2, failure_reason = #{reason}, " +
            "update_time = current_timestamp where id = #{id} and status = 0")
    int markClaimFailed(@Param("id") Long id, @Param("reason") String reason);
    @Update("update coupon_activity set stock_remaining = stock_remaining - 1, " +
            "issued_count = issued_count + 1, update_time = current_timestamp " +
            "where id = #{id} and stock_remaining > 0")
    int consumeActivityStock(Long id);
    int insertUserCoupon(UserCoupon userCoupon);
    Long getClaimedCouponId(@Param("businessNo") String businessNo, @Param("userId") Long userId);

    Page<UserCouponVO> pageUserCoupons(@Param("userId") Long userId,
                                       @Param("status") Integer status,
                                       @Param("now") LocalDateTime now);
    Page<AgentCouponSummaryVO> pageAgentCoupons(@Param("userId") Long userId,
                                                 @Param("status") Integer status,
                                                 @Param("now") LocalDateTime now);

    @Select("select * from user_coupon where id = #{couponId} and user_id = #{userId} for update")
    UserCoupon getUserCouponForUpdate(@Param("couponId") Long couponId, @Param("userId") Long userId);
    @Select("select * from user_coupon where id = #{couponId} for update")
    UserCoupon getUserCouponByIdForUpdate(@Param("couponId") Long couponId);
    @Update("update user_coupon set status = 1, locked_order_id = #{orderId} " +
            "where id = #{couponId} and user_id = #{userId} and status = 0 " +
            "and valid_start_time <= #{now} and valid_end_time > #{now}")
    int lockUserCoupon(@Param("couponId") Long couponId, @Param("userId") Long userId,
                       @Param("orderId") Long orderId, @Param("now") LocalDateTime now);
    @Update("update user_coupon set status = 2, used_order_id = #{orderId}, used_time = #{now} " +
            "where status = 1 and locked_order_id = #{orderId}")
    int useCouponForOrder(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);
    @Update("update user_coupon set status = case when valid_end_time <= #{now} then 3 else 0 end, " +
            "locked_order_id = null where status = 1 and locked_order_id = #{orderId}")
    int releaseCouponForOrder(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);
    @Update("update user_coupon set status = case when valid_end_time <= #{now} then 3 else 0 end, " +
            "used_order_id = null, used_time = null where status = 2 and used_order_id = #{orderId}")
    int restoreCouponAfterRefund(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    void insertOrderCouponSnapshot(OrderCouponSnapshot snapshot);
    OrderCouponSnapshot getOrderCouponSnapshot(Long orderId);
    @Update("update order_coupon_snapshot set coupon_state = #{couponState}, update_time = current_timestamp " +
            "where order_id = #{orderId} and coupon_state = #{expectedState}")
    int updateOrderCouponState(@Param("orderId") Long orderId,
                               @Param("expectedState") Integer expectedState,
                               @Param("couponState") Integer couponState);
    @Update("update coupon_activity set used_count = used_count + 1 " +
            "where id = (select activity_id from order_coupon_snapshot where order_id = #{orderId})")
    int incrementActivityUsedCount(Long orderId);
    @Update("update coupon_activity set used_count = used_count - 1 where id = " +
            "(select activity_id from order_coupon_snapshot where order_id = #{orderId}) and used_count > 0")
    int decrementActivityUsedCount(Long orderId);
}
