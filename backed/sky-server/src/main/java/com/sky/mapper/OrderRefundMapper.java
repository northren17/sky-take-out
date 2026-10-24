package com.sky.mapper;

import com.sky.entity.OrderRefundRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface OrderRefundMapper {
    @Insert("insert into order_refund_record " +
            "(out_refund_no, request_id, order_id, order_number, refund_amount, " +
            "eligible_goods_refund_amount, status) " +
            "values (#{outRefundNo}, #{requestId}, #{orderId}, #{orderNumber}, #{refundAmount}, " +
            "#{eligibleGoodsRefundAmount}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderRefundRecord record);

    @Select("select * from order_refund_record where out_refund_no = #{outRefundNo} for update")
    OrderRefundRecord getForUpdate(@Param("outRefundNo") String outRefundNo);

    @Select("select * from order_refund_record where request_id = #{requestId}")
    OrderRefundRecord getByRequestId(@Param("requestId") String requestId);

    @Select("select coalesce(sum(refund_amount), 0.00) from order_refund_record " +
            "where order_id = #{orderId} and status in (0, 1)")
    BigDecimal sumPendingAndSucceededRefundAmounts(@Param("orderId") Long orderId);

    @Select("select coalesce(sum(eligible_goods_refund_amount), 0.00) from order_refund_record " +
            "where order_id = #{orderId} and status in (0, 1)")
    BigDecimal sumPendingAndSucceededEligibleRefundAmounts(@Param("orderId") Long orderId);

    @Select("select coalesce(sum(refund_amount), 0.00) from order_refund_record " +
            "where order_id = #{orderId} and status = 1")
    BigDecimal sumSucceededRefundAmounts(@Param("orderId") Long orderId);

    @Select("select count(*) from order_refund_record where order_id = #{orderId} and status = 0")
    int countPendingRefunds(@Param("orderId") Long orderId);

    @Update("update order_refund_record set response_body = #{responseBody}, update_time = current_timestamp " +
            "where id = #{id} and status = 0")
    int updateResponse(@Param("id") Long id, @Param("responseBody") String responseBody);

    @Update("update order_refund_record set status = #{status}, response_body = #{responseBody}, " +
            "update_time = current_timestamp where id = #{id} and status = 0")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status,
                     @Param("responseBody") String responseBody);
}
