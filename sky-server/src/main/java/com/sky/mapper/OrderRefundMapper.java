package com.sky.mapper;

import com.sky.entity.OrderRefundRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderRefundMapper {
    @Insert("insert into order_refund_record (out_refund_no, order_id, order_number, refund_amount, status) " +
            "values (#{outRefundNo}, #{orderId}, #{orderNumber}, #{refundAmount}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderRefundRecord record);

    @Select("select * from order_refund_record where out_refund_no = #{outRefundNo} for update")
    OrderRefundRecord getForUpdate(@Param("outRefundNo") String outRefundNo);

    @Select("select * from order_refund_record where order_id = #{orderId}")
    OrderRefundRecord getByOrderId(Long orderId);

    @Update("update order_refund_record set response_body = #{responseBody}, update_time = current_timestamp " +
            "where id = #{id} and status = 0")
    int updateResponse(@Param("id") Long id, @Param("responseBody") String responseBody);

    @Update("update order_refund_record set status = #{status}, response_body = #{responseBody}, " +
            "update_time = current_timestamp where id = #{id} and status = 0")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status,
                     @Param("responseBody") String responseBody);
}
