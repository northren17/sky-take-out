package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
     * 根据订单号查询订单
     * @param orderNumber
     * @return
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(@Param("orderNumber") String orderNumber);

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
    /**
     * 分页条件查询并按下单时间排序
     * @param ordersPageQueryDTO
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);
    /**
     * 根据订单号和用户id查询订单
     * @param userId
     */
    @Select("select * from orders where user_id= #{userId}")
    Orders getById( @Param("userId") Long userId);
    /**
     * 根据状态统计订单数量
     * @param status
     */
    @Select("select count(id) from orders where status = #{status}")
    Integer countStatus(Integer status);
    /**
     * 根据状态和下单时间查询订单
     * @param status
     * @param orderTime
     */
    @Select("select * from orders where status = #{status} and order_time < #{orderTime}")
    List<Orders> getByStatusAndOrdertimeLT(Integer status, LocalDateTime orderTime);
    /**
     * 根据id查询订单
     * @param id
     */
    @Select("select * from orders where id=#{id}")
    Orders getByid(Long id);
    /**
     * 根据动态条件统计营业额
     * @param map
     */
    Double sumByMap(Map map);
}
