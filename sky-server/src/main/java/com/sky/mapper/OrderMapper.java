package com.sky.mapper;

import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper {
    /**
     * 插入订单数据
     * @param order
     */
    void insert(Orders order);
    /**
     * 根据订单号和用户id查询订单
     * @param orderNumber
     * @param userId
     */
    @Select("select * from orders where number = #{orderNumber} and user_id= #{userId}")
    Orders getByNumberAndUserId(@Param("orderNumber") String orderNumber, @Param("userId") Long userId);

    /**
     * 修改订单信息
     * @param orders
     */
    void update(Orders orders);
    // 在 OrderMapper.java 中添加
    /**
     * 根据订单号更新订单信息
     * @param orders 包含需要更新的字段
     * @param orderNumber 订单号
     */
    @Update("UPDATE orders SET status = #{orders.status}, pay_status = #{orders.payStatus}, checkout_time = #{orders.checkoutTime} WHERE number = #{orderNumber}")
    void updateByNumber(@Param("orders") Orders orders, @Param("orderNumber") String orderNumber);
}
